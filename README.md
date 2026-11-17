# 秒杀订单队列优化实验平台（seckill-order-queue）

《编程能力提升》课程期末项目，选题 #15「电商「秒杀」系统的订单处理队列优化」。

一个可替换组件的秒杀订单处理引擎（Java 21 + JUC）+ 一块实时监控面板，配套 99 组性能与正确性实验。

## 运行

```bash
mvn -B -DskipTests package
java -jar target/seckill.jar 8099
```

浏览器打开 <http://127.0.0.1:8099>。实验数据目录默认 `./data`（H2 文件库）。

## 实验

```bash
# 全量矩阵（约 5 分钟），结果写入 docs/experiments/results-all.csv
java -Xms2g -Xmx2g -XX:+UseG1GC -cp target/classes \
     com.chesed.seckill.bench.ExperimentRunner all docs/experiments

# 队列纯微基准 / 超卖探针
java -cp target/classes com.chesed.seckill.bench.QueueBench docs/experiments/results-queue-bench.csv
java -cp target/classes com.chesed.seckill.bench.OversellProbe docs/experiments/results-oversell.csv
```

## 现场演示

```powershell
# 六组对照，从清库启动一直跑到结论，服务留在 8099 端口方便接着开面板
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo.ps1

# 端到端超卖复现率（40 次重复，约 6 分钟），写入 results-oversell-e2e.csv
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\oversell-repro.ps1 -Runs 40
```

`demo.ps1` 会把 TTL 临时拉到 10 分钟。默认 3 秒的话，订单一超时就被扫描器
关闭并回补库存，讲解途中库存数字会自己往上跳。

第 6 组（LongAdder 版库存）每次跑的结果不固定：端到端复现率约 5%，
大多数轮次会显示"正好 1000 件、没超卖"。脚本会按当轮实际数字走不同分支，
不会替你编一个不存在的结论。要稳定看到超卖，要么多跑几十次，
要么跑上面的复现率脚本，要么用 `java -cp target\classes com.chesed.seckill.bench.OversellProbe`。

## 结构

```
src/main/java/com/chesed/seckill/
  stock/        库存扣减 5 种实现（synchronized / ReentrantLock / StampedLock / CAS / LongAdder）
  queue/        任务队列 4 种（ArrayBlockingQueue / LinkedBlockingQueue / LinkedTransferQueue / 自写 MPSC 环形队列）
  dedup/        幂等去重 3 种（不去重 / ConcurrentHashMap / 布隆过滤器）
  ratelimit/    限流 3 种（无 / 令牌桶 / 滑动窗口）
  persistence/  落库（内存 / H2）
  order/        SeckillEngine 核心链路
  metrics/      延迟直方图与计数器
  server/       JDK HttpServer + 手写 JSON
  bench/        实验框架（E1~E7）
src/main/resources/web/  原生 HTML + JS + 本地化 Chart.js 监控面板
```

## 文档

- `docs/01-实验矩阵设计.md` — 七组实验的设计与判据
- `docs/02-系统设计.md` — 结构、链路、组件对比
- `docs/03-实验分析.md` — 实测数据与结论
- `docs/figures/` — 报告用图与结构图
- `docs/deliverables/` — 期末报告（GB 8567—88，含封面与评分表）与中期进度汇报 PPT
