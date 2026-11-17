# -*- coding: utf-8 -*-
"""生成报告用的结构图 / 流程图 / 状态机图。

matplotlib 画方块图比在 Word 里拼绘图形状可控得多：坐标、字号、对齐都由代码决定，
改一版重跑即可，不用手工挪形状。输出到 docs/figures/。
"""
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch, Rectangle

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei"]
plt.rcParams["axes.unicode_minus"] = False

OUT = Path(__file__).resolve().parent.parent / "docs" / "figures"
OUT.mkdir(parents=True, exist_ok=True)

INK = "#1b2430"
DEEP = "#1f4e79"
WARM = "#b03a2e"
GREEN = "#2f6f4e"
MUTED = "#6b7684"
LIGHT = "#eef2f7"
PALE = "#f7f9fc"


def canvas(w, h):
    fig, ax = plt.subplots(figsize=(w, h))
    ax.set_xlim(0, 100)
    ax.set_ylim(0, 100)
    ax.axis("off")
    fig.subplots_adjust(left=0, right=1, top=1, bottom=0)
    return fig, ax


def box(ax, x, y, w, h, text, fc=LIGHT, ec=DEEP, fs=11, tc=INK, weight="normal",
        lw=1.2, radius=1.2, align="center"):
    p = FancyBboxPatch(
        (x, y), w, h,
        boxstyle=f"round,pad=0,rounding_size={radius}",
        linewidth=lw, edgecolor=ec, facecolor=fc, zorder=2,
    )
    ax.add_patch(p)
    if align == "center":
        ax.text(x + w / 2, y + h / 2, text, ha="center", va="center",
                fontsize=fs, color=tc, weight=weight, zorder=3, linespacing=1.6)
    else:
        ax.text(x + 1.4, y + h / 2, text, ha="left", va="center",
                fontsize=fs, color=tc, weight=weight, zorder=3, linespacing=1.6)
    return p


def arrow(ax, x1, y1, x2, y2, label="", color=MUTED, fs=9, style="-|>", ls="-",
          lw=1.2, rad=0.0, lx=0, ly=0):
    a = FancyArrowPatch(
        (x1, y1), (x2, y2),
        arrowstyle=style, mutation_scale=13, linewidth=lw,
        color=color, linestyle=ls, zorder=4,
        connectionstyle=f"arc3,rad={rad}",
        shrinkA=1, shrinkB=1,
    )
    ax.add_patch(a)
    if label:
        ax.text((x1 + x2) / 2 + lx, (y1 + y2) / 2 + ly, label, ha="center",
                va="center", fontsize=fs, color=color, zorder=5,
                bbox=dict(boxstyle="round,pad=0.22", fc="white", ec="none"))


def save(fig, name):
    path = OUT / name
    fig.savefig(path, dpi=200, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    print(f"  {name}")


# ---------------------------------------------------------------- 系统结构
def fig_arch():
    fig, ax = canvas(10.2, 8.6)

    ax.text(2, 96, "浏览器监控面板", fontsize=12, color=DEEP, weight="bold")
    box(ax, 2, 84, 96, 10,
        "原生 HTML / CSS / JavaScript ＋ Chart.js     2s 轮询 /api/metrics，3s 轮询 /api/orders",
        fc=PALE, fs=10.5)

    arrow(ax, 50, 84, 50, 78, "HTTP · JSON", fs=9)
    box(ax, 2, 68, 96, 10,
        "server 层      WebServer（JDK HttpServer）  ·  ApiHandler（路由与单例引擎）  ·  Json  ·  Main",
        fc=PALE, fs=10.5)

    arrow(ax, 50, 68, 50, 62, "同进程直调", fs=9)

    # 引擎大框
    ax.add_patch(Rectangle((2, 26), 96, 36, fill=False, ec=DEEP, lw=1.6, zorder=1))
    ax.text(4, 58.6, "order / SeckillEngine —— 核心链路", fontsize=11.5, color=DEEP,
            weight="bold", va="center")

    xs = [4, 20.8, 37.6, 54.4, 71.2]
    labels = [
        ("① 限流\ntryAcquire", PALE),
        ("② 去重\nfirstSeen", PALE),
        ("③ 扣库存\ntryDeduct", "#fdf0ee"),
        ("④ 建单 + 入队\noffer", "#fdf0ee"),
        ("⑤ 消费落库\nworker", PALE),
    ]
    for (x, (t, fc)) in zip(xs, labels):
        box(ax, x, 44, 13.6, 11, t, fc=fc, fs=9.6,
            ec=(WARM if fc == "#fdf0ee" else DEEP))
    box(ax, 88, 44, 9, 11, "⑥ 扫超时\n30s", fc=LIGHT, fs=9.6)

    arrow(ax, 17.6, 49.5, 20.8, 49.5, lw=1.0)
    arrow(ax, 34.4, 49.5, 37.6, 49.5, lw=1.0)
    arrow(ax, 51.2, 49.5, 54.4, 49.5, lw=1.0)
    arrow(ax, 68.0, 49.5, 71.2, 49.5, lw=1.0)
    arrow(ax, 84.8, 49.5, 88, 49.5, lw=1.0, ls=":")

    ax.text(4, 39.6, "请求线程同步执行（必须极短）", fontsize=8.6, color=MUTED)
    ax.text(71.2, 39.6, "独立消费线程组", fontsize=8.6, color=MUTED)

    # 补偿标注
    ax.text(4, 33.2, "补偿点：", fontsize=9.2, color=WARM, weight="bold")
    ax.text(11, 33.2,
            "a 入队失败 → 回补库存    b 落库失败 → 死信 + 回补    c 超时未付 → 关闭 + 回补",
            fontsize=9.2, color=WARM)
    ax.text(4, 29.2,
            "对账恒等式：剩余库存 ＋ 已售 ＋ 已关闭 ＋ 死信 ＝ 初始库存",
            fontsize=9.2, color=GREEN)

    # 组件层
    arrow(ax, 50, 26, 50, 21.5, "接口注入", fs=9)
    comps = [
        ("stock/\n5 种扣减", 2),
        ("queue/\n4 种队列", 21.6),
        ("dedup/\n3 种去重", 41.2),
        ("ratelimit/\n3 种限流", 60.8),
        ("persistence/\n内存 + H2", 80.4),
    ]
    for t, x in comps:
        box(ax, x, 8, 17.6, 12.5, t, fc=LIGHT, fs=9.6)
    ax.text(50, 4.2,
            "全部藏在接口后面，换实现不改业务代码 —— 这是能跑 7 组对比实验的前提",
            fontsize=9.2, color=MUTED, ha="center")

    save(fig, "figA-系统结构.png")


# ---------------------------------------------------------------- 核心链路
def fig_flow():
    fig, ax = canvas(9.4, 10.4)
    ax.set_ylim(0, 103)      # 顶部留出余量，否则第一个方框上沿被裁掉
    X = 26          # 主干中心
    W, H = 30, 7.6

    def step(y, text, fc=LIGHT, ec=DEEP):
        box(ax, X - W / 2, y, W, H, text, fc=fc, ec=ec, fs=10)

    def fail(y, text):
        box(ax, 68, y, 28, H, text, fc="#fdf0ee", ec=WARM, fs=9.6)
        arrow(ax, X + W / 2, y + H / 2, 68, y + H / 2, "否", color=WARM, fs=9)

    step(94, "收到秒杀请求")
    arrow(ax, X, 94, X, 90.6, fs=9)

    step(83, "① 限流   tryAcquire( )")
    fail(83, "RATE_LIMITED\n被限流，直接返回")
    arrow(ax, X, 83, X, 79.6, "通过", fs=9, lx=5.2)

    step(72, "② 去重   firstSeen( )")
    fail(72, "DUPLICATE\n重复请求，直接返回")
    arrow(ax, X, 72, X, 68.6, "通过", fs=9, lx=5.2)

    step(61, "③ 扣库存  tryDeduct( )", fc="#fdf0ee", ec=WARM)
    fail(61, "SOLD_OUT\n已售罄，直接返回")
    arrow(ax, X, 61, X, 57.6, "有货", fs=9, lx=5.2)

    step(50, "④ 生成订单号 + 入队", fc="#fdf0ee", ec=WARM)
    fail(50, "回补库存 → REJECTED\n（扣了没入队必须还回去）")
    arrow(ax, X, 50, X, 46.6, "入队成功", fs=9, lx=8)

    step(39, "⑤ 消费线程落库", fc="#fdf0ee", ec=WARM)
    fail(39, "回补库存 → DEAD\n（死信可重放）")
    arrow(ax, X, 39, X, 35.6, "落库成功", fs=9, lx=8)

    step(28, "订单 CREATED", fc="#eaf2ea", ec=GREEN)
    step(17, "支付 → PAID")
    step(6, "超时 30s → CLOSED + 回补", fc="#eaf2ea", ec=GREEN)
    arrow(ax, X, 28, X, 24.6, fs=9)
    arrow(ax, X, 17, X, 13.6, fs=9)

    ax.text(68, 26,
            "右列为失败分支。\n任一步失败都不允许让库存\n凭空消失：要么回补，\n要么进死信等重放。",
            fontsize=9.4, color=MUTED, va="top", ha="left", linespacing=1.8)

    save(fig, "figB-核心链路.png")


# ---------------------------------------------------------------- 状态机
def fig_state():
    fig, ax = canvas(8.6, 6.4)
    ax.set_ylim(-7, 100)     # 底部留白给说明文字，否则会压在 CLOSED 方框上

    # 入口在上方居中，成功与失败两个分支平铺，避免跨状态的长弧线被误读成状态转移
    box(ax, 30, 84, 40, 10, "扣库存成功 + 已入队", fc=PALE, fs=10)
    box(ax, 8, 58, 32, 13, "CREATED\n已建单待支付", fc="#eef2f7", fs=10)
    box(ax, 60, 58, 32, 13, "DEAD\n死信，可重放", fc="#fdf0ee", ec=WARM, fs=10)
    box(ax, 8, 30, 32, 13, "PAID\n已支付", fc="#eaf2ea", ec=GREEN, fs=10)
    box(ax, 8, 3, 32, 13, "CLOSED\n超时关闭", fc="#fbf6e8", ec="#a07b2c", fs=10)

    arrow(ax, 42, 84, 24, 71.6, "落库成功", fs=9)
    arrow(ax, 58, 84, 76, 71.6, "落库失败", color=WARM, fs=9)
    arrow(ax, 24, 58, 24, 43.6, "支付", fs=9, lx=3.6)
    arrow(ax, 24, 30, 24, 16.6, "30s 未支付", fs=9, lx=9.5)

    ax.text(22, -4.5, "CLOSED 与 DEAD 都触发库存回补",
            fontsize=9, color=GREEN, ha="center")
    ax.text(60, 50,
            "对账恒等式：\n剩余库存 ＋ 已售 ＋ 已关闭 ＋ 死信\n＝ 初始库存",
            fontsize=9.2, color=GREEN, va="top", ha="left", linespacing=1.9)

    save(fig, "figC-订单状态机.png")


if __name__ == "__main__":
    print("生成结构图 →", OUT)
    fig_arch()
    fig_flow()
    fig_state()
    print("完成")
