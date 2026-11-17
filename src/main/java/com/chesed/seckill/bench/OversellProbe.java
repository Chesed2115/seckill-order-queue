package com.chesed.seckill.bench;

import com.chesed.seckill.stock.AdderStock;
import com.chesed.seckill.stock.CasStock;
import com.chesed.seckill.stock.Stock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 超卖复现探针：单独量化「不加原子判断」到底会不会超卖、超卖多少。
 *
 * <p>为什么要单独写这一个类：E2 端到端跑下来，{@code AdderUnsafe} 的
 * {@code oversold} 是 0，这跟预期不符。查下来原因是
 * <b>竞态窗口相对于请求处理延迟太窄</b>：
 * 错误写法在 {@code sold.sum()} 和 {@code sold.increment()} 之间留了一个
 * 十几纳秒的窗口，而 {@code engine.seckill()} 走完一条请求要几微秒
 * （限流判断、去重判断、构造订单对象、入队），
 * 256 个线程里同一时刻落进那个窗口的期望值不到 0.05 个，
 * 所以 200 万次请求里一次都没撞上。
 * </p>
 *
 * <p>这个探针把除扣减以外的所有环节都拿掉，只留紧循环调用
 * {@code tryDeduct()}，让线程密度真正集中在竞态窗口上。
 * 这才是「这行代码有没有并发缺陷」的正确问法。
 * E2 测的是「在真实请求路径里这个缺陷会不会被触发」，两者结论不同，都要写进报告。
 * </p>
 */
public final class OversellProbe {

    /**
     * @param stock        策略名
     * @param threads      并发线程数
     * @param inventory    初始库存
     * @param attempts     每个线程的尝试次数
     * @param success      扣减成功的次数
     * @param oversold     success - inventory，正数表示超卖
     * @param failed       因售罄而失败的次数
     * @param elapsedMs    耗时
     * @param attemptsPerSec 尝试速率
     */
    public record Result(String stock, int threads, int inventory, long attempts,
                         long success, long oversold, long failed,
                         double elapsedMs, double attemptsPerSec) {
    }

    private static final String CSV_HEADER = "stock_type,threads,inventory,attempts,"
            + "success,failed_soldout,oversold,elapsed_ms,attempts_per_sec";

    private OversellProbe() {
    }

    /**
     * 跑一次探针。每个线程紧循环调用 {@code attemptsPerThread} 次。
     */
    public static Result run(String kind, int threads, int inventory,
                             int attemptsPerThread) throws InterruptedException {
        Stock stock = build(kind, inventory);
        long totalAttempts = (long) threads * attemptsPerThread;

        AtomicLong success = new AtomicLong();
        AtomicLong failed = new AtomicLong();

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Thread> pool = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int k = 0; k < attemptsPerThread; k++) {
                    if (stock.tryDeduct()) {
                        success.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                }
            }, "oversell-" + i);
            t.start();
            pool.add(t);
        }

        ready.await();
        long t0 = System.nanoTime();
        gate.countDown();
        for (Thread t : pool) {
            t.join();
        }
        long elapsed = System.nanoTime() - t0;

        long ok = success.get();
        double ms = elapsed / 1_000_000.0;
        return new Result(kind, threads, inventory, totalAttempts,
                ok, Math.max(0, ok - inventory), failed.get(), ms,
                ms <= 0 ? 0 : totalAttempts * 1_000_000_000.0 / elapsed);
    }

    public static void writeCsv(Path file, Result r) {
        try {
            StringBuilder sb = new StringBuilder();
            if (!Files.exists(file) || Files.size(file) == 0) {
                sb.append(CSV_HEADER).append('\n');
            }
            sb.append(String.format("%s,%d,%d,%d,%d,%d,%d,%.4f,%.4f%n",
                    r.stock(), r.threads(), r.inventory(), r.attempts(),
                    r.success(), r.failed(), r.oversold(),
                    r.elapsedMs(), r.attemptsPerSec()));
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Stock build(String kind, int inventory) {
        return switch (kind) {
            case "AdderUnsafe" -> new AdderStock(inventory, true);
            case "AdderSafe" -> new AdderStock(inventory, false);
            default -> new CasStock(inventory);
        };
    }

    /**
     * 用法：{@code OversellProbe <输出csv>}
     *
     * <p>线程数从 1 扫到 1024。线程越多，同一时刻落在竞态窗口里的概率越大，
     * 超卖数量应当随线程数上升——这条曲线本身就是"这个缺陷是真的"的证据。
     * 同时把正确的 {@code CAS} 放进来作为对照，它的超卖在任何线程数下都必须是 0。
     * </p>
     */
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/experiments/results-oversell.csv");
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);

        final int inventory = 1000;
        final int attemptsPerThread = 50;

        System.out.println("== 超卖复现探针（库存 " + inventory + "，每线程紧循环 "
                + attemptsPerThread + " 次）==");

        for (int kind = 0; kind < 3; kind++) {
            String name = new String[]{"AdderUnsafe", "AdderSafe", "CAS"}[kind];
            for (int threads : new int[]{1, 8, 32, 128, 256, 512, 1024}) {
                // 每种组合跑 5 次取超卖最多的那次：超卖是概率事件，偶尔一次没撞上不代表安全
                Result worst = null;
                for (int rep = 0; rep < 5; rep++) {
                    Result r = run(name, threads, inventory, attemptsPerThread);
                    if (worst == null || r.oversold() > worst.oversold()) {
                        worst = r;
                    }
                }
                writeCsv(out, worst);
                System.out.printf("  %-11s t=%-4d  成功 %,6d  超卖 %,5d  失败 %,7d  %,12.0f 次/秒%n",
                        name, threads, worst.success(), worst.oversold(),
                        worst.failed(), worst.attemptsPerSec());
            }
        }

        System.out.println("\n结果: " + out);
    }
}
