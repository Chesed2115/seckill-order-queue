package com.chesed.seckill.domain;

/** 订单状态机：CREATED -> PAID / CLOSED（超时未支付）/ DEAD（异常无法补偿）。 */
public record Order(long orderId, long userId, long skuId, String requestId,
                    Status status, long createdMillis, long paidMillis) {

    public enum Status {
        CREATED, PAID, CLOSED, DEAD
    }

    public Order withStatus(Status s) {
        return new Order(orderId, userId, skuId, requestId, s, createdMillis,
                s == Status.PAID ? System.currentTimeMillis() : paidMillis);
    }
}
