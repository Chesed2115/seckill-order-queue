package com.chesed.seckill.persistence;

import com.chesed.seckill.domain.Order;

import java.util.List;

/**
 * 订单存储。真实持久化（H2）和内存实现共用这一个接口，
 * 这样实验里可以一键切换"是否落库"，把 IO 开销单独隔离出来测。
 */
public interface OrderStore extends AutoCloseable {

    String name();

    /** 落库。orderId 重复时应抛异常而不是静默覆盖，这是防重复的最后一道关卡。 */
    void save(Order order);

    /** 批量落库，减少事务开销。 */
    void saveBatch(List<Order> orders);

    Order find(long orderId);

    List<Order> findByUser(long userId);

    /** 统计某状态的订单数，对账用。 */
    long countByStatus(Order.Status status);

    /** 全部订单数。 */
    long count();

    /** 最近的若干订单，面板列表用。 */
    List<Order> recent(int limit);

    /**
     * 库里最大的订单号，没有订单时返回 0。
     *
     * <p>订单号序列不能从固定值起算：H2 是文件库，进程重启或面板切换配置后
     * 库里的旧订单还在，序列若又从 1000000 开始，新订单会全部撞主键进死信。
     * 所以引擎构造时先问一下库里已有的最大号，接着往下发。
     * </p>
     */
    long maxOrderId();

    /**
     * 把所有仍处于 CREATED 的订单一次性关闭，返回条数。
     *
     * <p>只在切换配置（重建引擎）时调用。原因见 {@code ApiHandler#rebuild}：
     * 旧的未完成订单是拿旧库存扣的，留给新一轮的超时扫描器去回补，
     * 就会把库存补到新库存上，等于凭空多发货。
     * </p>
     */
    int closeAllCreated();

    /** 启动时对账：检查库里已售出的总数是否等于库存已扣数。 */
    Reconciliation reconcile(int initialStock);

    /** 把超过 ttlMillis 仍未支付的订单改成 CLOSED，返回受影响的订单。 */
    List<Order> closeExpired(long ttlMillis);

    @Override
    void close();

    /** 对账结果。 */
    record Reconciliation(long sold, long closed, long dead, long pending,
                          int remaining, int initial, boolean consistent) {
    }
}
