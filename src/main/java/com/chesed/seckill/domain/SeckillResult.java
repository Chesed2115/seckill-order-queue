package com.chesed.seckill.domain;

/** 一次秒杀调用的结果，status 决定前端怎么显示。 */
public record SeckillResult(Status status, long orderId, long costNanos) {

    public enum Status {
        SUCCESS("抢购成功"),
        SOLD_OUT("已售罄"),
        DUPLICATE("重复请求"),
        RATE_LIMITED("被限流"),
        REJECTED("系统繁忙"),
        TIMEOUT("处理超时");

        private final String text;

        Status(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public static SeckillResult ok(long orderId, long costNanos) {
        return new SeckillResult(Status.SUCCESS, orderId, costNanos);
    }

    public static SeckillResult fail(Status status, long costNanos) {
        return new SeckillResult(status, -1L, costNanos);
    }
}
