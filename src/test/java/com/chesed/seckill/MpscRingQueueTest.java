package com.chesed.seckill;

import com.chesed.seckill.queue.MpscRingQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自写环形队列的正确性：不丢、不重、顺序在单生产者下保持一致。 */
class MpscRingQueueTest {

    @Test
    @DisplayName("单线程基本进出队")
    void singleThread() {
        MpscRingQueue<Integer> q = new MpscRingQueue<>(8);
        for (int i = 0; i < 8; i++) {
            assertTrue(q.offer(i), "第 " + i + " 个应该能入队");
        }
        assertFalse(q.offer(99), "满了必须拒绝");
        for (int i = 0; i < 8; i++) {
            assertEquals(i, q.poll());
        }
        assertNull(q.poll(), "空了必须返回 null");
    }

    @Test
    @DisplayName("容量必须是 2 的幂")
    void powerOfTwo() {
        try {
            new MpscRingQueue<Integer>(100);
            throw new AssertionError("应该抛异常");
        } catch (IllegalArgumentException expected) {
            // 正常
        }
    }

    @Test
    @DisplayName("多消费者并发取：不丢不重")
    void multiConsumerNoLossNoDup() throws Exception {
        int capacity = 1024;
        int total = 200_000;
        MpscRingQueue<Integer> q = new MpscRingQueue<>(capacity);
        ConcurrentLinkedQueue<Integer> consumed = new ConcurrentLinkedQueue<>();
        AtomicInteger offered = new AtomicInteger();

        // 单个生产者，消费者 8 个
        Thread producer = new Thread(() -> {
            int v = 0;
            while (v < total) {
                if (q.offer(v)) {
                    offered.incrementAndGet();
                    v++;
                } else {
                    Thread.onSpinWait();
                }
            }
        });
        producer.start();

        int consumers = 8;
        CountDownLatch done = new CountDownLatch(consumers);
        List<Thread> list = new ArrayList<>();
        for (int i = 0; i < consumers; i++) {
            Thread t = new Thread(() -> {
                try {
                    long idle = 0;
                    while (true) {
                        Integer v = q.poll();
                        if (v != null) {
                            consumed.add(v);
                            idle = 0;
                        } else {
                            // 多次空转且生产者已结束，就认为取完了
                            if (!producer.isAlive() && q.size() == 0) {
                                return;
                            }
                            idle++;
                            Thread.onSpinWait();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
            t.start();
            list.add(t);
        }
        producer.join();
        done.await();
        for (Thread t : list) {
            t.join();
        }

        assertEquals(total, offered.get(), "入队数应等于生产数");
        assertEquals(total, consumed.size(), "出队数应等于入队数，不能丢");
        // 不重：去重后数量应相同
        List<Integer> sorted = new ArrayList<>(consumed);
        sorted.sort(Integer::compareTo);
        for (int i = 0; i < sorted.size(); i++) {
            assertEquals(i, sorted.get(i), "第 " + i + " 个元素错了，可能重复或丢失");
        }
    }
}
