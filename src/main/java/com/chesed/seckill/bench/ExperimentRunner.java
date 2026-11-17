package com.chesed.seckill.bench;

import com.chesed.seckill.dedup.BloomDedup;
import com.chesed.seckill.dedup.Deduplicator;
import com.chesed.seckill.dedup.MapDedup;
import com.chesed.seckill.metrics.Metrics;
import com.chesed.seckill.order.SeckillEngine;
import com.chesed.seckill.persistence.MemoryOrderStore;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

/**
 * 实验主程序。用法：
 *
 * <pre>
 *   java -cp seckill.jar com.chesed.seckill.bench.ExperimentRunner &lt;组名|all&gt; &lt;输出目录&gt;
 * </pre>
 *
 * <p>每组实验独立建引擎、独立跑，跑完 close 掉，避免上一组的状态影响下一组。
 * 每轮正式跑之前有预热轮，结果丢弃——JIT 预热和页缓存冷启动会让第一轮明显偏慢，
 * 不预热的话第 1 轮永远是离群点。
 * </p>
 */
public final class ExperimentRunner {

    /** 固定控制变量，写报告时要写清楚。 */
    static final int INVENTORY = 1000;
    static final long REQUESTS = 200_000L;
    static final int WARMUP_ROUNDS = 3;
    static final int MEASURE_ROUNDS = 5;

    /**
     * 微基准（E1/E3）用的请求量。
     *
     * <p>为什么不用 {@link #REQUESTS}：队列一次入队出队只有几十纳秒，
     * 20 万次请求跑完不到 50ms，测量噪声（GC、起跑抖动）会把差异淹掉。
     * 放大到 200 万次，单轮跑到几百毫秒，队列实现的差异才浮得出来。
     * </p>
     */
    static final long MICRO_REQUESTS = 2_000_000L;

    /**
     * 微基准用的库存。给到和请求同量级，保证每个请求都能扣到库存、
     * 真的走一遍入队出队，而不是在闸机上就被拦掉。
     */
    static final int MICRO_INVENTORY = 4_000_000;

    /** 是否让被测引擎采样队列深度（面板要这个数，但 E1 里它是干扰项）。 */
    private static boolean SAMPLE_DEPTH = true;

    private final Path outFile;
    private final CsvResult csv;
    private final String prefix;

    public ExperimentRunner(Path outDir, String prefix) {
        this.prefix = prefix;
        this.outFile = outDir.resolve(prefix + ".csv");
        this.csv = new CsvResult(outFile, false);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: ExperimentRunner <e1|e2|e3|e4|e5|e6|e7|all> <输出目录>");
            System.exit(2);
        }
        String group = args[0];
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);

        long t0 = System.currentTimeMillis();
        var runner = new ExperimentRunner(dir, "results-" + group);
        long n = switch (group) {
            case "e1" -> runner.runE1();
            case "e2" -> runner.runE2();
            case "e3" -> runner.runE3();
            case "e4" -> runner.runE4();
            case "e5" -> runner.runE5();
            case "e6" -> runner.runE6();
            case "e7" -> runner.runE7();
            case "all" -> runner.runE1() + runner.runE2() + runner.runE3()
                    + runner.runE4() + runner.runE5() + runner.runE6() + runner.runE7();
            default -> {
                System.err.println("未知实验组: " + group);
                yield -1L;
            }
        };
        if (n < 0) {
            System.exit(2);
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.printf(Locale.ROOT, "%n全部完成：%d 个配置，用时 %.1f 分钟%n结果: %s%n",
                n, ms / 60000.0, runner.outFile);
    }

    // ================= E1 队列实现对比（微基准） =================

    /**
     * 只测队列本身。
     *
     * <p>关键设计：<b>库存给足、落库用空实现</b>。
     * 第一版直接沿用秒杀场景的 inventory=1000，结果 20 万请求里只有 1000 条能进队列，
     * 其余 19.9 万条在 CAS 扣库存那一步就被拒了，压根没碰到队列。
     * 这样测出来的 tps 是「拒绝吞吐」——不同队列之间没有统计意义上的差别，
     * 实验等于白跑。
     * </p>
     *
     * <p>微基准要回答的是「哪个队列在 32 个消费者争抢时更快」，
     * 所以让每个请求都真的走一遍 offer/poll。
     * </p>
     */
    private long runE1() throws Exception {
        System.out.println("== E1 队列实现对比（微基准：库存不设限，落库为空实现）==");
        long n = 0;
        // E1 跑两遍，唯一差别是关不关队列深度采样。
        //
        // 一开始只跑一遍（开着采样），结果很怪：LinkedTransferQueue 在纯队列
        // 微基准里是最快的（12.9M 元素/秒），装进引擎里 order_tps 反而是最低的
        // （34 万，比 ArrayBlockingQueue 慢 4 倍）。查下来是 size() 的问题：
        // 引擎每成功入队一次就调一次 queue.size() 更新面板，而
        // LinkedTransferQueue.size() 是加锁遍历整条链表——LTQ 又无界，
        // 这轮跑完队列里积压了两百万个节点，等于每入队一次走一遍两百万节点的
        // 链表。测出来的根本不是"谁入队快"，是"谁的 size() 便宜"。
        // 所以两遍都跑，把污染量本身也当成一个结论写进报告。
        for (boolean sample : new boolean[]{true, false}) {
            String tag = sample ? "-采样" : "-无采样";
            for (int workers : new int[]{4, 16, 32}) {
                for (String q : new String[]{"ABQ", "LBQ", "LTQ", "MpscRing"}) {
                    String cfg = q + "-w" + workers + tag;
                    System.out.print("  " + cfg + " ... ");
                    Case c = new Case("E1-queue", cfg, q, "CAS", workers, 256, 8192,
                            "none", "none", MICRO_INVENTORY, MICRO_REQUESTS, 0.0);
                    SAMPLE_DEPTH = sample;
                    runMatrix(c, () -> new SeckillEngine.Builder()
                            .stock(new CasStock(MICRO_INVENTORY))
                            .queue(buildQueue(q, 8192))
                            .store(new NullOrderStore())
                            .workers(workers));
                    n++;
                    System.out.println("ok");
                }
            }
        }
        SAMPLE_DEPTH = true;
        return n;
    }

    // ================= E2 库存扣减策略对比 =================

    private long runE2() throws Exception {
        System.out.println("== E2 库存扣减策略对比 ==");
        long n = 0;
        for (int vusers : new int[]{1, 16, 64, 256, 1024}) {
            for (String s : new String[]{"Sync", "Lock", "Stamped", "CAS", "Adder"}) {
                String cfg = s + "-v" + vusers;
                System.out.print("  " + cfg + " ... ");
                Case c = new Case("E2-stock", cfg, "ABQ", s, 16, vusers, 8192,
                        "none", "none", INVENTORY, REQUESTS, 0.0);
                // 注意：库存实例必须在工厂内部 new。工厂会被调用 8 次（3 预热 + 5 实测），
                // 若在循环外 new 一个复用，第二轮开始时库存已经是 0，success 全是 0。
                runMatrix(c, () -> new SeckillEngine.Builder()
                        .stock(buildStock(s, INVENTORY))
                        .queue(buildQueue("ABQ", 8192))
                        .store(new NullOrderStore())
                        .workers(16));
                n++;
                System.out.println("ok");
            }
        }
        // 反例：LongAdder 不做原子判断，必然超卖。报告里作为负面对照，证明"只加原子计数器"不够。
        System.out.print("  AdderUnsafe-v256 ... ");
        Case unsafe = new Case("E2-stock", "AdderUnsafe-v256", "ABQ", "AdderUnsafe",
                16, 256, 8192, "none", "none", INVENTORY, REQUESTS, 0.0);
        runMatrix(unsafe, () -> new SeckillEngine.Builder()
                .stock(new AdderStock(INVENTORY, true))
                .queue(buildQueue("ABQ", 8192))
                .store(new NullOrderStore())
                .workers(16));
        System.out.println("ok（预期一致性失败）");
        return n + 1;
    }

    // ================= E3 线程池参数（微基准） =================

    private long runE3() throws Exception {
        System.out.println("== E3 消费者线程数与队列容量（微基准）==");
        long n = 0;
        for (int workers : new int[]{4, 8, 16, 24, 32, 48}) {
            for (int cap : new int[]{256, 1024, 4096, 16384}) {
                String cfg = "w" + workers + "-c" + cap;
                System.out.print("  " + cfg + " ... ");
                Case c = new Case("E3-pool", cfg, "ABQ", "CAS", workers, 256, cap,
                        "none", "none", MICRO_INVENTORY, MICRO_REQUESTS, 0.0);
                runMatrix(c, () -> new SeckillEngine.Builder()
                        .stock(new CasStock(MICRO_INVENTORY))
                        .queue(buildQueue("ABQ", cap))
                        .store(new NullOrderStore())
                        .workers(workers));
                n++;
                System.out.println("ok");
            }
        }
        return n;
    }

    // ================= E4 限流 =================

    private long runE4() throws Exception {
        System.out.println("== E4 限流策略 ==");
        long n = 0;
        for (int qps : new int[]{10_000, 50_000, 100_000}) {
            for (String r : new String[]{"none", "token", "sliding"}) {
                String cfg = r + "-qps" + qps;
                System.out.print("  " + cfg + " ... ");
                Case c = new Case("E4-ratelimit", cfg, "ABQ", "CAS", 16, 256, 8192,
                        r, "none", INVENTORY, REQUESTS, 0.0);
                // 限流器同样必须每轮新建：令牌桶跑完一轮令牌已经耗光，
                // 复用会让第二轮全部被限流，测出来的只是"限流器空桶时的拒绝速率"。
                runMatrix(c, () -> new SeckillEngine.Builder()
                        .stock(new CasStock(INVENTORY))
                        .queue(buildQueue("ABQ", 8192))
                        .store(new NullOrderStore())
                        .limiter(buildLimiter(r, qps))
                        .workers(16));
                n++;
                System.out.println("ok");
            }
        }
        return n;
    }

    // ================= E5 去重 =================

    private long runE5() throws Exception {
        System.out.println("== E5 去重策略 ==");
        long n = 0;
        for (int dupPct : new int[]{0, 30, 90}) {
            for (String d : new String[]{"none", "map", "bloom"}) {
                String cfg = d + "-dup" + dupPct;
                System.out.print("  " + cfg + " ... ");
                Case c = new Case("E5-dedup", cfg, "ABQ", "CAS", 16, 256, 8192,
                        "none", d, INVENTORY, REQUESTS, dupPct / 100.0);
                // 去重器同理：跑完一轮后 requestId 全在表里，
                // 复用的话第二轮每个请求都会被判重复，测不出真实去重开销。
                runMatrix(c, () -> new SeckillEngine.Builder()
                        .stock(new CasStock(INVENTORY))
                        .queue(buildQueue("ABQ", 8192))
                        .store(new NullOrderStore())
                        .dedup(buildDedup(d))
                        .workers(16));
                n++;
                System.out.println("ok");
            }
        }
        return n;
    }

    // ================= E6 正确性 =================

    private long runE6() throws Exception {
        System.out.println("== E6 正确性验证 ==");
        long n = 0;

        // 6.1 超时未支付回补：100 件库存卖空，全部超时关闭后应该能重新卖出 100 件
        System.out.print("  expire-restore ... ");
        {
            int stock = 100;
            SeckillEngine engine = new SeckillEngine.Builder()
                    .stock(new CasStock(stock))
                    .queue(buildQueue("ABQ", 8192))
                    .store(new MemoryOrderStore())
                    .workers(8)
                    .ttlMillis(50L)
                    .build();
            new LoadHarness(engine).run(1000, 32, 0.0);
            Thread.sleep(300);
            int closed = engine.sweepExpired();
            int remainingAfter = engine.stock().remaining();
            Case c = new Case("E6-correctness", "expire-restore", "ABQ", "CAS",
                    8, 32, 8192, "none", "none", stock, 1000, 0.0);
            CsvResult.Row row = baseRow(c, false);
            row.totalRequests = 1000;
            row.success = engine.metrics().success();
            row.failedSoldout = engine.metrics().soldOut();
            row.oversold = 0;
            row.duplicateOrders = 0;
            row.consistencyOk = closed == stock && remainingAfter == stock;
            csv.write(row);
            engine.close();
            System.out.println("关闭 " + closed + " 单，剩余库存回补至 " + remainingAfter
                    + (row.consistencyOk ? " ✓" : " ✗"));
            n++;
        }

        // 6.2 落库失败：订单进死信，库存必须回补，否则这件货永远卖不出去
        System.out.print("  store-failure-deadletter ... ");
        {
            int stock = 50;
            SeckillEngine engine = new SeckillEngine.Builder()
                    .stock(new CasStock(stock))
                    .queue(buildQueue("ABQ", 8192))
                    .store(new FailingStore())
                    .workers(4)
                    .build();
            new LoadHarness(engine).run(200, 16, 0.0);
            Metrics m = engine.metrics();
            Case c = new Case("E6-correctness", "store-failure", "ABQ", "CAS",
                    4, 16, 8192, "none", "none", stock, 200, 0.0);
            CsvResult.Row row = baseRow(c, false);
            row.totalRequests = 200;
            row.success = m.success();
            row.queuePeakDepth = m.queuePeakDepth();
            row.oversold = 0;
            row.duplicateOrders = 0;
            row.consistencyOk = m.deadLetter() == stock && engine.stock().remaining() == stock;
            csv.write(row);
            engine.close();
            System.out.println("死信 " + m.deadLetter() + " 条，剩余库存回补至 "
                    + engine.stock().remaining() + (row.consistencyOk ? " ✓" : " ✗"));
            n++;
        }

        // 6.3 恒等式：各状态计数之和必须等于总请求数，且扣减数 = 初始 - 剩余
        System.out.print("  accounting-identity ... ");
        {
            SeckillEngine engine = new SeckillEngine.Builder()
                    .stock(new CasStock(INVENTORY))
                    .queue(buildQueue("ABQ", 8192))
                    .store(new MemoryOrderStore())
                    .workers(16)
                    .build();
            LoadHarness.RunReport rep = new LoadHarness(engine).run(REQUESTS, 256, 0.0);
            Metrics m = engine.metrics();
            long sum = m.success() + m.soldOut() + m.duplicate()
                    + m.rateLimited() + m.rejected() + m.deadLetter();
            boolean eq = sum == REQUESTS
                    && engine.stock().deducted() == INVENTORY - engine.stock().remaining();
            Case c = new Case("E6-correctness", "accounting-identity", "ABQ", "CAS",
                    16, 256, 8192, "none", "none", INVENTORY, REQUESTS, 0.0);
            CsvResult.Row row = baseRow(c, false);
            row.totalRequests = REQUESTS;
            row.success = m.success();
            row.failedSoldout = m.soldOut();
            row.tps = rep.tps(REQUESTS);
            row.orderTps = rep.durationNanos() <= 0 ? 0
                    : m.success() * 1_000_000_000.0 / rep.durationNanos();
            row.soldoutMs = rep.soldOutMillis();
            row.oversold = 0;
            row.duplicateOrders = 0;
            row.consistencyOk = eq;
            row.durationMs = rep.durationMillis();
            csv.write(row);
            engine.close();
            System.out.println("状态和 " + sum + " / " + REQUESTS + (eq ? " ✓" : " ✗"));
            n++;
        }
        return n;
    }

    // ================= E7 极端流量 =================

    private long runE7() throws Exception {
        System.out.println("== E7 极端流量 ==");
        long n = 0;
        long[] totals = {REQUESTS, REQUESTS * 5, REQUESTS * 20, REQUESTS * 100};
        String[] names = {"1x", "5x", "20x", "100x"};
        for (int i = 0; i < totals.length; i++) {
            long total = totals[i];
            String cfg = names[i] + "-" + total;
            System.out.print("  " + cfg + " ... ");
            // E7 用 ABQ + 32 workers 作基准组合，专门看"请求量本身"如何压垮系统。
            // 落库改回 MemoryOrderStore（E7 关心的是端到端，不是队列微差异）。
            Case c = new Case("E7-extreme", cfg, "ABQ", "CAS", 32, 512, 16384,
                    "none", "none", INVENTORY, total, 0.0);
            runMatrix(c, () -> new SeckillEngine.Builder()
                    .stock(new CasStock(INVENTORY))
                    .queue(buildQueue("ABQ", 16384))
                    .store(new MemoryOrderStore())
                    .workers(32));
            n++;
            System.out.println("ok");
        }
        return n;
    }

    // ================= 运行骨架 =================

    /** 返回一个新建的、还没跑过的引擎 Builder。 */
    @FunctionalInterface
    interface EngineFactory {
        SeckillEngine.Builder get();
    }

    /**
     * 一个待测配置的全部固定参数。
     *
     * <p>头一版是十几个位置参数直接传给 runMatrix，加一个字段就要改四处调用，
     * 还很容易把 workers 和 vusers 传错位。换成 record 之后调用处用命名参数，
     * 加字段也只改这里。
     * </p>
     *
     * @param exp       实验组名，写进 CSV 的 experiment 列
     * @param cfg       配置名，写进 config_name 列
     * @param queue     队列类型
     * @param stock     库存策略类型
     * @param workers   消费者线程数
     * @param vusers    并发压测线程数
     * @param cap       队列容量
     * @param limiter   限流策略
     * @param dedup     去重策略
     * @param inventory 本组的初始库存（微基准用大库存，秒杀实验用小库存）
     * @param total     总请求数
     * @param dupRate   重复请求比例
     */
    record Case(String exp, String cfg, String queue, String stock, int workers,
                int vusers, int cap, String limiter, String dedup, int inventory,
                long total, double dupRate) {

        Case withCfg(String newCfg) {
            return new Case(exp, newCfg, queue, stock, workers, vusers, cap,
                    limiter, dedup, inventory, total, dupRate);
        }

        Case withLoad(long newTotal, int newVusers) {
            return new Case(exp, cfg, queue, stock, workers, newVusers, cap,
                    limiter, dedup, inventory, newTotal, dupRate);
        }
    }

    /**
     * 跑一个配置：先预热 3 轮（结果丢弃），再正式测 5 轮取中位数。
     *
     * <p>取中位数而不是平均值：偶尔会遇到 GC 停顿或系统调度抖动，
     * 平均值会被一次离群值拉偏，中位数对它免疫。
     * </p>
     */
    private void runMatrix(Case c, EngineFactory factory) throws Exception {
        // 预热：JIT 编译完热点代码，页缓存也热起来
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            executeOnce(c.withCfg("warmup").withLoad(Math.min(c.total(), 20_000L),
                    Math.min(c.vusers(), 64)), factory);
        }

        List<CsvResult.Row> rounds = new ArrayList<>(MEASURE_ROUNDS);
        boolean allConsistent = true;
        long oversoldMax = 0;
        long dupOrdersMax = 0;
        for (int i = 0; i < MEASURE_ROUNDS; i++) {
            CsvResult.Row r = executeOnce(c, factory);
            rounds.add(r);
            allConsistent &= r.consistencyOk;
            oversoldMax = Math.max(oversoldMax, r.oversold);
            dupOrdersMax = Math.max(dupOrdersMax, r.duplicateOrders);
        }

        // 取「吞吐处于中位数」的那一整轮，而不是每个字段各取中位数。
        //
        // 第一版是逐字段取中位数，结果 E4 出现 success 1000 + 被限流 198901
        // + 售罄 344 = 200245，比总请求数还多。原因是三个数字来自不同的轮次，
        // 拼起来就不是任何一次真实运行的状态了。
        // 现在按 tps 排序取中间那一轮，所有字段都出自同一次运行，
        // 各个计数之间的关系（加起来等于总数）天然成立。
        rounds.sort((a, b) -> Double.compare(a.tps, b.tps));
        CsvResult.Row picked = rounds.get(rounds.size() / 2);

        CsvResult.Row merged = baseRow(c, true);
        merged.totalRequests = c.total();
        merged.success = picked.success;
        merged.failedSoldout = picked.failedSoldout;
        merged.failedRejected = picked.failedRejected;
        merged.failedDup = picked.failedDup;
        merged.failedRatelimit = picked.failedRatelimit;
        merged.tps = picked.tps;
        merged.orderTps = picked.orderTps;
        merged.soldoutMs = picked.soldoutMs;
        merged.p50 = picked.p50;
        merged.p95 = picked.p95;
        merged.p99 = picked.p99;
        merged.p999 = picked.p999;
        merged.max = picked.max;
        merged.mean = picked.mean;
        // 这一行是漏的：合并字段是一列一列抄过来的，加 order_p99 那列时忘了在这里抄，
        // 于是 CSV 里 87 行的 order_p99 全是默认值 0，看着像"成功单根本没有延迟"。
        // 直方图本身没问题（单独喂样本测过），纯粹是抄漏。
        merged.orderP99 = picked.orderP99;
        merged.queuePeakDepth = picked.queuePeakDepth;
        // 这两个是「只要有一轮出错就是错」，所以取所有轮次的最大值，不跟中位数轮走
        merged.oversold = oversoldMax;
        merged.duplicateOrders = dupOrdersMax;
        merged.consistencyOk = allConsistent;
        merged.durationMs = picked.durationMs;
        csv.write(merged);
    }

    private CsvResult.Row executeOnce(Case c, EngineFactory factory) throws Exception {
        SeckillEngine engine = factory.get().build();
        engine.setSampleDepth(SAMPLE_DEPTH);
        try {
            LoadHarness.RunReport rep = new LoadHarness(engine).run(c.total(), c.vusers(),
                    c.dupRate());
            Metrics m = engine.metrics();
            long oversold = Math.max(0, engine.stock().deducted() - c.inventory());
            // 关掉去重时，同一条 requestId 会被重复下单。第一条算正常，
            // 之后每一条都是重复单，所以减 1。开了去重则一条都进不来，记 0。
            boolean dedupOn = !"none".equals(c.dedup());
            long dupOrders = dedupOn ? 0 : Math.max(0, rep.dupSuccess() - 1);

            CsvResult.Row row = baseRow(c, true);
            row.totalRequests = c.total();
            row.success = m.success();
            row.failedSoldout = m.soldOut();
            row.failedRejected = m.rejected();
            row.failedDup = m.duplicate();
            row.failedRatelimit = m.rateLimited();
            row.tps = rep.tps(c.total());
            // 有效成交吞吐：只数真的卖出去的货。库存远小于请求量时，
            // 用总请求数算出来的 tps 会把"被秒拒"也算成吞吐，数字飙到几百万毫无意义。
            row.orderTps = rep.durationNanos() <= 0 ? 0
                    : m.success() * 1_000_000_000.0 / rep.durationNanos();
            row.soldoutMs = rep.soldOutMillis();
            // 分位数取「全部请求」的直方图：包含被限流/去重/售罄拒绝的那些。
            // 早期版本取的是成功单的直方图，结果库存只有 1000 件时，
            // 分位数描述的是"最前面那批请求"，后面几十万条快速失败根本没统计进去，
            // 于是 100 倍流量下 p50 反而更低——看着像系统变快了，其实它早就不干活了。
            row.p50 = m.requestLatency().percentileMillis(0.50);
            row.p95 = m.requestLatency().percentileMillis(0.95);
            row.p99 = m.requestLatency().percentileMillis(0.99);
            row.p999 = m.requestLatency().percentileMillis(0.999);
            row.max = m.requestLatency().maxMillis();
            row.mean = m.requestLatency().meanMillis();
            // 成功单的端到端 P99 单独存一列：这是"真买到货的人"的实际等待时间
            row.orderP99 = m.e2e().percentileMillis(0.99);
            row.queuePeakDepth = m.queuePeakDepth();
            row.oversold = oversold;
            row.duplicateOrders = dupOrders;
            // 一致性判定：不超卖、不重复下单、成交数不超过库存
            row.consistencyOk = oversold == 0 && dupOrders == 0 && m.success() <= c.inventory();
            row.durationMs = rep.durationMillis();
            return row;
        } finally {
            engine.close();
        }
    }

    private CsvResult.Row baseRow(Case c, boolean persist) {
        CsvResult.Row r = new CsvResult.Row();
        r.runId = prefix + "-" + System.nanoTime();
        r.experiment = c.exp();
        r.configName = c.cfg();
        r.queueType = c.queue();
        r.lockType = c.stock();
        r.workers = c.workers();
        r.vusers = c.vusers();
        r.queueCapacity = c.cap();
        r.rateLimit = c.limiter();
        r.dedupType = c.dedup();
        r.persistEnabled = persist;
        r.inventory = c.inventory();
        r.oversold = 0;
        r.duplicateOrders = 0;
        r.consistencyOk = true;
        return r;
    }

    // ================= 构造工具 =================

    private static TaskQueue<SeckillEngine.OrderTask> buildQueue(String kind, int cap) {
        int c = Math.max(64, cap);
        return switch (kind) {
            case "LBQ" -> new JdkQueueAdapter<>("LinkedBlockingQueue",
                    new LinkedBlockingQueue<>(c), c);
            case "LTQ" -> new JdkQueueAdapter<>("LinkedTransferQueue",
                    new LinkedTransferQueue<>(), Integer.MAX_VALUE);
            case "MpscRing" -> new MpscRingQueue<>(Integer.highestOneBit(c));
            default -> new JdkQueueAdapter<>("ArrayBlockingQueue",
                    new ArrayBlockingQueue<>(c), c);
        };
    }

    private static Stock buildStock(String kind, int inventory) {
        return switch (kind) {
            case "Sync" -> new SyncStock(inventory);
            case "Lock" -> new LockStock(inventory);
            case "Stamped" -> new StampedStock(inventory);
            case "Adder" -> new AdderStock(inventory, false);
            default -> new CasStock(inventory);
        };
    }

    private static Deduplicator buildDedup(String kind) {
        return switch (kind) {
            case "map" -> new MapDedup();
            case "bloom" -> new BloomDedup(1 << 20, 4);
            default -> null;
        };
    }

    private static RateLimiter buildLimiter(String kind, int qps) {
        return switch (kind) {
            case "token" -> new TokenBucketLimiter(qps, Math.max(1, qps / 10));
            case "sliding" -> new SlidingWindowLimiter(qps, 10);
            default -> null;
        };
    }

    private static double median(List<Double> xs) {
        if (xs.isEmpty()) {
            return 0;
        }
        List<Double> copy = new ArrayList<>(xs);
        copy.sort(Double::compareTo);
        int n = copy.size();
        return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2;
    }

    private static double medianCol(List<double[]> rows, int col) {
        List<Double> xs = new ArrayList<>(rows.size());
        for (double[] r : rows) {
            xs.add(r[col]);
        }
        return median(xs);
    }

    /** 总是落库失败的 Store，用来验证死信与库存回补路径。 */
    static final class FailingStore implements com.chesed.seckill.persistence.OrderStore {

        private final MemoryOrderStore delegate = new MemoryOrderStore();

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public void save(com.chesed.seckill.domain.Order order) {
            throw new IllegalStateException("模拟数据库写入失败");
        }

        @Override
        public void saveBatch(java.util.List<com.chesed.seckill.domain.Order> orders) {
            throw new IllegalStateException("模拟数据库写入失败");
        }

        @Override
        public com.chesed.seckill.domain.Order find(long orderId) {
            return delegate.find(orderId);
        }

        @Override
        public java.util.List<com.chesed.seckill.domain.Order> findByUser(long userId) {
            return delegate.findByUser(userId);
        }

        @Override
        public long countByStatus(com.chesed.seckill.domain.Order.Status status) {
            return delegate.countByStatus(status);
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public java.util.List<com.chesed.seckill.domain.Order> recent(int limit) {
            return delegate.recent(limit);
        }

        @Override
        public long maxOrderId() {
            return delegate.maxOrderId();
        }

        @Override
        public int closeAllCreated() {
            return delegate.closeAllCreated();
        }

        @Override
        public Reconciliation reconcile(int initialStock) {
            return delegate.reconcile(initialStock);
        }

        @Override
        public java.util.List<com.chesed.seckill.domain.Order> closeExpired(long ttlMillis) {
            return delegate.closeExpired(ttlMillis);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
