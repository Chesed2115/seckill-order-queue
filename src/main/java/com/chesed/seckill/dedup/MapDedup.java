package com.chesed.seckill.dedup;

import java.util.concurrent.ConcurrentHashMap;

/** 用 ConcurrentHashMap.putIfAbsent 做去重。精确但占内存，key 多了会 OOM。 */
public final class MapDedup implements Deduplicator {

    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "ConcurrentHashMap";
    }

    @Override
    public boolean firstSeen(long userId, String requestId) {
        String key = userId + ":" + requestId;
        // putIfAbsent 本身是原子的，null 表示之前没有，即首次出现
        return seen.putIfAbsent(key, Boolean.TRUE) == null;
    }

    @Override
    public long size() {
        return seen.size();
    }
}
