package com.chesed.seckill.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 延迟直方图。用固定分桶（对数刻度）而不是存全部样本，
 * 这样内存恒定、不随请求数增长，代价是分位数精度受桶宽限制。
 * 秒杀要跑几十万次请求，存全部样本会先 OOM。
 *
 * <p>线程安全说明：第一版用的是 {@code long[] counts} 裸自增，压测时被两百多个线程同时写，
 * 出现了丢样本和分位数明显偏小的问题（p50 报出 0.007ms 这种不可能的值）。
 * 现在每一格改成 {@link LongAdder}，counter 内部会按线程散列到不同的 cell，
 * 高并发下写冲突比 {@code AtomicLong} 小得多，读的时候 sum 一下即可。
 * 分位数只在采样时才读，所以读的代价高一点无所谓。
 */
public final class LatencyHistogram {

    /** 上界（微秒）。每个桶覆盖 [prev, this)。
     *  从 1us 一直排到约 10s，覆盖真实秒杀的全部可能延迟。
     *
     *  <p>为什么不是简单的 1,2,4,8…（每倍频一个桶）：第一版就是这么做的，
     *  结果 12ms 附近的桶宽达 8.19ms~16.38ms，四个不同流量下算出来的 p50
     *  全都是 12.2880ms 一模一样——不是延迟真的没变，是桶太粗，插值结果
     *  退化成桶中点，看上去像坏数据。现在改成每倍频 3 个桶（比值约 1.26），
     *  桶宽收到 ±13%，12ms 附近的分辨率约 1.5ms，够看趋势了。
     *  桶数从 25 增加到 73，内存代价可以忽略。 */
    private static final long[] BOUNDS_US = buildBounds();

    private static long[] buildBounds() {
        final double step = Math.pow(2.0, 1.0 / 3.0); // 每倍频 3 个桶
        final long maxUs = 16_777_216L;               // 约 16.7 秒
        java.util.List<Long> list = new java.util.ArrayList<>(80);
        long v = 1;
        while (v <= maxUs) {
            list.add(v);
            double next = v * step;
            long nv = Math.max(v + 1, Math.round(next));
            v = nv;
        }
        long[] arr = new long[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private final LongAdder[] counts = new LongAdder[BOUNDS_US.length + 1];
    private final LongAdder total = new LongAdder();
    private final LongAdder sumNanos = new LongAdder();
    private final AtomicLong maxNanos = new AtomicLong();

    public LatencyHistogram() {
        for (int i = 0; i < counts.length; i++) {
            counts[i] = new LongAdder();
        }
    }

    public void record(long nanos) {
        if (nanos < 0) {
            nanos = 0;
        }
        long us = nanos / 1_000L;
        int idx = Arrays.binarySearch(BOUNDS_US, us);
        if (idx < 0) {
            idx = -idx - 1;
        }
        // 超过最大上界的都归到最后一格
        if (idx >= counts.length) {
            idx = counts.length - 1;
        }
        counts[idx].increment();
        total.increment();
        sumNanos.add(nanos);
        maxNanos.accumulateAndGet(nanos, Math::max);
    }

    public long count() {
        return total.sum();
    }

    public double meanMillis() {
        long n = total.sum();
        return n == 0 ? 0 : (sumNanos.sum() / 1_000_000.0) / n;
    }

    public double maxMillis() {
        return maxNanos.get() / 1_000_000.0;
    }

    /** 计算分位数。桶内用线性插值估算，比直接取桶上界准。 */
    public double percentileMillis(double p) {
        long n = total.sum();
        if (n == 0) {
            return 0;
        }
        long target = (long) Math.ceil(n * p);
        long acc = 0;
        long prevBound = 0;
        for (int i = 0; i < counts.length; i++) {
            long c = counts[i].sum();
            acc += c;
            if (acc >= target) {
                long upper = i < BOUNDS_US.length ? BOUNDS_US[i] : BOUNDS_US[BOUNDS_US.length - 1] * 2;
                // 在该桶内按已累计比例插值
                long beforeInBucket = acc - c;
                double frac = c == 0 ? 1.0 : (target - beforeInBucket) / (double) c;
                double us = prevBound + (upper - prevBound) * frac;
                double ms = us / 1000.0;
                // 插值是按桶上界外推的，可能算出比实测最大值还大，这里夹住
                return Math.min(ms, maxMillis());
            }
            prevBound = i < BOUNDS_US.length ? BOUNDS_US[i] : prevBound;
        }
        return maxMillis();
    }

    public void reset() {
        for (LongAdder c : counts) {
            c.reset();
        }
        total.reset();
        sumNanos.reset();
        maxNanos.set(0);
    }
}
