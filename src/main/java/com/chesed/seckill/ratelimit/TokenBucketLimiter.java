package com.chesed.seckill.ratelimit;

import java.util.concurrent.locks.LockSupport;

/**
 * 令牌桶。按时间流逝补充令牌，允许短时突发（桶容量决定突发上限）。
 * 用 AtomicLong 存"当前令牌数 * 1000"避免浮点误差，补充时按纳秒折算。
 */
public final class TokenBucketLimiter implements RateLimiter {

    private final int rate;
    private final long capacity;
    private final java.util.concurrent.atomic.AtomicLong tokens;
    private final java.util.concurrent.atomic.AtomicLong lastRefillNanos;
    private final long nanoPerToken;

    public TokenBucketLimiter(int permitsPerSecond, int burst) {
        this.rate = permitsPerSecond;
        this.capacity = (long) burst * 1000L;
        this.tokens = new java.util.concurrent.atomic.AtomicLong(capacity);
        this.lastRefillNanos = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        this.nanoPerToken = 1_000_000_000L / permitsPerSecond;
    }

    @Override
    public String name() {
        return "TokenBucket";
    }

    @Override
    public boolean tryAcquire() {
        refill();
        for (; ; ) {
            long cur = tokens.get();
            if (cur < 1000L) {
                return false;
            }
            if (tokens.compareAndSet(cur, cur - 1000L)) {
                return true;
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsed = now - last;
        if (elapsed < nanoPerToken) {
            return;
        }
        // 只让一个线程做补充，抢输的直接走人，不用等
        if (!lastRefillNanos.compareAndSet(last, now)) {
            return;
        }
        long add = elapsed / nanoPerToken * 1000L;
        for (; ; ) {
            long cur = tokens.get();
            long next = Math.min(capacity, cur + add);
            if (tokens.compareAndSet(cur, next)) {
                return;
            }
        }
    }

    @Override
    public int permitsPerSecond() {
        return rate;
    }
}
