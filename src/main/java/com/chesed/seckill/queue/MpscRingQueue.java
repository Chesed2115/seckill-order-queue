package com.chesed.seckill.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 自写的单生产者多消费者无锁环形队列（MPSC）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>容量必须是 2 的幂，取模可以用位与，省掉一次除法。</li>
 *   <li>每个槽位带一个序号。生产者写数据后把序号推到 pos+1 发布，
 *       消费者靠序号判断槽位是否可读，无需任何锁。</li>
 *   <li>多消费者之间用 CAS 抢占 tail，抢输的重读再来。</li>
 *   <li>head 只有生产者写，不需要原子操作。</li>
 *   <li>手动填充缓存行，防止 head/tail 与相邻字段落在同一缓存行导致伪共享。</li>
 * </ul>
 * </p>
 */
public final class MpscRingQueue<E> implements TaskQueue<E> {

    private static final class Slot<E> {
        volatile Object value;
    }

    private static final VarHandle TAIL;

    static {
        try {
            TAIL = MethodHandles.lookup().findVarHandle(MpscRingQueue.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int mask;
    private final Slot<E>[] slots;
    private final AtomicLongArray seq;
    private final int capacity;

    // 生产者独占，前后补一组 long 撑满缓存行
    private volatile long head;
    private long p0, p1, p2, p3, p4, p5, p6;

    // 消费者共享，靠 CAS 推进
    private volatile long tail;
    private long c0, c1, c2, c3, c4, c5, c6;

    @SuppressWarnings("unchecked")
    public MpscRingQueue(int capacity) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity 必须是 2 的幂: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.slots = new Slot[capacity];
        long[] init = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = new Slot<>();
            init[i] = i;
        }
        this.seq = new AtomicLongArray(init);
    }

    @Override
    public String name() {
        return "MpscRing(CAS)";
    }

    @Override
    public boolean offer(E task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        long pos = head;
        int idx = (int) (pos & mask);
        // 该槽位还被上一圈的消费者占着，说明满了
        if (seq.get(idx) - pos < 0) {
            return false;
        }
        slots[idx].value = task;
        // 先写数据再发布序号，set 是 volatile 写，保证数据对消费者可见
        seq.set(idx, pos + 1);
        head = pos + 1;
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E poll() {
        for (; ; ) {
            long pos = tail;
            int idx = (int) (pos & mask);
            if (seq.get(idx) - (pos + 1) < 0) {
                // 生产者还没写到这里，队列为空
                return null;
            }
            @SuppressWarnings("unchecked")
            E v = (E) slots[idx].value;
            if (!TAIL.compareAndSet(this, pos, pos + 1)) {
                // 被别的消费者抢走了，重新读 tail 再试
                continue;
            }
            slots[idx].value = null;
            // 把槽位还回给生产者，序号推进到下一圈
            seq.set(idx, pos + capacity);
            return v;
        }
    }

    @Override
    public int size() {
        // head / tail 非强一致，仅供监控，允许误差
        long d = head - tail;
        return d <= 0 ? 0 : (int) Math.min(d, capacity);
    }

    @Override
    public int capacity() {
        return capacity;
    }
}
