package com.chesed.seckill.queue;

/**
 * 队列的统一抽象。这里不用 java.util.Queue，因为需要额外的 size/capacity/isFull
 * 用于面板展示和背压判断，而且自写的环形队列不适合硬凑 BlockingQueue 的完整语义。
 * 所有实现只要求：offer 非阻塞、poll 非阻塞、线程安全。
 */
public interface TaskQueue<E> {

    /** 队列类型名，实验输出用。 */
    String name();

    /** 非阻塞入队，队列满返回 false。 */
    boolean offer(E task);

    /** 非阻塞出队，空返回 null。 */
    E poll();

    /** 当前元素个数（近似值即可，用于监控）。 */
    int size();

    /** 容量上限，Integer.MAX_VALUE 表示无界。 */
    int capacity();
}
