package com.chesed.seckill.stock;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;

/**
 * StampedLock 乐观读。关键在于：99% 的请求在库存为 0 时应该快速失败，
 * 用乐观读先探一下能做到完全不阻塞。只有乐观读通过、需要真扣的时候才升级成写锁。
 */
public final class StampedStock implements Stock {

    private final int capacity;
    private final StampedLock lock = new StampedLock();
    private volatile int remain;
    private final LongAdder deducted = new LongAdder();

    public StampedStock(int capacity) {
        this.capacity = capacity;
        this.remain = capacity;
    }

    @Override
    public String name() {
        return "StampedLock";
    }

    @Override
    public boolean tryDeduct() {
        // 第一层：乐观读探路。库存已经空了就直接返回，不抢锁。
        long stamp = lock.tryOptimisticRead();
        int cur = remain;
        if (!lock.validate(stamp)) {
            // 探路期间有人写入，退化成悲观读
            stamp = lock.readLock();
            try {
                cur = remain;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        if (cur <= 0) {
            return false;
        }

        // 第二层：确实有货，才拿写锁做扣减。
        stamp = lock.writeLock();
        try {
            if (remain <= 0) {
                return false;
            }
            remain--;
            deducted.increment();
            return true;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public void restore() {
        long stamp = lock.writeLock();
        try {
            if (remain < capacity) {
                remain++;
                deducted.decrement();
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public int remaining() {
        long stamp = lock.tryOptimisticRead();
        int v = remain;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                v = remain;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return v;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public long deducted() {
        return deducted.sum();
    }
}
