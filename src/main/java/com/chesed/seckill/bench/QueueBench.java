package com.chesed.seckill.bench;

import com.chesed.seckill.queue.JdkQueueAdapter;
import com.chesed.seckill.queue.MpscRingQueue;
import com.chesed.seckill.queue.TaskQueue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 队列结构的纯微基准。
 *
 * <p>为什么单独写这么一个类、而不是复用 {@code ExperimentRunner} 的引擎路径：
 * 第一版把队列对比放在完整引擎里跑，结果测出来的是「拒绝吞吐」——
 * 库存 1000、请求 20 万，19.9 万条在扣库存就被拒了；
 * 改成大库存后，又变成有界队列塞满、{@code offer} 返回 false 被立刻拒绝，
 * {@code LinkedTransferQueue} 因为容量是 {@code Integer.MAX_VALUE} 永远不拒绝，
 * 于是它「成功率 100%」而其他三个「成功率 2%」——这比的是容量不是性能。
 * </p>
 *
 * <p>微基准要做的事很单纯：把 N 个元素从一个队列里推过去，
 * 看一秒钟能推多少个。生产者塞不进去就自旋重试（记次数），
 * 不丢弃、不拒绝，保证每个队列都搬运同样多的元素。
 * 队列被打满之后吞吐就等于消费者的出队速率，这才是要看的东西。
 * </p>
 */
public final class QueueBench {

    /** 每个元素都是同一个哨兵对象，避免对象分配干扰测量。 */
    private static final Object ITEM = new Object();

    /**
     * 一次测量的结果。
     *
     * @param queue      队列类型名
     * @param producers  生产者线程数
     * @param consumers  消费者线程数
     * @param capacity   队列容量
     * @param items      搬运的元素总数
     * @param elapsedMs  耗时（毫秒）
     * @param throughput 吞吐（元素/秒）
     * @param retries    生产者遇到队列满而重试的总次数。这个数反映队列的背压强度：
     *                   队列越容易满，生产者自旋越多；无界队列永远是 0
     */
    public record Result(String queue, int producers, int consumers, int capacity,
                         long items, double elapsedMs, double throughput, long retries) {
    }

    private static final String CSV_HEADER =
            "queue_type,producers,consumers,queue_capacity,items,elapsed_ms,"
                    + "throughput_items_per_sec,offer_retries";

    private QueueBench() {
    }

    /**
     * 跑一组测量。
     *
     * @param queueName 队列类型：ABQ / LBQ / LTQ / MpscRing
     * @param producers 生产者线程数
     * @param consumers 消费者线程数
     * @param capacity  队列容量
     * @param items     要搬运的元素总数
     */
    public static Result run(String queueName, int producers, int consumers,
                             int capacity, long items) throws InterruptedException {
        TaskQueue<Object> queue = build(queueName, capacity);

        AtomicLong consumed = new AtomicLong();
        AtomicLong retries = new AtomicLong();

        CountDownLatch ready = new CountDownLatch(producers + consumers);
        CountDownLatch gate = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>(producers + consumers);

        // 生产者：把 items 均分给 producers 个线程，塞不进去就自旋重试
        long perProducer = items / producers;
        long remainder = items % producers;
        for (int p = 0; p < producers; p++) {
            long mine = perProducer + (p < remainder ? 1 : 0);
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (long i = 0; i < mine; i++) {
                    while (!queue.offer(ITEM)) {
                        retries.incrementAndGet();
                        Thread.onSpinWait();
                    }
                }
            }, "qb-producer-" + p);
            t.start();
            threads.add(t);
        }

        // 消费者：一直取到 consumed 达到 items 为止
        for (int c = 0; c < consumers; c++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (consumed.get() < items) {
                    Object o = queue.poll();
                    if (o != null) {
                        consumed.incrementAndGet();
                    } else {
                        // 队列空了：生产者可能还在路上，让出 CPU 一小会儿
                        LockSupport.parkNanos(1_000L);
                    }
                }
            }, "qb-consumer-" + c);
            t.start();
            threads.add(t);
        }

        // 注意：这里刻意没有「深度采样线程」。
        // 第一版每 500us 调一次 queue.size()，结果发现 ArrayBlockingQueue.size()
        // 要拿锁、LinkedTransferQueue.size() 更是加锁遍历整条链表——
        // 而 LTQ 在这一轮积压到 130 万个节点，等于每 500us 遍历一百万个节点，
        // 采样本身成了主要开销，测出来的吞吐被严重低估。
        // 队列压力改用 retries 表达：队列越满，生产者自旋重试越多，这个数是精确的。

        ready.await();
        long t0 = System.nanoTime();
        gate.countDown();
        for (Thread t : threads) {
            t.join();
        }
        long elapsed = System.nanoTime() - t0;

        double ms = elapsed / 1_000_000.0;
        double throughput = ms <= 0 ? 0 : items * 1_000_000_000.0 / elapsed;
        return new Result(queueName, producers, consumers, capacity, items,
                ms, throughput, retries.get());
    }

    /** 把一组结果追加写进 CSV（表头只写一次）。 */
    public static void writeCsv(Path file, Result r) {
        boolean needHeader = !Files.exists(file) || sizeOf(file) == 0;
        StringBuilder sb = new StringBuilder();
        if (needHeader) {
            sb.append(CSV_HEADER).append('\n');
        }
        sb.append(String.format("%s,%d,%d,%d,%d,%.4f,%.4f,%d%n",
                r.queue(), r.producers(), r.consumers(), r.capacity(), r.items(),
                r.elapsedMs(), r.throughput(), r.retries()));
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static TaskQueue<Object> build(String kind, int capacity) {
        int c = Math.max(64, capacity);
        return switch (kind) {
            case "LBQ" -> new JdkQueueAdapter<>("LinkedBlockingQueue",
                    new LinkedBlockingQueue<>(c), c);
            case "LTQ" -> new JdkQueueAdapter<>("LinkedTransferQueue",
                    new LinkedTransferQueue<>(), Integer.MAX_VALUE);
            case "MpscRing" -> new MpscRingQueue<>(Integer.highestOneBit(c));
            default -> new JdkQueueAdapter<>("ArrayBlockingQueue",
                    new ArrayBlockingQueue<>(c), c);
        };
    }

    // ================= 入口 =================

    private static final long BENCH_ITEMS = 20_000_000L;
    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURE_ROUNDS = 5;

    /**
     * 用法：{@code QueueBench <输出csv>}
     *
     * <p>测两组：
     * <ol>
     *   <li>生产者=1、消费者 ∈ {1,4,16,32}：单生产者场景，四个队列都能参与，
     *       这是 {@code MpscRingQueue} 的设计工作区间。</li>
     *   <li>生产者 ∈ {4,16}、消费者=16：多生产者场景。
     *       {@code MpscRingQueue} 的 head 不是原子变量，多生产者会丢数据，
     *       所以这一组它不参加——这个限制本身就是要写进报告的结论。</li>
     * </ol>
     * </p>
     */
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/experiments/results-queue-bench.csv");
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);

        System.out.println("== 队列纯微基准（每轮搬运 " + BENCH_ITEMS + " 个元素）==");

        // 第一组：单生产者，消费者数量扫描
        System.out.println("-- 第一组：1 生产者 x 消费者数量 --");
        for (int consumers : new int[]{1, 4, 16, 32}) {
            for (String q : new String[]{"ABQ", "LBQ", "LTQ", "MpscRing"}) {
                Result r = measure(q, 1, consumers, 8192);
                writeCsv(out, r);
                System.out.printf("  %-9s p=1 c=%-2d  %,12.0f 元素/秒  (重试 %,d)%n",
                        q, consumers, r.throughput(), r.retries());
            }
        }

        // 第二组：多生产者，MPSC 环形队列不参加
        System.out.println("-- 第二组：多生产者 x 16 消费者（MpscRing 只支持单生产者，不参加）--");
        for (int producers : new int[]{4, 16}) {
            for (String q : new String[]{"ABQ", "LBQ", "LTQ"}) {
                Result r = measure(q, producers, 16, 8192);
                writeCsv(out, r);
                System.out.printf("  %-9s p=%-2d c=16  %,12.0f 元素/秒  (重试 %,d)%n",
                        q, producers, r.throughput(), r.retries());
            }
        }

        System.out.println("\n结果: " + out);
    }

    /** 预热 2 轮丢弃，再测 5 轮取吞吐中位数，返回中位数那一轮的完整结果。 */
    private static Result measure(String queue, int producers, int consumers,
                                  int capacity) throws InterruptedException {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            run(queue, producers, consumers, capacity, Math.min(BENCH_ITEMS, 2_000_000L));
        }
        List<Result> measured = new ArrayList<>(MEASURE_ROUNDS);
        for (int i = 0; i < MEASURE_ROUNDS; i++) {
            measured.add(run(queue, producers, consumers, capacity, BENCH_ITEMS));
        }
        measured.sort((a, b) -> Double.compare(a.throughput(), b.throughput()));
        return measured.get(measured.size() / 2);
    }
}
