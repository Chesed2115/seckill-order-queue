#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把实验结果 CSV 画成报告/PPT 用的图。

只做一件事：读 docs/experiments/*.csv，输出 docs/figures/*.png。
所有图都输出 2 倍分辨率的 PNG（dpi=200），方便直接插进 Word。
"""
import csv
import os
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei"]
plt.rcParams["axes.unicode_minus"] = False
plt.rcParams["figure.dpi"] = 200
plt.rcParams["savefig.dpi"] = 200
plt.rcParams["savefig.bbox"] = "tight"
plt.rcParams["axes.grid"] = True
plt.rcParams["grid.alpha"] = 0.25
plt.rcParams["grid.linestyle"] = "--"
plt.rcParams["axes.axisbelow"] = True

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EXP = os.path.join(ROOT, "docs", "experiments")
OUT = os.path.join(ROOT, "docs", "figures")
os.makedirs(OUT, exist_ok=True)

# 统一配色：四个队列 / 五种锁各用固定颜色，跨图一致，读者不用重新记图例
C_BLUE, C_GREEN, C_ORANGE, C_RED, C_PURPLE, C_GRAY = (
    "#58a6ff", "#3fb950", "#e3803a", "#f85149", "#a371f7", "#8b949e")


def read_csv(name):
    path = os.path.join(EXP, name)
    if not os.path.exists(path):
        print(f"  [跳过] 找不到 {name}")
        return []
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def f(row, key, default=0.0):
    try:
        v = row.get(key, "")
        return float(v) if v not in ("", None) else default
    except (TypeError, ValueError):
        return default


def i(row, key, default=0):
    return int(f(row, key, default))


def save(fig, name):
    path = os.path.join(OUT, name)
    fig.savefig(path)
    plt.close(fig)
    print(f"  [输出] {name}")


def wan_fmt(x, _pos=None):
    """把 1.2e7 显示成 1200 万，报告里读者不必自己数零。"""
    if x >= 1e8:
        return f"{x/1e8:.1f}亿"
    if x >= 1e4:
        return f"{x/1e4:.0f}万"
    return f"{x:.0f}"


# ───────────────────────── 图 1：队列纯微基准 ─────────────────────────

def fig_queue_bench():
    rows = read_csv("results-queue-bench.csv")
    if not rows:
        return

    # 第一组：单生产者，消费者 1/4/16/32
    single = defaultdict(dict)   # queue -> {consumers: throughput}
    multi = defaultdict(dict)    # queue -> {producers: throughput}
    for r in rows:
        q, p, c = r["queue_type"], i(r, "producers"), i(r, "consumers")
        if p == 1:
            single[q][c] = f(r, "throughput_items_per_sec")
        elif c == 16:
            multi[q][p] = f(r, "throughput_items_per_sec")

    # CSV 里存的是短名（ABQ/LBQ/LTQ/MpscRing），图例里再展开成全名
    colors = {"ABQ": C_BLUE, "LBQ": C_GREEN, "LTQ": C_ORANGE, "MpscRing": C_PURPLE}
    labels = {"ABQ": "ArrayBlockingQueue（有界数组）",
              "LBQ": "LinkedBlockingQueue（有界链表）",
              "LTQ": "LinkedTransferQueue（无界）",
              "MpscRing": "MpscRingQueue（自写环形）"}
    kinds = ["ABQ", "LBQ", "LTQ", "MpscRing"]

    fig, axes = plt.subplots(1, 2, figsize=(13, 4.6))

    # 左：单生产者
    ax = axes[0]
    xs = [1, 4, 16, 32]
    for q in kinds:
        ys = [single.get(q, {}).get(c, 0) for c in xs]
        ax.plot(xs, ys, marker="o", ms=6, lw=2, color=colors[q], label=labels[q])
    ax.set_xlabel("消费者线程数")
    ax.set_ylabel("吞吐（元素/秒）")
    ax.set_title("单生产者：四个队列都能参加")
    ax.set_xticks(xs)
    ax.yaxis.set_major_formatter(FuncFormatter(wan_fmt))
    ax.legend(fontsize=8, loc="upper right")

    # 右：多生产者
    ax = axes[1]
    xs2 = [4, 16]
    width = 0.26
    for idx, q in enumerate(kinds[:3]):
        ys = [multi.get(q, {}).get(p, 0) for p in xs2]
        pos = [x + (idx - 1) * width for x in range(len(xs2))]
        ax.bar(pos, ys, width, color=colors[q], label=labels[q])
    ax.set_xticks(range(len(xs2)))
    ax.set_xticklabels([f"{p} 生产者" for p in xs2])
    ax.set_ylabel("吞吐（元素/秒）")
    ax.set_title("多生产者（16 消费者）：环形队列不参加")
    ax.yaxis.set_major_formatter(FuncFormatter(wan_fmt))
    ax.legend(fontsize=8)

    fig.suptitle("队列结构纯微基准：2,000 万元素跨队列搬运", fontsize=13, y=1.02)
    save(fig, "fig1-队列微基准.png")

    # 附：重试次数（背压强度）单独一张，因为量级差异太大不适合和吞吐同轴
    fig, ax = plt.subplots(figsize=(7.5, 4.2))
    retries = defaultdict(dict)
    for r in rows:
        retries[r["queue_type"]][(i(r, "producers"), i(r, "consumers"))] = i(r, "offer_retries")
    combos = sorted({k for q in retries for k in retries[q]})
    xlabels = [f"p{p}/c{c}" for p, c in combos]
    for idx, q in enumerate(kinds):
        ys = [retries.get(q, {}).get(k, 0) for k in combos]
        ax.bar([x + (idx - 1.5) * 0.2 for x in range(len(combos))], ys, 0.2,
               color=colors[q], label=labels[q])
    ax.set_xticks(range(len(combos)))
    ax.set_xticklabels(xlabels, fontsize=8)
    ax.set_ylabel("生产者重试次数")
    ax.set_title("队列满导致的生产者自旋重试（背压强度）")
    ax.yaxis.set_major_formatter(FuncFormatter(wan_fmt))
    ax.legend(fontsize=8)
    save(fig, "fig2-队列背压重试.png")


# ─────────────────── 图 2b：采样开关的 A/B（E1 的第二遍） ───────────────────

def fig_queue_sampling():
    """E1 跑了两遍，只差一件事：引擎要不要每次入队后调 queue.size()。

    这张图是整个项目里最像"真实 debug 过程"的一张：把采样关掉之后，
    LinkedTransferQueue 从最慢变成最快，说明原来测到的根本不是队列性能。
    """
    rows = [r for r in read_csv("results-all.csv") if r["experiment"] == "E1-queue"]
    if not rows:
        return

    kinds = ["ABQ", "LBQ", "LTQ", "MpscRing"]
    colors = {"ABQ": C_BLUE, "LBQ": C_GREEN, "LTQ": C_ORANGE, "MpscRing": C_PURPLE}
    workers = [4, 16, 32]

    def pick(q, w, sampling):
        tag = "-采样" if sampling else "-无采样"
        want = f"{q}-w{w}{tag}"
        for r in rows:
            if r["config_name"] == want:
                return f(r, "order_tps")
        return 0.0

    fig, axes = plt.subplots(1, 2, figsize=(13, 4.4), sharey=True)
    for ax, sampling, title in (
            (axes[0], True, "开着队列深度采样（面板正常运行时）"),
            (axes[1], False, "关掉队列深度采样（纯队列对比）")):
        width = 0.2
        for idx, q in enumerate(kinds):
            ys = [pick(q, w, sampling) for w in workers]
            pos = [x + (idx - 1.5) * width for x in range(len(workers))]
            ax.bar(pos, ys, width, color=colors[q], label=q)
        ax.set_xticks(range(len(workers)))
        ax.set_xticklabels([f"{w} 消费者" for w in workers])
        ax.set_title(title, fontsize=11)
        ax.yaxis.set_major_formatter(FuncFormatter(wan_fmt))
        if ax is axes[0]:
            ax.set_ylabel("有效成交吞吐（单/秒）")
            ax.legend(fontsize=8)

    fig.suptitle("同一个队列，只因为多调了一次 size()，排名就翻了过来", fontsize=13, y=1.04)
    save(fig, "fig2b-采样开关对比.png")


# ───────────────────────── 图 3：库存扣减策略 ─────────────────────────

def fig_stock():
    rows = [r for r in read_csv("results-all.csv") if r["experiment"] == "E2-stock"]
    if not rows:
        return

    order = ["Sync", "Lock", "Stamped", "CAS", "Adder"]
    colors = {"Sync": C_GRAY, "Lock": C_ORANGE, "Stamped": C_BLUE,
              "CAS": C_GREEN, "Adder": C_PURPLE}
    labels = {"Sync": "synchronized", "Lock": "ReentrantLock",
              "Stamped": "StampedLock 乐观读", "CAS": "AtomicInteger CAS",
              "Adder": "LongAdder（反例）"}

    data = defaultdict(dict)
    for r in rows:
        name = r["config_name"].rsplit("-v", 1)[0]
        if name not in order:
            continue
        data[name][i(r, "vusers")] = f(r, "tps")

    vusers = sorted({v for d in data.values() for v in d})
    fig, ax = plt.subplots(figsize=(9, 5))
    for name in order:
        ys = [data.get(name, {}).get(v, 0) for v in vusers]
        ax.plot(vusers, ys, marker="o", ms=6, lw=2.2, color=colors[name],
                label=labels[name])
    ax.set_xscale("log", base=2)
    ax.set_xticks(vusers)
    ax.set_xticklabels([str(v) for v in vusers])
    ax.set_xlabel("并发线程数")
    ax.set_ylabel("吞吐（次/秒）")
    ax.set_title("库存扣减策略：并发下的吞吐对比（库存 1000，请求 20 万，取中位数轮）")
    ax.yaxis.set_major_formatter(FuncFormatter(wan_fmt))
    ax.legend(fontsize=9)
    save(fig, "fig3-库存扣减策略对比.png")

    # 附：超卖探针——为什么 AdderUnsafe 端到端测不出超卖
    probe = read_csv("results-oversell.csv")
    if probe:
        fig, ax = plt.subplots(figsize=(8, 4.4))
        threads = sorted({i(r, "threads") for r in probe})
        groups = [("AdderUnsafe", C_RED, "LongAdder 非原子（反例）"),
                  ("AdderSafe", C_PURPLE, "LongAdder + 锁"),
                  ("CAS", C_GREEN, "AtomicInteger CAS")]
        for idx, (name, color, label) in enumerate(groups):
            ys = []
            for t in threads:
                match = [r for r in probe
                         if r["stock_type"] == name and i(r, "threads") == t]
                ys.append(i(match[0], "oversold") if match else 0)
            ax.bar([x + (idx - 1) * 0.26 for x in range(len(threads))], ys, 0.26,
                   color=color, label=label)
        ax.set_xticks(range(len(threads)))
        ax.set_xticklabels([str(t) for t in threads])
        ax.set_xlabel("并发线程数")
        ax.set_ylabel("超卖数量")
        ax.set_title("超卖复现探针：只有裸 LongAdder 在 256 线程时卖了 2 单")
        ax.legend(fontsize=9)
        save(fig, "fig4-超卖探针.png")


# ───────────────────────── 图 5：线程池配置热力图 ─────────────────────────

def fig_pool():
    rows = [r for r in read_csv("results-all.csv") if r["experiment"] == "E3-pool"]
    if not rows:
        return

    workers = sorted({i(r, "workers") for r in rows})
    caps = sorted({i(r, "queue_capacity") for r in rows})
    grid = [[0.0] * len(caps) for _ in workers]
    for r in rows:
        w, c = i(r, "workers"), i(r, "queue_capacity")
        if w in workers and c in caps:
            grid[workers.index(w)][caps.index(c)] = f(r, "order_tps") / 1000.0

    fig, ax = plt.subplots(figsize=(8, 5))
    im = ax.imshow(grid, cmap="YlGn", aspect="auto")
    ax.set_xticks(range(len(caps)))
    ax.set_xticklabels([f"{c}" for c in caps])
    ax.set_yticks(range(len(workers)))
    ax.set_yticklabels([f"{w} 线程" for w in workers])
    ax.set_xlabel("队列容量")
    ax.set_ylabel("消费者线程数")
    ax.set_title("线程池配置对有效成交吞吐的影响（千单/秒）")
    ax.grid(False)

    vmax = max(max(row) for row in grid)
    for yi in range(len(workers)):
        for xi in range(len(caps)):
            v = grid[yi][xi]
            ax.text(xi, yi, f"{v:.0f}", ha="center", va="center", fontsize=9,
                    color="white" if v > vmax * 0.6 else "#1a1a1a")
    cbar = fig.colorbar(im, ax=ax)
    cbar.set_label("有效成交吞吐（千单/秒）")
    save(fig, "fig5-线程池热力图.png")


# ───────────────────────── 图 6/7：限流与去重 ─────────────────────────

def fig_limiter_dedup():
    rows = read_csv("results-all.csv")
    if not rows:
        return

    # 限流：三种策略 × 三个目标 QPS，看实际限流生效情况
    e4 = [r for r in rows if r["experiment"] == "E4-ratelimit"]
    if e4:
        names = {"none": "无限制", "token": "令牌桶", "sliding": "滑动窗口"}
        colors = {"none": C_GRAY, "token": C_BLUE, "sliding": C_GREEN}
        qps = [10000, 50000, 100000]
        fig, axes = plt.subplots(1, 2, figsize=(13, 4.4))

        ax = axes[0]
        for name in ["none", "token", "sliding"]:
            ys = []
            for q in qps:
                m = [r for r in e4 if r["config_name"] == f"{name}-qps{q}"]
                ys.append(i(m[0], "failed_ratelimit") if m else 0)
            ax.plot(qps, ys, marker="s", ms=7, lw=2, color=colors[name], label=names[name])
        ax.set_xlabel("限流器设定的放行速率（QPS）")
        ax.set_ylabel("被拒绝的请求数")
        ax.set_title("限流器实际拦截效果")
        ax.yaxis.set_major_formatter(FuncFormatter(wan_fmt))
        ax.legend(fontsize=9)

        # 右：限流带来的开销——对比无限制与两种限流器的总吞吐
        ax = axes[1]
        width = 0.26
        for idx, name in enumerate(["none", "token", "sliding"]):
            ys = []
            for q in qps:
                m = [r for r in e4 if r["config_name"] == f"{name}-qps{q}"]
                ys.append(f(m[0], "tps") if m else 0)
            ax.bar([x + (idx - 1) * width for x in range(len(qps))], ys, width,
                   color=colors[name], label=names[name])
        ax.set_xticks(range(len(qps)))
        ax.set_xticklabels([f"设定 {q} QPS" for q in qps])
        ax.set_ylabel("实际总吞吐（请求/秒）")
        ax.set_title("限流器本身的吞吐开销")
        ax.yaxis.set_major_formatter(FuncFormatter(wan_fmt))
        ax.legend(fontsize=9)
        save(fig, "fig6-限流策略对比.png")

    # 去重：三种策略 × 三种重复率
    e5 = [r for r in rows if r["experiment"] == "E5-dedup"]
    if e5:
        names = {"none": "不去重", "map": "ConcurrentHashMap", "bloom": "布隆过滤器"}
        colors = {"none": C_GRAY, "map": C_BLUE, "bloom": C_PURPLE}
        rates = [0, 30, 90]
        fig, axes = plt.subplots(1, 2, figsize=(13, 4.4))

        ax = axes[0]
        width = 0.26
        for idx, name in enumerate(["none", "map", "bloom"]):
            ys = []
            for rt in rates:
                m = [r for r in e5 if r["config_name"] == f"{name}-dup{rt}"]
                ys.append(f(m[0], "tps") if m else 0)
            ax.bar([x + (idx - 1) * width for x in range(len(rates))], ys, width,
                   color=colors[name], label=names[name])
        ax.set_xticks(range(len(rates)))
        ax.set_xticklabels([f"重复率 {rt}%" for rt in rates])
        ax.set_ylabel("总吞吐（请求/秒）")
        ax.set_title("去重策略的吞吐代价")
        ax.yaxis.set_major_formatter(FuncFormatter(wan_fmt))
        ax.legend(fontsize=9)

        # 右：正确性——不去重时重复订单数随重复率上升，去重后归零
        ax = axes[1]
        for idx, name in enumerate(["none", "map", "bloom"]):
            ys = []
            for rt in rates:
                m = [r for r in e5 if r["config_name"] == f"{name}-dup{rt}"]
                ys.append(i(m[0], "duplicate_orders") if m else 0)
            ax.bar([x + (idx - 1) * width for x in range(len(rates))], ys, width,
                   color=colors[name], label=names[name])
        ax.set_xticks(range(len(rates)))
        ax.set_xticklabels([f"重复率 {rt}%" for rt in rates])
        ax.set_ylabel("重复订单数")
        ax.set_title("正确性：重复请求是否被真正拦住")
        ax.legend(fontsize=9)
        save(fig, "fig7-去重策略对比.png")


# ───────────────────────── 图 8：极端流量 ─────────────────────────

def fig_extreme():
    rows = [r for r in read_csv("results-all.csv") if r["experiment"] == "E7-extreme"]
    if not rows:
        return

    rows.sort(key=lambda r: i(r, "total_requests"))
    totals = [i(r, "total_requests") for r in rows]
    labels = [f"{t//200000}x" for t in totals]

    fig, axes = plt.subplots(1, 3, figsize=(15, 4.3))

    ax = axes[0]
    ax.plot(range(len(rows)), [f(r, "tps") for r in rows],
            marker="o", ms=7, lw=2.2, color=C_BLUE, label="总吞吐")
    ax.plot(range(len(rows)), [f(r, "order_tps") for r in rows],
            marker="s", ms=7, lw=2.2, color=C_GREEN, label="有效成交吞吐")
    ax.set_xticks(range(len(rows)))
    ax.set_xticklabels(labels)
    ax.set_yscale("log")
    ax.set_xlabel("请求量倍数（基数 20 万）")
    ax.set_ylabel("每秒请求数（对数轴）")
    ax.set_title("吞吐：请求量涨 100 倍 ≠ 吞吐涨 100 倍")
    ax.legend(fontsize=9)

    ax = axes[1]
    so = [f(r, "soldout_ms") for r in rows]
    # 「被抢光之后」剩下的时间：请求还在继续打，但库存已经是 0，
    # 后面每一条都在闸机上被直接拒掉。这部分时间是纯浪费。
    after = [max(0.0, f(r, "duration_ms") - f(r, "soldout_ms")) for r in rows]
    xs = range(len(rows))
    ax.bar(xs, so, color=C_ORANGE, label="卖光库存用了多久")
    ax.bar(xs, after, bottom=so, color=C_GRAY, label="卖光后仍在空转")
    ax.set_xticks(list(xs))
    ax.set_xticklabels(labels)
    ax.set_xlabel("请求量倍数")
    ax.set_ylabel("耗时（毫秒）")
    # 这一栏的标题前后改过两次，记录一下：
    # 上一版数据里 soldout_ms 是 34.8/33.7/34.2/31.9 一条平线，所以当时写的是
    # 「与请求量基本无关」；重跑后变成 23.5/27.4/37.7/45.7，是单调上升的，
    # 于是那句话又不成立了。改成直接说这张图真正的结论：卖光库存本身没慢多少，
    # 真正爆炸的是卖光之后还在接的那部分流量。
    ax.set_title("真正爆炸的不是抢货，是卖光后还在空转", fontsize=11)
    ax.legend(fontsize=8)

    ax = axes[2]
    # 原来这一栏把 P50 和 P99 画在同一个对数轴上。问题是两者差了两个数量级
    # （0.0005ms vs 16ms），对数轴把「本来就在底部」的 P50 压成一条贴着轴的线，
    # 也看不出 P99 自己的变化幅度；而且科学计数法的刻度（1.5×10¹）不好读。
    # 改成线性轴、只留 P99，并把"成功单的 P99"（order_p99）加进来做对照：
    # 前者是被拒绝请求占多数的整体感受，后者是真买到货的人的等待时间。
    p99 = [f(r, "p99_ms") for r in rows]
    op99 = [f(r, "order_p99_ms") for r in rows]
    ax.plot(range(len(rows)), p99, marker="o", ms=7, lw=2.2,
            color=C_RED, label="全部请求 P99")
    ax.plot(range(len(rows)), op99, marker="^", ms=7, lw=2.2,
            color=C_BLUE, label="成交单 P99")
    ax.set_xticks(range(len(rows)))
    ax.set_xticklabels(labels)
    ax.set_xlabel("请求量倍数")
    ax.set_ylabel("延迟（毫秒，线性轴）")
    ax.set_title("尾延迟：被拒请求很快，成交单才是真等待")
    ax.set_ylim(0, max(max(p99) if p99 else 0, max(op99) if op99 else 0) * 1.25)
    ax.legend(fontsize=9)
    ax.ticklabel_format(axis="y", style="plain")

    fig.suptitle("极端流量下的系统行为", fontsize=13, y=1.02)
    save(fig, "fig8-极端流量.png")


# ───────────────────────── 图 9：实验矩阵全景 ─────────────────────────

def fig_overview():
    rows = read_csv("results-all.csv")
    if not rows:
        return

    groups = defaultdict(list)
    for r in rows:
        groups[r["experiment"]].append(r)

    order = ["E1-queue", "E2-stock", "E3-pool", "E4-ratelimit",
             "E5-dedup", "E6-correctness", "E7-extreme"]
    titles = {
        "E1-queue": "E1 队列实现", "E2-stock": "E2 库存扣减",
        "E3-pool": "E3 线程池", "E4-ratelimit": "E4 限流策略",
        "E5-dedup": "E5 请求去重", "E6-correctness": "E6 正确性验证",
        "E7-extreme": "E7 极端流量",
    }
    labels, values, colors = [], [], []
    palette = [C_BLUE, C_GREEN, C_ORANGE, C_RED, C_PURPLE, C_GRAY, "#39c5bb"]
    for idx, key in enumerate(order):
        if key in groups:
            labels.append(titles[key])
            values.append(len(groups[key]))
            colors.append(palette[idx % len(palette)])

    fig, ax = plt.subplots(figsize=(9, 4.5))
    bars = ax.bar(labels, values, color=colors)
    for bar, v in zip(bars, values):
        ax.text(bar.get_x() + bar.get_width() / 2, v + 0.4, str(v),
                ha="center", va="bottom", fontsize=10)
    ax.set_ylabel("配置数")
    ax.set_title(f"实验矩阵全景：共 {sum(values)} 组配置")
    plt.setp(ax.get_xticklabels(), rotation=15, ha="right", fontsize=9)
    save(fig, "fig0-实验矩阵全景.png")


def main():
    print("生成图表 ->", OUT)
    fig_overview()
    fig_queue_bench()
    fig_queue_sampling()
    fig_stock()
    fig_pool()
    fig_limiter_dedup()
    fig_extreme()
    print("完成。")


if __name__ == "__main__":
    main()
