package com.chesed.seckill.dedup;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 布隆过滤器去重。内存固定，代价是有假阳性——会把没来过的请求误判成重复。
 * 秒杀场景可以接受少量误杀（用户重试即可），换来内存可控。
 */
public final class BloomDedup implements Deduplicator {

    private final AtomicLongArray bits;
    private final int bitCount;
    private final int hashCount;
    private final AtomicLong count = new AtomicLong();

    public BloomDedup(int bitCount, int hashCount) {
        this.bitCount = bitCount;
        this.hashCount = hashCount;
        this.bits = new AtomicLongArray((bitCount + 63) >>> 6);
    }

    @Override
    public String name() {
        return "BloomFilter";
    }

    @Override
    public boolean firstSeen(long userId, String requestId) {
        long h = mix(userId * 0x9E3779B97F4A7C15L ^ requestId.hashCode());
        boolean allSet = true;
        for (int i = 0; i < hashCount; i++) {
            // 用不同的位移派生出多个位置，比重新算哈希快
            int pos = (int) Math.floorMod(h >>> (i * 8), (long) bitCount);
            int word = pos >>> 6;
            long flag = 1L << (pos & 63);
            long old = bits.get(word);
            if ((old & flag) == 0) {
                allSet = false;
                // 立刻置位，保证同一次调用里完成的写入对后续可见
                long cur = old;
                while ((cur & flag) == 0) {
                    if (bits.compareAndSet(word, cur, cur | flag)) {
                        break;
                    }
                    cur = bits.get(word);
                }
            }
        }
        if (allSet) {
            return false;
        }
        count.incrementAndGet();
        return true;
    }

    @Override
    public long size() {
        return count.get();
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
