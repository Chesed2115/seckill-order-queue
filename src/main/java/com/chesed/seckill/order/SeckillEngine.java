package com.chesed.seckill.order;

import com.chesed.seckill.dedup.Deduplicator;
import com.chesed.seckill.domain.Order;
import com.chesed.seckill.domain.SeckillRequest;
import com.chesed.seckill.domain.SeckillResult;
import com.chesed.seckill.metrics.Metrics;
import com.chesed.seckill.persistence.OrderStore;
import com.chesed.seckill.queue.TaskQueue;
import com.chesed.seckill.ratelimit.RateLimiter;
import com.chesed.seckill.stock.Stock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀引擎主体。
 *
 * <p>请求路径：
 * <ol>
 *   <li>限流（可选）—— 挡在最前面，最快的拒绝方式</li>
 *   <li>幂等去重 —— 同一用户同一 requestId 只放过去一次</li>
 *   <li>库存 CAS 扣减 —— 防超卖的唯一关键点</li>
 *   <li>生成订单号、入队 —— 同步返回给用户，后续处理异步化</li>
 * </ol>
 * 第 3 步成功但第 4 步入队失败（队列满）时必须回补库存，
 * 否则库存永久丢一件。这个补偿是整个设计里最容易漏掉的地方。
 * </p>
 */
public final class SeckillEngine implements AutoCloseable {

    private final Stock stock;
    private final TaskQueue<OrderTask> queue;
    private final Deduplicator dedup;
    private final RateLimiter limiter;
    private final OrderStore store;
    private final Metrics metrics;
    private final OrderWorker[] workers;

    private final AtomicLong orderSeq = new AtomicLong(1_000_000L);
    private final long ttlMillis;
    private final boolean async;

    private volatile boolean running = true;

    /**
     * 是否在每次入队后采样队列深度。
     *
     * <p>做成开关是因为它本身会污染测量：面板需要这个数字，但不同队列的
     * {@code size()} 代价差了几个数量级。{@code ArrayBlockingQueue.size()}
     * 拿一次锁读个 int；{@code LinkedTransferQueue.size()} 则要加锁遍历
     * 整条链表——队列里积压几十万个节点时，每入队一次遍历一遍，
     * 采样反而成了主要开销。做队列对比实验时必须能把它关掉，
     * 否则测出来的差异是"谁的 size() 更贵"，不是"谁的入队更快"。
     * </p>
     */
    private volatile boolean sampleDepth = true;

    public SeckillEngine(Stock stock, TaskQueue<OrderTask> queue, Deduplicator dedup,
                         RateLimiter limiter, OrderStore store, Metrics metrics,
                         int workerCount, long ttlMillis, boolean async) {
        this.stock = stock;
        this.queue = queue;
        this.dedup = dedup;
        this.limiter = limiter;
        this.store = store;
        this.metrics = metrics;
        this.ttlMillis = ttlMillis;
        this.async = async;
        this.workers = new OrderWorker[Math.max(1, workerCount)];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new OrderWorker(i, this);
            if (async) {
                workers[i].start();
            }
        }
    }

    /** 关掉队列深度采样，用于纯队列性能对比实验。 */
    public void setSampleDepth(boolean v) {
        this.sampleDepth = v;
    }

    /** 秒杀主入口。整个方法必须尽量短——它跑在请求线程上，会是吞吐瓶颈。 */
    public SeckillResult seckill(SeckillRequest req) {
        long t0 = System.nanoTime();
        metrics.recordRequest();

        try {
            return doSeckill(req, t0);
        } finally {
            // 不管成功还是被拒，用户都等了这么久，所以响应时间要记在全部请求上。
            // 成功单的端到端耗时另有 e2e 直方图（含落库），两者用途不同。
            metrics.recordRequestLatency(System.nanoTime() - t0);
        }
    }

    private SeckillResult doSeckill(SeckillRequest req, long t0) {
        if (limiter != null && !limiter.tryAcquire()) {
            metrics.recordRateLimited();
            return SeckillResult.fail(SeckillResult.Status.RATE_LIMITED, System.nanoTime() - t0);
        }

        if (dedup != null && !dedup.firstSeen(req.userId(), req.requestId())) {
            metrics.recordDuplicate();
            return SeckillResult.fail(SeckillResult.Status.DUPLICATE, System.nanoTime() - t0);
        }

        if (!stock.tryDeduct()) {
            metrics.recordSoldOut();
            return SeckillResult.fail(SeckillResult.Status.SOLD_OUT, System.nanoTime() - t0);
        }

        long orderId = orderSeq.getAndIncrement();
        Order order = new Order(orderId, req.userId(), req.skuId(), req.requestId(),
                Order.Status.CREATED, System.currentTimeMillis(), 0L);
        OrderTask task = new OrderTask(order, t0, System.nanoTime());

        if (async) {
            if (!queue.offer(task)) {
                // 队列满了，库存必须还回去，否则这一件永远卖不出去
                stock.restore();
                metrics.recordRejected();
                return SeckillResult.fail(SeckillResult.Status.REJECTED, System.nanoTime() - t0);
            }
            if (sampleDepth) {
                metrics.updateQueueDepth(queue.size());
            }
            metrics.recordSuccess();
            return SeckillResult.ok(orderId, System.nanoTime() - t0);
        }

        // 同步模式：直接落库，用于功能测试和端到端对照
        try {
            store.save(order);
        } catch (RuntimeException e) {
            stock.restore();
            metrics.recordDeadLetter();
            return SeckillResult.fail(SeckillResult.Status.REJECTED, System.nanoTime() - t0);
        }
        metrics.recordSuccess();
        // 同步模式没有 worker，端到端延迟在这里补记
        metrics.recordLatency(System.nanoTime() - t0);
        return SeckillResult.ok(orderId, System.nanoTime() - t0);
    }

    /** 处理一个任务。抽出来供 worker 和同步路径共用。 */
    void handle(OrderTask task) {
        long now = System.nanoTime();
        // 排队等待：从入队到被消费者拿到的时长，反映队列积压程度
        metrics.recordQueueWait(now - task.enqueueNanos());
        try {
            store.save(task.order());
        } catch (RuntimeException e) {
            // 落库失败：订单进死信，库存回补，避免这单占着库存不放
            metrics.recordDeadLetter();
            stock.restore();
        }
        // 端到端：从请求进来到订单落库完成，用户实际等待的时间
        metrics.recordLatency(System.nanoTime() - task.arriveNanos());
    }

    /** 扫描超时未支付订单并回补库存。由定时任务周期性调用。 */
    public int sweepExpired() {
        List<Order> expired = store.closeExpired(ttlMillis);
        for (Order o : expired) {
            stock.restore();
        }
        return expired.size();
    }

    public Metrics metrics() {
        return metrics;
    }

    public Stock stock() {
        return stock;
    }

    public OrderStore store() {
        return store;
    }

    public TaskQueue<OrderTask> queue() {
        return queue;
    }

    public Deduplicator dedup() {
        return dedup;
    }

    public RateLimiter limiter() {
        return limiter;
    }

    /** 等所有已入队任务处理完，用于实验收尾时保证数据一致。 */
    public void awaitDrain(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (queue.size() == 0) {
                // 队列空了还可能有 worker 正在写库，留一点余量
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (queue.size() == 0) {
                    return;
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        for (OrderWorker w : workers) {
            w.shutdown();
        }
    }

    /**
     * 队列里的任务单元。
     * arriveNanos 是请求刚进 seckill() 的时间，用于算端到端延迟；
     * enqueueNanos 是入队那一刻，两者之差就是"请求处理前半段"的耗时。
     */
    public record OrderTask(Order order, long arriveNanos, long enqueueNanos) {
    }

    /** 消费者线程。空转时短暂 park，避免把 CPU 全烧在自旋上。 */
    static final class OrderWorker extends Thread {

        private final SeckillEngine engine;
        private volatile boolean alive = true;

        OrderWorker(int idx, SeckillEngine engine) {
            super("order-worker-" + idx);
            this.engine = engine;
            setDaemon(true);
        }

        @Override
        public void run() {
            while (alive && engine.running) {
                OrderTask task = engine.queue.poll();
                if (task == null) {
                    java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
                    continue;
                }
                engine.handle(task);
            }
        }

        void shutdown() {
            alive = false;
        }
    }

    /** 供实验使用：构造一个全部默认配置的引擎，参数可覆盖。 */
    public static final class Builder {
        private Stock stock;
        private TaskQueue<OrderTask> queue;
        private Deduplicator dedup;
        private RateLimiter limiter;
        private OrderStore store = new com.chesed.seckill.persistence.MemoryOrderStore();
        private Metrics metrics = new Metrics();
        private int workers = 16;
        private long ttlMillis = 30_000L;
        private boolean async = true;

        public Builder stock(Stock v) {
            this.stock = v;
            return this;
        }

        public Builder queue(TaskQueue<OrderTask> v) {
            this.queue = v;
            return this;
        }

        public Builder dedup(Deduplicator v) {
            this.dedup = v;
            return this;
        }

        public Builder limiter(RateLimiter v) {
            this.limiter = v;
            return this;
        }

        public Builder store(OrderStore v) {
            this.store = v;
            return this;
        }

        public Builder metrics(Metrics v) {
            this.metrics = v;
            return this;
        }

        public Builder workers(int v) {
            this.workers = v;
            return this;
        }

        public Builder ttlMillis(long v) {
            this.ttlMillis = v;
            return this;
        }

        public Builder async(boolean v) {
            this.async = v;
            return this;
        }

        public SeckillEngine build() {
            if (stock == null) {
                throw new IllegalStateException("必须指定库存策略");
            }
            if (queue == null) {
                queue = new com.chesed.seckill.queue.JdkQueueAdapter<>(
                        "ArrayBlockingQueue",
                        new java.util.concurrent.ArrayBlockingQueue<>(8192), 8192);
            }
            return new SeckillEngine(stock, queue, dedup, limiter, store, metrics, workers, ttlMillis, async);
        }
    }

    /** 方便面板展示：当前引擎的配置摘要。 */
    public List<String[]> describe() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"库存策略", stock.name()});
        rows.add(new String[]{"队列", queue.name()});
        rows.add(new String[]{"去重", dedup == null ? "关闭" : dedup.name()});
        rows.add(new String[]{"限流", limiter == null ? "关闭" : limiter.name() + " @ " + limiter.permitsPerSecond() + "/s"});
        rows.add(new String[]{"存储", store.name()});
        rows.add(new String[]{"消费者", String.valueOf(workers.length)});
        rows.add(new String[]{"模式", async ? "异步" : "同步"});
        return rows;
    }
}
