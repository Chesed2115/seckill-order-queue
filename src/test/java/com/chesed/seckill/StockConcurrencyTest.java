package com.chesed.seckill;

import com.chesed.seckill.stock.AdderStock;
import com.chesed.seckill.stock.CasStock;
import com.chesed.seckill.stock.LockStock;
import com.chesed.seckill.stock.StampedStock;
import com.chesed.seckill.stock.Stock;
import com.chesed.seckill.stock.SyncStock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发正确性测试：每种库存策略在高压下都必须恰好卖出 inventory 件，一件不多一件不少。
 * 这是整个项目最核心的断言——超卖是秒杀系统最严重的事故。
 */
class StockConcurrencyTest {

    private static final int STOCK = 1000;
    private static final int THREADS = 256;

    @Test
    @DisplayName("synchronized 不超卖")
    void syncNoOversell() throws Exception {
        assertExactlySold(new SyncStock(STOCK));
    }

    @Test
    @DisplayName("ReentrantLock 不超卖")
    void lockNoOversell() throws Exception {
        assertExactlySold(new LockStock(STOCK));
    }

    @Test
    @DisplayName("StampedLock 不超卖")
    void stampedNoOversell() throws Exception {
        assertExactlySold(new StampedStock(STOCK));
    }

    @Test
    @DisplayName("CAS 不超卖")
    void casNoOversell() throws Exception {
        assertExactlySold(new CasStock(STOCK));
    }

    @Test
    @DisplayName("LongAdder 加锁版本不超卖")
    void adderSafeNoOversell() throws Exception {
        assertExactlySold(new AdderStock(STOCK, false));
    }

    @Test
    @DisplayName("LongAdder 不加锁版本会超卖（反例，证明条件扣减不能光靠无锁计数）")
    void adderUnsafeOversells() throws Exception {
        AdderStock unsafe = new AdderStock(STOCK, true);
        AtomicInteger success = hammer(unsafe, THREADS, 50);
        System.out.println("unsafe 模式成功扣减 " + success.get() + " 次，库存仅 " + STOCK);
        // 不断言具体超卖数字，因为每次运行都不一样；只断言它确实超了
        assertTrue(success.get() > STOCK,
                "预期会出现超卖，实际成功 " + success.get() + " 次");
    }

    private void assertExactlySold(Stock stock) throws Exception {
        AtomicInteger success = hammer(stock, THREADS, 50);
        assertEquals(STOCK, success.get(), "成功扣减数必须等于库存数");
        assertEquals(0, stock.remaining(), "卖完后库存必须归零");
        assertEquals(STOCK, stock.deducted(), "累计扣减数必须等于库存数");
        assertTrue(stock.remaining() >= 0, "库存永远不能为负");
    }

    /** 每个线程尝试 perThread 次，返回成功次数。用 CountDownLatch 保证同时开跑。 */
    private AtomicInteger hammer(Stock stock, int threads, int perThread) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        List<Thread> list = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int k = 0; k < perThread; k++) {
                        if (stock.tryDeduct()) {
                            success.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
            list.add(t);
        }
        start.countDown();
        done.await();
        for (Thread t : list) {
            t.join();
        }
        return success;
    }
}
