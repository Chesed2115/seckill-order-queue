package com.chesed.seckill.persistence;

import com.chesed.seckill.domain.Order;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * H2 嵌入式持久化。用文件模式，进程重启后数据还在，能验证"重启对账"。
 * 订单号做主键，天然防重复——即使上层去重失效，这里也会因为主键冲突而拒掉。
 */
public final class H2OrderStore implements OrderStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS orders (
                order_id     BIGINT PRIMARY KEY,
                user_id      BIGINT NOT NULL,
                sku_id       BIGINT NOT NULL,
                request_id   VARCHAR(64) NOT NULL,
                status       VARCHAR(16) NOT NULL,
                created_ms   BIGINT NOT NULL,
                paid_ms      BIGINT
            );
            CREATE INDEX IF NOT EXISTS idx_user ON orders(user_id);
            CREATE INDEX IF NOT EXISTS idx_status ON orders(status);
            CREATE INDEX IF NOT EXISTS idx_created ON orders(created_ms);
            """;

    private final Connection conn;

    public H2OrderStore(String jdbcUrl) {
        try {
            this.conn = DriverManager.getConnection(jdbcUrl, "sa", "");
            try (Statement st = conn.createStatement()) {
                for (String sql : DDL.split(";")) {
                    if (!sql.isBlank()) {
                        st.execute(sql);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 H2 失败", e);
        }
    }

    @Override
    public String name() {
        return "h2";
    }

    @Override
    public synchronized void save(Order order) {
        String sql = "INSERT INTO orders(order_id,user_id,sku_id,request_id,status,created_ms,paid_ms) "
                + "VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, order);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 主键冲突就是重复订单，这里把它翻译成业务异常
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new IllegalStateException("订单号重复: " + order.orderId(), e);
            }
            throw new IllegalStateException("写订单失败", e);
        }
    }

    @Override
    public void saveBatch(List<Order> orders) {
        if (orders.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO orders(order_id,user_id,sku_id,request_id,status,created_ms,paid_ms) "
                + "VALUES(?,?,?,?,?,?,?)";
        synchronized (this) {
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Order o : orders) {
                        bind(ps, o);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败也没别的办法，原始异常更重要
                }
                throw new IllegalStateException("批量写订单失败", e);
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // 连接状态异常留给后续调用暴露
                }
            }
        }
    }

    private void bind(PreparedStatement ps, Order o) throws SQLException {
        ps.setLong(1, o.orderId());
        ps.setLong(2, o.userId());
        ps.setLong(3, o.skuId());
        ps.setString(4, o.requestId());
        ps.setString(5, o.status().name());
        ps.setLong(6, o.createdMillis());
        if (o.paidMillis() > 0) {
            ps.setLong(7, o.paidMillis());
        } else {
            ps.setNull(7, java.sql.Types.BIGINT);
        }
    }

    @Override
    public synchronized Order find(long orderId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders WHERE order_id=?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查订单失败", e);
        }
    }

    @Override
    public synchronized List<Order> findByUser(long userId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM orders WHERE user_id=? ORDER BY created_ms")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Order> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(map(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("按用户查订单失败", e);
        }
    }

    @Override
    public synchronized long countByStatus(Order.Status status) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM orders WHERE status=?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计订单失败", e);
        }
    }

    @Override
    public synchronized long count() {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("统计总数失败", e);
        }
    }

    @Override
    public synchronized List<Order> recent(int limit) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM orders ORDER BY order_id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(0, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<Order> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(map(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查最近订单失败", e);
        }
    }

    @Override
    public synchronized Reconciliation reconcile(int initialStock) {        long created = countByStatus(Order.Status.CREATED);
        long paid = countByStatus(Order.Status.PAID);
        long closed = countByStatus(Order.Status.CLOSED);
        long dead = countByStatus(Order.Status.DEAD);
        long sold = created + paid;
        // 核心恒等式：已占用的库存不能超过初始库存
        boolean consistent = sold <= initialStock;
        int remaining = initialStock - (int) sold;
        return new Reconciliation(sold, closed, dead, created, remaining, initialStock, consistent);
    }

    @Override
    public synchronized List<Order> closeExpired(long ttlMillis) {
        long deadline = System.currentTimeMillis() - ttlMillis;
        List<Order> expired = new ArrayList<>();
        try (PreparedStatement sel = conn.prepareStatement(
                "SELECT * FROM orders WHERE status='CREATED' AND created_ms<?")) {
            sel.setLong(1, deadline);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    expired.add(map(rs));
                }
            }
            if (expired.isEmpty()) {
                return expired;
            }
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE orders SET status='CLOSED' WHERE order_id=? AND status='CREATED'")) {
                for (Order o : expired) {
                    upd.setLong(1, o.orderId());
                    upd.addBatch();
                }
                upd.executeBatch();
            }
            List<Order> result = new ArrayList<>(expired.size());
            for (Order o : expired) {
                result.add(o.withStatus(Order.Status.CLOSED));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("关闭超时订单失败", e);
        }
    }

    private Order map(ResultSet rs) throws SQLException {
        long paidMs = rs.getLong("paid_ms");
        boolean paidNull = rs.wasNull();
        return new Order(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getLong("sku_id"),
                rs.getString("request_id"),
                Order.Status.valueOf(rs.getString("status")),
                rs.getLong("created_ms"),
                paidNull ? 0 : paidMs);
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 关闭失败无补救手段
        }
    }
}
