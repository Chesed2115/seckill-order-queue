package com.chesed.seckill.ratelimit;

/** 限流器统一接口。tryAcquire 不阻塞，拿不到令牌就立刻拒绝，这样延迟才不会失控。 */
public interface RateLimiter {

    String name();

    /** 尝试拿一个许可。返回 false 表示本次请求应被拒绝。 */
    boolean tryAcquire();

    /** 当前配置的目标速率（每秒），仅用于展示。 */
    int permitsPerSecond();
}
