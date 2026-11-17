package com.chesed.seckill.server;

import com.chesed.seckill.dedup.BloomDedup;
import com.chesed.seckill.dedup.Deduplicator;
import com.chesed.seckill.dedup.MapDedup;
import com.chesed.seckill.domain.SeckillRequest;
import com.chesed.seckill.domain.SeckillResult;
import com.chesed.seckill.metrics.Metrics;
import com.chesed.seckill.order.SeckillEngine;
import com.chesed.seckill.persistence.H2OrderStore;
import com.chesed.seckill.persistence.MemoryOrderStore;
import com.chesed.seckill.persistence.OrderStore;
import com.chesed.seckill.queue.JdkQueueAdapter;
import com.chesed.seckill.queue.MpscRingQueue;
import com.chesed.seckill.queue.TaskQueue;
import com.chesed.seckill.ratelimit.RateLimiter;
import com.chesed.seckill.ratelimit.SlidingWindowLimiter;
import com.chesed.seckill.ratelimit.TokenBucketLimiter;
import com.chesed.seckill.stock.AdderStock;
import com.chesed.seckill.stock.CasStock;
import com.chesed.seckill.stock.LockStock;
import com.chesed.seckill.stock.StampedStock;
import com.chesed.seckill.stock.Stock;
import com.chesed.seckill.stock.SyncStock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 面板用的 API。整个类只有一份"当前引擎"状态，切换配置时重建引擎。
 * 重建会丢掉正在跑的负载，所以切换前先停负载。
 */
public final class ApiHandler {

    private final Path h2Path;
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "expire-sweeper");
        t.setDaemon(true);
        return t;
    });

    private volatile SeckillEngine engine;
    private volatile Config config = Config.defaults();
    private volatile LoadRunner runner;
    private final Metrics totalMetrics = new Metrics();
    private final AtomicLong startAt = new AtomicLong(System.currentTimeMillis());

    public ApiHandler(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.h2Path = dataDir.resolve("orders");
        rebuild(Config.defaults());
        // 每 5 秒扫一次超时未支付订单，回补库存。真实系统里这一步是延迟队列
        sweeper.scheduleWithFixedDelay(() -> {
            try {
                SeckillEngine e = engine;
                if (e != null) {
                    e.sweepExpired();
                }
            } catch (RuntimeException ignored) {
                // 定时任务不能抛，否则后续不再执行
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /** 引擎配置。字段名就是前端表单的 name，一一对应。 */
    public record Config(String stock, String queue, int queueCapacity, String dedup,
                         String limiter, int limitQps, int workers, String store,
                         int inventory, long ttlMillis, boolean async) {

        static Config defaults() {
            return new Config("CAS", "ArrayBlockingQueue", 8192, "map",
                    "none", 50000, 16, "h2", 1000, 30_000L, true);
        }
    }

    public String dispatch(String path, String method, Map<String, String> q, String body) {
        switch (path) {
            case "/api/config":
                return configJson();
            case "/api/config/apply":
                return applyConfig(body);
            case "/api/metrics":
                return metricsJson();
            case "/api/load/start":
                return startLoad(body);
            case "/api/load/stop":
                return stopLoad();
            case "/api/orders":
                return ordersJson(q);
            case "/api/health":
                return "{\"ok\":true}";
            case "/api/experiments":
                return experimentsJson();
            default:
                throw new WebServer.ApiException(404, "未知接口: " + path);
        }
    }

    private synchronized String configJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        Config c = config;
        m.put("stock", c.stock());
        m.put("queue", c.queue());
        m.put("queueCapacity", c.queueCapacity());
        m.put("dedup", c.dedup());
        m.put("limiter", c.limiter());
        m.put("limitQps", c.limitQps());
        m.put("workers", c.workers());
        m.put("store", c.store());
        m.put("inventory", c.inventory());
        m.put("ttlMillis", c.ttlMillis());
        m.put("async", c.async());
        m.put("describe", engine.describe());
        return Json.of(m);
    }

    private synchronized String applyConfig(String body) {
        stopLoadQuietly();
        Config next = new Config(
                Json.str(body, "stock") == null ? config.stock() : Json.str(body, "stock"),
                Json.str(body, "queue") == null ? config.queue() : Json.str(body, "queue"),
                (int) Json.num(body, "queueCapacity", config.queueCapacity()),
                Json.str(body, "dedup") == null ? config.dedup() : Json.str(body, "dedup"),
                Json.str(body, "limiter") == null ? config.limiter() : Json.str(body, "limiter"),
                (int) Json.num(body, "limitQps", config.limitQps()),
                (int) Json.num(body, "workers", config.workers()),
                Json.str(body, "store") == null ? config.store() : Json.str(body, "store"),
                (int) Json.num(body, "inventory", config.inventory()),
                Json.num(body, "ttlMillis", config.ttlMillis()),
                body != null && body.contains("\"async\":false") ? false : config.async());
        rebuild(next);
        return configJson();
    }

    private synchronized void rebuild(Config c) {
        SeckillEngine old = engine;
        if (old != null) {
            // 先把旧队列里已经受理的任务处理完再停消费者。
            // 不排空就直接 close()，队列里剩下的任务会被丢掉——它们已经扣过库存、
            // 也已经被算进 success，却没落库，于是对账里「已占」比「成交数」少一截
            // （实测第 3 组成交 1000、只落库 136）。
            old.awaitDrain(3000);
            old.close();
            // 旧引擎留下的 CREATED 订单是拿旧库存扣的，必须在这里一次性结清。
            // 否则新一轮的超时扫描器会把它们扫成过期，再调自己那颗 stock 的 restore()，
            // 等于把上一轮卖掉的货补进这一轮的库存里——实测能让库存从 0 涨回 1000。
            try {
                old.store().closeAllCreated();
            } catch (RuntimeException ignore) {
                // 存储不支持就跳过，不影响换配置本身
            }
            // SeckillEngine.close() 只停消费者线程，不关存储；这里得自己关，
            // 否则每切一次配置就漏一个 H2 连接。
            try {
                old.store().close();
            } catch (RuntimeException ignore) {
                // 同上
            }
        }
        Stock stock = buildStock(c.stock(), c.inventory());
        TaskQueue<SeckillEngine.OrderTask> queue = buildQueue(c.queue(), c.queueCapacity());
        Deduplicator dedup = buildDedup(c.dedup());
        RateLimiter limiter = buildLimiter(c.limiter(), c.limitQps());
        OrderStore store = buildStore(c.store());
        SeckillEngine e = new SeckillEngine.Builder()
                .stock(stock)
                .queue(queue)
                .dedup(dedup)
                .limiter(limiter)
                .store(store)
                .metrics(totalMetrics)
                .workers(c.workers())
                .ttlMillis(c.ttlMillis())
                .async(c.async())
                .build();
        totalMetrics.reset();
        startAt.set(System.currentTimeMillis());
        this.engine = e;
        this.config = c;
    }

    private OrderStore buildStore(String kind) {
        if ("memory".equals(kind)) {
            return new MemoryOrderStore();
        }
        // 每次切换配置都开一个新库，避免上一轮的订单混进来
        String url = "jdbc:h2:file:" + h2Path.toAbsolutePath() + ";DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE";
        return new H2OrderStore(url);
    }

    private Stock buildStock(String kind, int inventory) {
        return switch (kind) {
            case "Sync" -> new SyncStock(inventory);
            case "Lock" -> new LockStock(inventory);
            case "Stamped" -> new StampedStock(inventory);
            case "Adder" -> new AdderStock(inventory, false);
            case "AdderUnsafe" -> new AdderStock(inventory, true);
            default -> new CasStock(inventory);
        };
    }

    private TaskQueue<SeckillEngine.OrderTask> buildQueue(String kind, int capacity) {
        int cap = Math.max(64, capacity);
        return switch (kind) {
            case "LinkedBlockingQueue" -> new JdkQueueAdapter<>(kind,
                    new java.util.concurrent.LinkedBlockingQueue<>(cap), cap);
            case "LinkedTransferQueue" -> new JdkQueueAdapter<>(kind,
                    new java.util.concurrent.LinkedTransferQueue<>(), Integer.MAX_VALUE);
            case "MpscRing" -> new MpscRingQueue<>(Integer.highestOneBit(cap));
            default -> new JdkQueueAdapter<>("ArrayBlockingQueue",
                    new java.util.concurrent.ArrayBlockingQueue<>(cap), cap);
        };
    }

    private Deduplicator buildDedup(String kind) {
        return switch (kind == null ? "none" : kind) {
            case "map" -> new MapDedup();
            case "bloom" -> new BloomDedup(1 << 20, 4);
            case "none" -> null;
            default -> new MapDedup();
        };
    }

    private RateLimiter buildLimiter(String kind, int qps) {
        if (kind == null || "none".equals(kind)) {
            return null;
        }
        return switch (kind) {
            case "sliding" -> new SlidingWindowLimiter(Math.max(1, qps), 10);
            case "token" -> new TokenBucketLimiter(Math.max(1, qps), Math.max(1, qps / 10));
            default -> null;
        };
    }

    private String metricsJson() {
        SeckillEngine e = engine;
        Metrics m = totalMetrics;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stockRemaining", e.stock().remaining());
        out.put("stockCapacity", e.stock().capacity());
        out.put("stockDeducted", e.stock().deducted());
        out.put("stockName", e.stock().name());
        out.put("queueName", e.queue().name());
        out.put("queueDepth", e.queue().size());
        out.put("queueCapacity", e.queue().capacity());
        out.put("queuePeak", m.queuePeakDepth());
        out.put("dedupSize", e.dedup() == null ? 0 : e.dedup().size());
        out.put("total", m.total());
        out.put("success", m.success());
        out.put("soldOut", m.soldOut());
        out.put("duplicate", m.duplicate());
        out.put("rateLimited", m.rateLimited());
        out.put("rejected", m.rejected());
        out.put("deadLetter", m.deadLetter());
        out.put("tps", round(m.instantThroughput()));
        // 面板上的延迟用「全部请求」的直方图，这样被拒绝的请求也算进感受里。
        // orderP99 单独给一个：真买到货的人等了多久。
        out.put("p50", round(m.requestLatency().percentileMillis(0.50)));
        out.put("p95", round(m.requestLatency().percentileMillis(0.95)));
        out.put("p99", round(m.requestLatency().percentileMillis(0.99)));
        out.put("p999", round(m.requestLatency().percentileMillis(0.999)));
        out.put("max", round(m.requestLatency().maxMillis()));
        out.put("mean", round(m.requestLatency().meanMillis()));
        out.put("orderP99", round(m.e2e().percentileMillis(0.99)));
        out.put("queueWaitP99", round(m.queueWait().percentileMillis(0.99)));
        out.put("orderCount", safeCount(e));
        out.put("uptimeMs", System.currentTimeMillis() - startAt.get());
        LoadRunner r = runner;
        out.put("loading", r != null && r.isRunning());
        out.put("loadTarget", r == null ? 0 : r.target());
        out.put("loadSent", r == null ? 0 : r.sent());
        return Json.of(out);
    }

    private long safeCount(SeckillEngine e) {
        try {
            return e.store().count();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private synchronized String startLoad(String body) {
        stopLoadQuietly();
        int concurrency = (int) Json.num(body, "concurrency", 128);
        long total = Json.num(body, "total", 20_000);
        boolean sameUser = body != null && body.contains("\"sameUser\":true");
        boolean reuseRequestId = body != null && body.contains("\"reuseRequestId\":true");
        LoadRunner r = new LoadRunner(engine, concurrency, total, sameUser, reuseRequestId);
        this.runner = r;
        r.start();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started", true);
        m.put("concurrency", concurrency);
        m.put("total", total);
        return Json.of(m);
    }

    private synchronized String stopLoad() {
        LoadRunner r = runner;
        if (r != null) {
            r.stop();
        }
        return "{\"stopped\":true}";
    }

    private void stopLoadQuietly() {
        LoadRunner r = runner;
        if (r != null) {
            r.stop();
            runner = null;
        }
    }

    private String ordersJson(Map<String, String> q) {
        int limit = 50;
        try {
            limit = Integer.parseInt(q.getOrDefault("limit", "50"));
        } catch (NumberFormatException ignored) {
            // 用默认值
        }
        SeckillEngine e = engine;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            OrderStore.Reconciliation rec = e.store().reconcile(e.stock().capacity());
            out.put("reconcile", rec);
            for (var o : e.store().recent(limit)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("orderId", o.orderId());
                r.put("userId", o.userId());
                r.put("status", o.status().name());
                r.put("createdMillis", o.createdMillis());
                rows.add(r);
            }
        } catch (RuntimeException ex) {
            out.put("reconcile", null);
            out.put("error", String.valueOf(ex.getMessage()));
        }
        out.put("rows", rows);
        out.put("limit", limit);
        return Json.of(out);
    }

    /** 把 docs/experiments 下的 CSV 列出来，面板直接读。 */
    private String experimentsJson() {
        Path dir = Path.of("docs", "experiments");
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".csv")).sorted().forEach(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.getFileName().toString());
                    try {
                        m.put("lines", Files.lines(p).count());
                    } catch (IOException e) {
                        m.put("lines", 0);
                    }
                    files.add(m);
                });
            } catch (IOException ignored) {
                // 目录读不了就返回空列表
            }
        }
        return Json.of(files);
    }

    public SeckillEngine engine() {
        return engine;
    }

    public void shutdown() {
        stopLoadQuietly();
        sweeper.shutdownNow();
        SeckillEngine e = engine;
        if (e != null) {
            e.close();
        }
    }

    /** 压测线程组。同进程内直接调引擎，避免 HTTP 开销污染并发数据。 */
    static final class LoadRunner {
        private final SeckillEngine engine;
        private final int concurrency;
        private final long total;
        private final boolean sameUser;
        private final boolean reuseRequestId;
        private final AtomicLong sent = new AtomicLong();
        private volatile boolean running = true;
        private final List<Thread> threads = new ArrayList<>();

        LoadRunner(SeckillEngine engine, int concurrency, long total, boolean sameUser, boolean reuseRequestId) {
            this.engine = engine;
            this.concurrency = Math.max(1, concurrency);
            this.total = Math.max(1, total);
            this.sameUser = sameUser;
            this.reuseRequestId = reuseRequestId;
        }

        void start() {
            for (int i = 0; i < concurrency; i++) {
                final int idx = i;
                Thread t = new Thread(() -> {
                    long n = 0;
                    while (running) {
                        long seq = sent.getAndIncrement();
                        if (seq >= total) {
                            return;
                        }
                        long userId = sameUser ? 1L : (seq % 100_000L);
                        String reqId = reuseRequestId ? "fixed" : ("r-" + seq);
                        engine.seckill(new SeckillRequest(userId, 1L, reqId, System.nanoTime()));
                        n++;
                    }
                }, "load-" + idx);
                t.setDaemon(true);
                t.start();
                threads.add(t);
            }
        }

        void stop() {
            running = false;
            for (Thread t : threads) {
                try {
                    t.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        boolean isRunning() {
            return running && sent.get() < total;
        }

        long sent() {
            return Math.min(sent.get(), total);
        }

        long target() {
            return total;
        }
    }
}
