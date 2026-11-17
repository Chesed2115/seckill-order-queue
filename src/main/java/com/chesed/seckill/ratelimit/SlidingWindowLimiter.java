package com.chesed.seckill.ratelimit;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 滑动窗口。把 1 秒切成 N 个桶，每个桶记该桶的通过数。
 * 相比固定窗口，它不会在窗口边界放出两倍流量；相比令牌桶，它能精确控制"任意 1 秒内总量"。
 */
public final class SlidingWindowLimiter implements RateLimiter {

    private final int rate;
    private final int buckets;
    private final long windowNanos;
    private final AtomicLongArray counters;
    private final AtomicLong lastBucket;

    public SlidingWindowLimiter(int permitsPerSecond, int buckets) {
        this.rate = permitsPerSecond;
        this.buckets = buckets;
        this.windowNanos = 1_000_000_000L;
        this.counters = new AtomicLongArray(buckets);
        this.lastBucket = new AtomicLong(Long.MIN_VALUE);
    }

    @Override
    public String name() {
        return "SlidingWindow";
    }

    @Override
    public boolean tryAcquire() {
        long nowBucket = System.nanoTime() / (windowNanos / buckets);
        advance(nowBucket);

        long total = 0;
        for (int i = 0; i < buckets; i++) {
            total += counters.get(i);
        }
        if (total >= rate) {
            return false;
        }
        int idx = (int) Math.floorMod(nowBucket, (long) buckets);
        counters.incrementAndGet(idx);
        return true;
    }

    /** 把过期的桶清零，保证统计的始终是最近一个完整窗口。 */
    private void advance(long nowBucket) {
        for (; ; ) {
            long last = lastBucket.get();
            if (last == Long.MIN_VALUE) {
                if (lastBucket.compareAndSet(last, nowBucket)) {
                    return;
                }
                continue;
            }
            long diff = nowBucket - last;
            if (diff <= 0) {
                return;
            }
            if (!lastBucket.compareAndSet(last, nowBucket)) {
                continue;
            }
            // 只清掉已经滑出窗口的桶
            long clearFrom = Math.max(0, diff - buckets + 1);
            for (long k = clearFrom; k <= diff; k++) {
                long b = last + k;
                int idx = (int) Math.floorMod(b, (long) buckets);
                counters.set(idx, 0);
            }
            return;
        }
    }

    @Override
    public int permitsPerSecond() {
        return rate;
    }
}
