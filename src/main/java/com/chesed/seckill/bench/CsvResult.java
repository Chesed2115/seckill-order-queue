package com.chesed.seckill.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 实验结果的 CSV 输出。每次运行追加一行，字段固定。
 * 用 synchronized 是因为多个实验组的写出线程可能并发，追加写必须串行。
 */
public final class CsvResult {

    /** 表头，顺序即写入顺序。改这里要同时改 toRow。 */
    public static final List<String> HEADER = List.of(
            "run_id", "experiment", "config_name", "queue_type", "lock_type",
            "workers", "vusers", "queue_capacity", "rate_limit", "dedup_type",
            "persist_enabled", "inventory", "total_requests", "success", "failed_soldout",
            "failed_rejected", "failed_dup", "failed_ratelimit",
            "tps", "order_tps", "soldout_ms",
            "p50_ms", "p95_ms", "p99_ms", "p999_ms", "max_ms", "mean_ms",
            "order_p99_ms",
            "queue_peak_depth", "oversold", "duplicate_orders", "consistency_ok",
            "duration_ms"
    );

    private final Path file;
    private final boolean append;
    /** 是否还没写过。第一版用「append=false 就写表头」判断，结果同一个文件被
     *  多个 CsvResult 打开时会重复写表头（E6 的 CSV 里出现了 3 行表头）。
     *  改成只认「本次实例是否已经写过」，才真正保证一个文件一份表头。 */
    private boolean firstWrite = true;

    public CsvResult(Path file, boolean append) {
        this.file = file;
        this.append = append;
    }

    /** 一次运行的完整结果。用 Builder 是因为字段太多，位置参数容易错位。 */
    public static final class Row {
        public String runId = "";
        public String experiment = "";
        public String configName = "";
        public String queueType = "";
        public String lockType = "";
        public int workers;
        public int vusers;
        public int queueCapacity;
        public String rateLimit = "none";
        public String dedupType = "none";
        public boolean persistEnabled;
        public int inventory;
        public long totalRequests;
        public long success;
        public long failedSoldout;
        public long failedRejected;
        public long failedDup;
        public long failedRatelimit;
        public double tps;
        /** 有效成交吞吐：只算真正落库成功的单，不受「早早就卖光了」的影响。 */
        public double orderTps;
        /** 库存被抢光用了多少毫秒，这是秒杀最关键的体验指标之一。 */
        public double soldoutMs;
        public double p50;
        public double p95;
        public double p99;
        public double p999;
        public double max;
        public double mean;
        /** 成功下单请求的端到端 P99（含落库），与上面「全部请求」的 P99 用途不同：
         *  这个衡量"下单成功的人等了多久"，上面那个衡量"所有人平均感受"。 */
        public double orderP99;
        public long queuePeakDepth;
        public long oversold;
        public long duplicateOrders;
        public boolean consistencyOk;
        public long durationMs;

        public String toRow() {
            return String.join(",",
                    runId, experiment, configName, queueType, lockType,
                    String.valueOf(workers), String.valueOf(vusers),
                    String.valueOf(queueCapacity), rateLimit, dedupType,
                    String.valueOf(persistEnabled), String.valueOf(inventory),
                    String.valueOf(totalRequests), String.valueOf(success),
                    String.valueOf(failedSoldout), String.valueOf(failedRejected),
                    String.valueOf(failedDup), String.valueOf(failedRatelimit),
                    f(tps), f(orderTps), f(soldoutMs),
                    f(p50), f(p95), f(p99), f(p999), f(max), f(mean),
                    f(orderP99),
                    String.valueOf(queuePeakDepth),
                    String.valueOf(oversold), String.valueOf(duplicateOrders),
                    String.valueOf(consistencyOk),
                    String.valueOf(durationMs));
        }

        private static String f(double v) {
            return String.format(java.util.Locale.ROOT, "%.4f", v);
        }
    }

    public synchronized void write(Row row) throws IOException {
        Files.createDirectories(file.getParent());
        // 只有「第一次写」并且文件本来就空/不存在时才写表头。
        // 这样同一文件多次打开也不会重复表头，追加到已有文件也不会插入表头。
        boolean needHeader = firstWrite && (!Files.exists(file) || isEmpty(file));
        firstWrite = false;
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (needHeader) {
                w.write(String.join(",", HEADER));
                w.newLine();
            }
            w.write(row.toRow());
            w.newLine();
        }
    }

    private static boolean isEmpty(Path p) {
        try {
            return Files.size(p) == 0;
        } catch (IOException e) {
            return true;
        }
    }
}
