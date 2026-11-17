package com.chesed.seckill.dedup;

/** 幂等去重的统一接口。firstSeen 返回 true 表示这是第一次见到这个 key。 */
public interface Deduplicator {

    String name();

    /** 如果 key 是首次出现返回 true；已存在返回 false。必须是原子的（check-and-set）。 */
    boolean firstSeen(long userId, String requestId);

    /** 已记录的 key 数量，监控用。 */
    long size();
}
