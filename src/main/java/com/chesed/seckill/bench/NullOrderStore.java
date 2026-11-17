package com.chesed.seckill.bench;

import com.chesed.seckill.domain.Order;
import com.chesed.seckill.persistence.OrderStore;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * 空落库实现，只计数不存数据。给 L1 微基准专用。
 *
 * <p>为什么需要它：<br>
 * 微基准要比较的是「队列实现」或「消费者线程数」的差异。
 * 如果底下接的是 {@link com.chesed.seckill.persistence.MemoryOrderStore}，
 * 它的每个方法都是 synchronized 的，所有消费者线程会串行卡在同一个监视器上，
 * 这时候测出来的吞吐其实是给数据库写入的速度，队列换成什么都没区别 ——
 * 差异被下游的锁吃掉了，实验就白做。
 * </p>
 *
 * <p>所以微基准里用空实现把下游摘掉，让队列和线程调度成为唯一的瓶颈。
 * 端到端实验（L2）仍然接真实的 H2，那测的是完整链路。
 * </p>
 */
public final class NullOrderStore implements OrderStore {

    private final LongAdder saved = new LongAdder();
    private final LongAdder savedBatchItems = new LongAdder();

    @Override
    public String name() {
        return "null";
    }

    @Override
    public void save(Order order) {
        saved.increment();
    }

    @Override
    public void saveBatch(List<Order> orders) {
        saved.add(orders.size());
        savedBatchItems.add(orders.size());
    }

    @Override
    public Order find(long orderId) {
        return null;
    }

    @Override
    public List<Order> findByUser(long userId) {
        return List.of();
    }

    @Override
    public long countByStatus(Order.Status status) {
        return 0;
    }

    @Override
    public long count() {
        return saved.sum();
    }

    @Override
    public List<Order> recent(int limit) {
        return List.of();
    }

    @Override
    public long maxOrderId() {
        return 0L;
    }

    @Override
    public int closeAllCreated() {
        return 0;
    }

    @Override
    public Reconciliation reconcile(int initialStock) {
        long n = saved.sum();
        return new Reconciliation(n, 0, 0, 0, initialStock, initialStock, true);
    }

    @Override
    public List<Order> closeExpired(long ttlMillis) {
        return List.of();
    }

    @Override
    public void close() {
        // 无资源可释放
    }

    /** 落库成功的条数，用来和 metrics.success() 对账。 */
    public long savedCount() {
        return saved.sum();
    }
}
