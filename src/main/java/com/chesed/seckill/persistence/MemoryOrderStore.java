package com.chesed.seckill.persistence;

import com.chesed.seckill.domain.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 纯内存实现，实验里代表"不落库"的基线，也方便单元测试。 */
public final class MemoryOrderStore implements OrderStore {

    private final Map<Long, Order> byId = new ConcurrentHashMap<>();
    private final Map<Long, List<Order>> byUser = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public void save(Order order) {
        Order prev = byId.putIfAbsent(order.orderId(), order);
        if (prev != null) {
            throw new IllegalStateException("订单号重复: " + order.orderId());
        }
        byUser.computeIfAbsent(order.userId(), k -> new ArrayList<>()).add(order);
    }

    @Override
    public void saveBatch(List<Order> orders) {
        for (Order o : orders) {
            save(o);
        }
    }

    @Override
    public Order find(long orderId) {
        return byId.get(orderId);
    }

    @Override
    public List<Order> findByUser(long userId) {
        return List.copyOf(byUser.getOrDefault(userId, List.of()));
    }

    @Override
    public long countByStatus(Order.Status status) {
        return byId.values().stream().filter(o -> o.status() == status).count();
    }

    @Override
    public long count() {
        return byId.size();
    }

    @Override
    public List<Order> recent(int limit) {
        // 内存实现没有顺序保证，这里按订单号倒序取，够演示用
        return byId.values().stream()
                .sorted((a, b) -> Long.compare(b.orderId(), a.orderId()))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public long maxOrderId() {
        long max = 0L;
        for (Long id : byId.keySet()) {
            if (id > max) {
                max = id;
            }
        }
        return max;
    }

    @Override
    public int closeAllCreated() {
        int n = 0;
        for (Order o : byId.values()) {
            if (o.status() == Order.Status.CREATED) {
                if (byId.replace(o.orderId(), o, o.withStatus(Order.Status.CLOSED))) {
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    public Reconciliation reconcile(int initialStock) {
        long sold = countByStatus(Order.Status.PAID) + countByStatus(Order.Status.CREATED);
        long closed = countByStatus(Order.Status.CLOSED);
        long dead = countByStatus(Order.Status.DEAD);
        long pending = countByStatus(Order.Status.CREATED);
        int remaining = initialStock - (int) sold;
        boolean ok = sold + closed + dead <= initialStock && remaining >= 0;
        return new Reconciliation(sold, closed, dead, pending, remaining, initialStock, ok);
    }

    @Override
    public List<Order> closeExpired(long ttlMillis) {
        long deadline = System.currentTimeMillis() - ttlMillis;
        List<Order> expired = new ArrayList<>();
        for (Order o : byId.values()) {
            if (o.status() == Order.Status.CREATED && o.createdMillis() < deadline) {
                Order closed = o.withStatus(Order.Status.CLOSED);
                if (byId.replace(o.orderId(), o, closed)) {
                    expired.add(closed);
                }
            }
        }
        return expired;
    }

    @Override
    public void close() {
        // 内存实现无须释放
    }
}
