package com.chesed.seckill.domain;

/**
 * 秒杀请求。userId 用于去重，requestId 由客户端生成，保证同一用户重复提交只算一次。
 */
public record SeckillRequest(long userId, long skuId, String requestId, long arriveNanos) {

    public static SeckillRequest of(long userId, long skuId, String requestId) {
        return new SeckillRequest(userId, skuId, requestId, System.nanoTime());
    }
}
