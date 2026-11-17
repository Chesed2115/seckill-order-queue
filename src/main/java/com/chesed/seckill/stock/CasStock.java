package com.chesed.seckill.stock;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 无锁 CAS。扣减用 AtomicInteger 的 CAS 循环保证原子性，成功计数用 LongAdder 分摊竞争。
 * 这里把两件事分开：库存必须强一致（CAS），统计可以最终一致（LongAdder）。
 */
public final class CasStock implements Stock {

    private final int capacity;
    private final AtomicInteger remain;
    private final LongAdder deducted = new LongAdder();

    public CasStock(int capacity) {
        this.capacity = capacity;
        this.remain = new AtomicInteger(capacity);
    }

    @Override
    public String name() {
        return "CAS";
    }

    @Override
    public boolean tryDeduct() {
        for (; ; ) {
            int cur = remain.get();
            if (cur <= 0) {
                return false;
            }
            if (remain.compareAndSet(cur, cur - 1)) {
                deducted.increment();
                return true;
            }
            // CAS 失败说明被别人抢先了，重读再试
        }
    }

    @Override
    public void restore() {
        for (; ; ) {
            int cur = remain.get();
            if (cur >= capacity) {
                return;
            }
            if (remain.compareAndSet(cur, cur + 1)) {
                deducted.decrement();
                return;
            }
        }
    }

    @Override
    public int remaining() {
        return remain.get();
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
