# 秒杀订单队列优化 —— 现场演示脚本
#
# 用法（在项目根目录）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo.ps1
#
# 前置：已经执行过 mvn -DskipTests package，存在 target\seckill.jar
#
# 脚本做四件事：清库重启服务、按固定顺序跑六组对照、每组打印关键指标与对账结果、
# 最后把服务留在 8099 端口上方便接着打开面板讲。

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $repo "target\seckill.jar"
$port = 8099
$base = "http://127.0.0.1:$port"

if (-not (Test-Path $jar)) {
    throw "找不到 $jar，请先在项目根目录执行：mvn -DskipTests package"
}

$java = "java"
$oracleJava = "C:\Program Files\Common Files\Oracle\Java\javapath\java.exe"
if (Test-Path $oracleJava) { $java = $oracleJava }

# ---------------------------------------------------------------- 工具函数

function Stop-OldServer {
    # netstat 查到的 PID 才是真正占着端口的进程；
    # 直接杀 Start-Process 返回的对象没用，那是新起的、还没退出的进程。
    # IPv4 和 IPv6 各有一行，PID 相同，按 PID 去重免得打印两遍。
    $lines = netstat -ano | Select-String ":$port\s" | Select-String "LISTENING"
    $pids = @()
    if ($lines) {
        foreach ($l in $lines) {
            $oldPid = ($l.ToString().Trim() -split "\s+")[-1]
            if ($pids -notcontains $oldPid) { $pids += $oldPid }
        }
    }
    foreach ($oldPid in $pids) {
        Write-Host "  端口 $port 被 PID=$oldPid 占用，先结束它" -ForegroundColor DarkYellow
        Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
    }
    if ($pids.Count -gt 0) { Start-Sleep -Milliseconds 900 }
}

function Post-Api($path, $obj) {
    $json = $obj | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri "$base$path" -Method Post -Body $json `
        -ContentType "application/json; charset=utf-8" -TimeoutSec 60
}

function Get-Api($path) {
    Invoke-RestMethod -Uri "$base$path" -TimeoutSec 60
}

function Wait-Idle {
    # 两件事要小心：
    # 1) 吞吐要在轮询过程中取峰值。压测一停，instantThroughput() 两次采样之间
    #    没有新请求，读数必然衰减到 0，等停下来再读只能是 0。
    # 2) loading 变 false 只说明压测线程发完了，队列里的任务可能还没落库。
    #    这时候读对账会看到「成交 1000、已占 136」这种明显缺口，所以还要等队列排空。
    $peak = 0.0
    $drained = 0
    for ($i = 0; $i -lt 2400; $i++) {
        $m = Get-Api "/api/metrics"
        if ($m.tps -gt $peak) { $peak = $m.tps }
        if (-not $m.loading) {
            if ($m.queueDepth -eq 0) { $drained++ } else { $drained = 0 }
            if ($drained -ge 2) {
                $m | Add-Member -NotePropertyName peakTps -NotePropertyValue $peak -Force
                # 瞬时值抖动大（同一配置相邻两次能差一倍），而且受采样间隔影响；
                # 演示时用「整轮平均」更稳定，也更好解释：总请求数 ÷ 这一轮持续时间。
                $secs = [math]::Max(0.001, $m.uptimeMs / 1000.0)
                $m | Add-Member -NotePropertyName avgTps -NotePropertyValue ($m.total / $secs) -Force
                $m | Add-Member -NotePropertyName orderTps -NotePropertyValue ($m.success / $secs) -Force
                return $m
            }
        }
        Start-Sleep -Milliseconds 50
    }
    throw "负载在 120 秒内没有结束，检查服务是否还活着"
}

function Show-Rule($text) {
    Write-Host ""
    Write-Host ("─" * 76) -ForegroundColor DarkCyan
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host ("─" * 76) -ForegroundColor DarkCyan
}

# ---------------------------------------------------------------- 一组对照

function Invoke-Cycle($title, $cfg, $load) {
    Show-Rule $title

    $c = Post-Api "/api/config/apply" $cfg
    Write-Host ("  配置  {0} + {1}(容量 {2}) + 去重 {3} + 限流 {4}({5} QPS) + {6} 消费者 + 存储 {7} + 库存 {8}" -f `
            $c.stock, $c.queue, $c.queueCapacity, $c.dedup, $c.limiter, $c.limitQps, `
            $c.workers, $c.store, $c.inventory) -ForegroundColor DarkGray
    Write-Host ("  负载  并发 {0} × 共 {1} 次请求" -f $load.concurrency, $load.total) -ForegroundColor DarkGray

    Post-Api "/api/load/start" $load | Out-Null
    $m = Wait-Idle
    $r = (Get-Api "/api/orders?limit=1").reconcile

    Write-Host ("  请求  {0} 条：成交 {1} / 售罄拒 {2} / 重复 {3} / 限流 {4} / 背压拒 {5} / 死信 {6}" -f `
            $m.total, $m.success, $m.soldOut, $m.duplicate, $m.rateLimited, $m.rejected, $m.deadLetter)
    # 两个吞吐口径要分开写，否则会像早期实验那样拿"总请求吞吐"当性能证明：
    # 大部分请求是被售罄/限流快速挡回去的，那个数字再高也没卖出货。
    Write-Host ("  吞吐  请求 {0:N0} 次/秒   成交 {1:N0} 单/秒   全部请求 P99 {2} ms   成交单 P99 {3} ms" -f `
            [math]::Round($m.avgTps), [math]::Round($m.orderTps), $m.p99, $m.orderP99)
    Write-Host ("  库存  余 {0} / 已扣 {1} / 容量 {2}        队列峰值 {3} / 容量 {4}" -f `
            $m.stockRemaining, $m.stockDeducted, $m.stockCapacity, $m.queuePeak, $m.queueCapacity)
    Write-Host ("  对账  已占 {0}  已关 {1}  死信 {2}  待付 {3}  剩余 {4}  初始 {5}  一致 {6}" -f `
            $r.sold, $r.closed, $r.dead, $r.pending, $r.remaining, $r.initial, $r.consistent)

    # 这里不套「剩余 + 已占 + 已关 + 死信 = 初始库存」这个完整恒等式：
    # H2 是文件库，前面几轮的订单会一直累加在「已关」里，
    # 而每一轮的初始库存是重新设的，两边对不上是正常的。
    # 演示时只关心一个判据——库存有没有被扣成负数，也就是有没有超卖。
    if ([int]$r.remaining -lt 0) {
        Write-Host ("  结论  超卖 {0} 件：库存被扣成负数" -f (-$r.remaining)) -ForegroundColor Red
    }
    elseif ($r.consistent) {
        Write-Host "  结论  对账一致，没有超卖" -ForegroundColor Green
    }
    else {
        Write-Host "  结论  对账不一致（已关一栏含之前几轮结清的订单，属正常）" -ForegroundColor Yellow
    }

    return [pscustomobject]@{ metrics = $m; reconcile = $r }
}

# ---------------------------------------------------------------- 开跑

Write-Host ""
Write-Host "秒杀订单队列优化  现场演示" -ForegroundColor Yellow
Write-Host "项目目录 $repo" -ForegroundColor DarkGray

Stop-OldServer

# 每次演示从空库开始，否则上一轮留下的订单会让"库存 1000"这个前提不成立
$dataDir = Join-Path $repo "data"
if (Test-Path $dataDir) {
    Remove-Item -Recurse -Force $dataDir
    Write-Host "  已清空 data\ 目录（H2 库文件）" -ForegroundColor DarkYellow
}

$outLog = Join-Path $env:TEMP "seckill-demo-out.log"
$errLog = Join-Path $env:TEMP "seckill-demo-err.log"
Remove-Item $outLog, $errLog -ErrorAction SilentlyContinue

Write-Host "  启动服务 ..." -ForegroundColor DarkGray
$proc = Start-Process -FilePath $java `
    -ArgumentList "-jar", "`"$jar`"", "$port" `
    -WorkingDirectory $repo `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError $errLog `
    -PassThru -WindowStyle Hidden

$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 300
    try {
        $h = Get-Api "/api/health"
        if ($h.ok) { $ready = $true; break }
    }
    catch { }
}
if (-not $ready) {
    Write-Host "  服务没起来，错误日志：" -ForegroundColor Red
    if (Test-Path $errLog) { Get-Content $errLog | Select-Object -Last 20 }
    throw "启动失败"
}
Write-Host "  服务已就绪：$base" -ForegroundColor Green

# 演示期间把 TTL 拉长到 10 分钟。默认 3 秒的话，
# 订单一超时就被扫描器关闭并回补库存，库存数字会在讲解中途自己往上跳。
$ttl = 600000

$null = Invoke-Cycle "第 1 组 / 主链路：1000 件库存，2 万次抢购" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "map"; limiter = "none"; limitQps = 10000
    workers = 16; store = "h2"; inventory = 1000; ttlMillis = $ttl
} @{ concurrency = 64; total = 20000 }

$null = Invoke-Cycle "第 2 组 / 同一用户重复提交，去重打开" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "map"; limiter = "none"; limitQps = 10000
    workers = 16; store = "h2"; inventory = 1000; ttlMillis = $ttl
} @{ concurrency = 16; total = 2000; sameUser = $true; reuseRequestId = $true }

$null = Invoke-Cycle "第 3 组 / 同一用户重复提交，去重关闭（对照组）" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "none"; limiter = "none"; limitQps = 10000
    workers = 16; store = "h2"; inventory = 1000; ttlMillis = $ttl
} @{ concurrency = 16; total = 2000; sameUser = $true; reuseRequestId = $true }

$null = Invoke-Cycle "第 4 组 / 令牌桶限流 5000 QPS（库存管够，看拦截）" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "none"; limiter = "token"; limitQps = 5000
    workers = 16; store = "memory"; inventory = 200000; ttlMillis = $ttl
} @{ concurrency = 128; total = 20000 }

$null = Invoke-Cycle "第 5 组 / 有界队列背压：容量 64，只有 4 个消费者" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 64
    dedup = "none"; limiter = "none"; limitQps = 10000
    workers = 4; store = "memory"; inventory = 200000; ttlMillis = $ttl
} @{ concurrency = 128; total = 20000 }

$last = Invoke-Cycle "第 6 组 / 库存扣减换成 LongAdder（对照 CAS）" @{
    stock = "AdderUnsafe"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "none"; limiter = "none"; limitQps = 10000
    workers = 32; store = "h2"; inventory = 1000; ttlMillis = $ttl
} @{ concurrency = 256; total = 20000 }

$over = $last.metrics.success - 1000

Write-Host ""
Write-Host ("─" * 76) -ForegroundColor DarkCyan
Write-Host "  第 6 组要接着讲的一句话" -ForegroundColor Yellow
Write-Host ("─" * 76) -ForegroundColor DarkCyan

if ($over -gt 0) {
    Write-Host @"
  这次真的超卖了 $over 件 —— 成交 $($last.metrics.success) 件，库存只有 1000 件。
  LongAdder 的"读-判断-写"不是原子操作，两个线程可以同时通过库存检查，
  于是都扣减成功。这就是把非原子操作当成判断依据的后果。
"@ -ForegroundColor Red
}
else {
    Write-Host @"
  这一轮成交正好 1000 件，没超卖。但这不代表 LongAdder 是安全的 ——
  它的"读-判断-写"不是原子操作，只是竞态窗口只有十几纳秒，
  端到端跑一次撞上的概率大约 5%（40 次重复里出 2 次，见 README 的复现率实验）。
  也就是说：单看这一轮，你有一半以上的机会看不到 bug。
  想稳定看到它，要么多跑几十次，要么用探针把中间环节剥掉。
"@ -ForegroundColor Gray
}

Write-Host @"
  真正的证据来自两项实验：
    · 专用探针（去掉 HTTP 与业务逻辑）：256 线程下稳定复现 1~2 件超卖；
    · 端到端复现率（同一配置重复 40 次）：5% 触发，CAS 版 0 次。
  两个数放一起才说得清楚：跑不出来不等于没有 bug，只能说明触发条件没凑齐。
"@ -ForegroundColor Yellow

Write-Host ""
Write-Host ("─" * 76) -ForegroundColor DarkCyan
Write-Host "  服务仍在运行，可以打开面板继续讲" -ForegroundColor Green
Write-Host "    $base" -ForegroundColor White
Write-Host "  结束演示后清理： Stop-Process -Id $($proc.Id) -Force" -ForegroundColor DarkGray
Write-Host ("─" * 76) -ForegroundColor DarkCyan
Write-Host ""
