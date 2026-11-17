package com.chesed.seckill.stock;

import java.util.concurrent.atomic.LongAdder;

/**
 * 反例对照：LongAdder 只擅长"只增不减"的统计，做条件扣减必须外层加锁，
 * 加了锁之后反而比 ReentrantLock 慢（多了一层分片间接寻址）。
 * 如果硬不加锁直接 sum-then-add，就会超卖。
 * 这个类故意保留"错误实现"分支，用于实验里量化超卖数量。
 */
public final class AdderStock implements Stock {

    private final int capacity;
    private final LongAdder sold = new LongAdder();
    private final Object guard = new Object();
    private final boolean unsafe;

    public AdderStock(int capacity, boolean unsafe) {
        this.capacity = capacity;
        this.unsafe = unsafe;
    }

    @Override
    public String name() {
        return unsafe ? "LongAdder(unsafe)" : "LongAdder+lock";
    }

    @Override
    public boolean tryDeduct() {
        if (unsafe) {
            // 错误示范：读和写之间没有原子性，高并发下必然超卖
            if (sold.sum() >= capacity) {
                return false;
            }
            sold.increment();
            return true;
        }
        // 加了锁的版本正确，但因为锁外还有一次分片寻址，比纯 ReentrantLock 更慢
        synchronized (guard) {
            if (sold.sum() >= capacity) {
                return false;
            }
            sold.increment();
            return true;
        }
    }

    @Override
    public void restore() {
        if (unsafe) {
            sold.decrement();
            return;
        }
        synchronized (guard) {
            if (sold.sum() > 0) {
                sold.decrement();
            }
        }
    }

    @Override
    public int remaining() {
        return (int) Math.max(0, capacity - sold.sum());
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public long deducted() {
        return sold.sum();
    }

    /** 只在 unsafe 模式下可能 > capacity，实验里会读这个值证明超卖。 */
    public long rawSold() {
        return sold.sum();
    }
}
