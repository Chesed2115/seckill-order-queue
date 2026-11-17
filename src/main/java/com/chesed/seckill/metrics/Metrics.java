package com.chesed.seckill.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 全局指标收集。所有计数都用 LongAdder，避免统计本身成为瓶颈。 */
public final class Metrics {

    private final LongAdder total = new LongAdder();
    private final LongAdder success = new LongAdder();
    private final LongAdder soldOut = new LongAdder();
    private final LongAdder duplicate = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder deadLetter = new LongAdder();

    private final LatencyHistogram e2eLatency = new LatencyHistogram();
    private final LatencyHistogram queueWaitLatency = new LatencyHistogram();

    /**
     * 全部请求的响应时间，包括被拒绝的那些。
     *
     * <p>为什么不能只用 {@link #e2eLatency}：那个直方图只记成功下单的请求。
     * 库存只有 1000 件的时候，整场压测的成功单就是最开始那一千个，
     * 剩下几十万条全走了「已售罄」的快速失败路径。于是 e2e 的分位数
     * 描述的是「最早那批请求有多快」，跟后来打进来的用户感受到的延迟无关。
     * 第一次跑极端流量实验时 p50 在 100 倍负载下反而降到 2.8ms，
     * 就是因为这个——不是系统变快了，是它早就不做正事了。
     * </p>
     */
    private final LatencyHistogram requestLatency = new LatencyHistogram();

    private final AtomicLong queuePeakDepth = new AtomicLong();
    private final AtomicLong queueCurrentDepth = new AtomicLong();

    private volatile long startNanos = System.nanoTime();

    // 瞬时速率采样用的上一拍数据，只有面板线程会写，不用加锁
    private long lastSampleNanos = startNanos;
    private long lastSampleTotal = 0;

    public void recordRequest() {
        total.increment();
    }

    public void recordSuccess() {
        success.increment();
    }

    public void recordSoldOut() {
        soldOut.increment();
    }

    public void recordDuplicate() {
        duplicate.increment();
    }

    public void recordRateLimited() {
        rateLimited.increment();
    }

    public void recordRejected() {
        rejected.increment();
    }

    public void recordDeadLetter() {
        deadLetter.increment();
    }

    public void recordLatency(long nanos) {
        e2eLatency.record(nanos);
    }

    /** 记录一次请求的响应时间，不论成功失败。 */
    public void recordRequestLatency(long nanos) {
        requestLatency.record(nanos);
    }

    public void recordQueueWait(long nanos) {
        queueWaitLatency.record(nanos);
    }

    /** 每次更新队列深度时调用，内部维护峰值。 */
    public void updateQueueDepth(int depth) {
        queueCurrentDepth.set(depth);
        for (; ; ) {
            long peak = queuePeakDepth.get();
            if (depth <= peak) {
                return;
            }
            if (queuePeakDepth.compareAndSet(peak, depth)) {
                return;
            }
        }
    }

    public void reset() {
        total.reset();
        success.reset();
        soldOut.reset();
        duplicate.reset();
        rateLimited.reset();
        rejected.reset();
        deadLetter.reset();
        e2eLatency.reset();
        queueWaitLatency.reset();
        requestLatency.reset();
        queuePeakDepth.set(0);
        queueCurrentDepth.set(0);
        startNanos = System.nanoTime();
    }

    public long total() {
        return total.sum();
    }

    public long success() {
        return success.sum();
    }

    public long soldOut() {
        return soldOut.sum();
    }

    public long duplicate() {
        return duplicate.sum();
    }

    public long rateLimited() {
        return rateLimited.sum();
    }

    public long rejected() {
        return rejected.sum();
    }

    public long deadLetter() {
        return deadLetter.sum();
    }

    public long queuePeakDepth() {
        return queuePeakDepth.get();
    }

    public long queueCurrentDepth() {
        return queueCurrentDepth.get();
    }

    public LatencyHistogram e2e() {
        return e2eLatency;
    }

    public LatencyHistogram queueWait() {
        return queueWaitLatency;
    }

    /** 全部请求（含被拒绝的）的响应时间分布。 */
    public LatencyHistogram requestLatency() {
        return requestLatency;
    }

    /** 从 reset 或启动到现在的平均吞吐（次/秒）。 */
    public double throughput() {
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed <= 0) {
            return 0;
        }
        return total.sum() * 1_000_000_000.0 / elapsed;
    }

    /**
     * 最近一拍到现在之间的瞬时吞吐。压测结束后 avg 吞吐会被前期空闲时间拉低，
     * 面板上看到的数字应该反映"打的时候有多快"，所以用瞬时值。
     */
    public synchronized double instantThroughput() {
        long now = System.nanoTime();
        long cur = total.sum();
        long dt = now - lastSampleNanos;
        long dn = cur - lastSampleTotal;
        lastSampleNanos = now;
        lastSampleTotal = cur;
        if (dt <= 0) {
            return 0;
        }
        return dn * 1_000_000_000.0 / dt;
    }
}
