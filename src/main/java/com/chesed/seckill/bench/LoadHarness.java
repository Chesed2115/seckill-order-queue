package com.chesed.seckill.bench;

import com.chesed.seckill.domain.SeckillRequest;
import com.chesed.seckill.domain.SeckillResult;
import com.chesed.seckill.order.SeckillEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个最小可用的压测执行器：N 个线程同时起跑，打完 total 个请求就停。
 *
 * <p>为什么要 CountDownLatch 而不是直接 start：如果线程一个个 start，
 * 先启动的线程已经在打了，后启动的还没热身，测出来的 p99 会被起始阶段拉低。
 * 用门闩让所有线程卡在同一起跑线，瞬时并发才对得上设定值。
 * </p>
 */
public final class LoadHarness {

    private final SeckillEngine engine;

    public LoadHarness(SeckillEngine engine) {
        this.engine = engine;
    }

    /**
     * 跑一轮压测。
     *
     * @param total       总请求数（含失败）
     * @param vusers      并发线程数
     * @param dupRate     重复请求比例 0.0~1.0；0 表示每个请求 userId 与 requestId 都不同
     * @return 统计结果
     */
    public RunReport run(long total, int vusers, double dupRate) throws InterruptedException {
        long start = System.nanoTime();
        AtomicLong seq = new AtomicLong();
        AtomicLong hitRateLimit = new AtomicLong();
        // 第一次见到"已售罄"的时刻。用 CAS 只让第一个写进去，
        // 后面所有线程的写请求都会失败但不影响结果。
        AtomicLong soldOutNanos = new AtomicLong(0L);
        // 重复请求里真正抢到单的次数。去重关闭时，同一条 requestId 会被重复下单，
        // 这些多出来的单就是 duplicate_orders，必须能测出来。
        AtomicLong dupSuccess = new AtomicLong(0L);
        List<Thread> threads = new ArrayList<>(vusers);
        CountDownLatch ready = new CountDownLatch(vusers);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(vusers);

        for (int i = 0; i < vusers; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done.countDown();
                    return;
                }
                while (true) {
                    long id = seq.getAndIncrement();
                    if (id >= total) {
                        break;
                    }
                    // 重复请求：dupRate 决定这条请求是不是重发同一条
                    boolean dup = dupRate > 0 && (id % 100) < (long) (dupRate * 100);
                    long userId = dup ? 1L : id;
                    String reqId = dup ? "dup-1" : ("r-" + id);
                    SeckillResult r = engine.seckill(SeckillRequest.of(userId, 1L, reqId));
                    if (r.status() == SeckillResult.Status.RATE_LIMITED) {
                        hitRateLimit.incrementAndGet();
                    }
                    if (r.status() == SeckillResult.Status.SOLD_OUT) {
                        soldOutNanos.compareAndSet(0L, System.nanoTime() - start);
                    }
                    if (dup && r.status() == SeckillResult.Status.SUCCESS) {
                        dupSuccess.incrementAndGet();
                    }
                }
                done.countDown();
            }, "vu-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }

        ready.await();
        gate.countDown();
        done.await();
        long durationNanos = System.nanoTime() - start;

        for (Thread t : threads) {
            t.join(1000);
        }
        // 让消费者把队列排空，否则最后一批订单没落库，对账会少
        engine.awaitDrain(30_000);

        return new RunReport(durationNanos, hitRateLimit.get(), soldOutNanos.get(),
                dupSuccess.get());
    }

    /** 一次运行的原始读数，尚未格式化。 */
    public record RunReport(long durationNanos, long hitRateLimit, long soldOutNanos,
                            long dupSuccess) {

        public double tps(long total) {
            double sec = durationNanos / 1_000_000_000.0;
            return sec <= 0 ? 0 : total / sec;
        }

        public long durationMillis() {
            return durationNanos / 1_000_000;
        }

        /** 库存被抢光用了多久（毫秒）。0 表示这一轮根本没卖完。 */
        public double soldOutMillis() {
            return soldOutNanos <= 0 ? 0 : soldOutNanos / 1_000_000.0;
        }
    }
}
