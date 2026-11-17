package com.chesed.seckill.stock;

/** 最朴素的实现：整个方法加 synchronized。基准对照。 */
public final class SyncStock implements Stock {

    private final int capacity;
    private int remain;
    private long deducted;

    public SyncStock(int capacity) {
        this.capacity = capacity;
        this.remain = capacity;
    }

    @Override
    public String name() {
        return "synchronized";
    }

    @Override
    public synchronized boolean tryDeduct() {
        if (remain <= 0) {
            return false;
        }
        remain--;
        deducted++;
        return true;
    }

    @Override
    public synchronized void restore() {
        if (remain < capacity) {
            remain++;
            deducted--;
        }
    }

    @Override
    public synchronized int remaining() {
        return remain;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public synchronized long deducted() {
        return deducted;
    }
}
