package com.chesed.seckill.stock;

/**
 * 库存扣减策略的统一接口。tryDeduct 必须是原子的"检查并扣减"，
 * 这是防超卖的关键——分两步做就一定会超卖。
 */
public interface Stock {

    /** 策略名，用于实验输出和面板显示。 */
    String name();

    /** 尝试扣减 1 件。成功返回 true。 */
    boolean tryDeduct();

    /** 归还 1 件（超时取消、异常补偿时用）。 */
    void restore();

    /** 当前剩余库存。 */
    int remaining();

    /** 初始库存。 */
    int capacity();

    /** 累计扣减成功次数，用于对账。 */
    long deducted();
}
