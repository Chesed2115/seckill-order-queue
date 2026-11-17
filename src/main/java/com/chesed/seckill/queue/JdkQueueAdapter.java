package com.chesed.seckill.queue;

import java.util.concurrent.BlockingQueue;

/** 把 JDK 的 BlockingQueue 适配成 TaskQueue，非阻塞语义用 offer/poll 实现。 */
public final class JdkQueueAdapter<E> implements TaskQueue<E> {

    private final String name;
    private final BlockingQueue<E> delegate;
    private final int capacity;

    public JdkQueueAdapter(String name, BlockingQueue<E> delegate, int capacity) {
        this.name = name;
        this.delegate = delegate;
        this.capacity = capacity;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean offer(E task) {
        return delegate.offer(task);
    }

    @Override
    public E poll() {
        return delegate.poll();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }
}
