package com.chesed.seckill.stock;

import java.util.concurrent.locks.ReentrantLock;

/** 显式锁。对比 synchronized 能看到 AQS 相对内置锁的开销差异。 */
public final class LockStock implements Stock {

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private int remain;
    private long deducted;

    public LockStock(int capacity) {
        this.capacity = capacity;
        this.remain = capacity;
    }

    @Override
    public String name() {
        return "ReentrantLock";
    }

    @Override
    public boolean tryDeduct() {
        lock.lock();
        try {
            if (remain <= 0) {
                return false;
            }
            remain--;
            deducted++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void restore() {
        lock.lock();
        try {
            if (remain < capacity) {
                remain++;
                deducted--;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int remaining() {
        lock.lock();
        try {
            return remain;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public long deducted() {
        lock.lock();
        try {
            return deducted;
        } finally {
            lock.unlock();
        }
    }
}
