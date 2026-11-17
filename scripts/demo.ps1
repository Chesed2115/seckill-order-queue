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
    $lines = netstat -ano | Select-String ":$port\s" | Select-String "LISTENING"
    if ($lines) {
        foreach ($l in $lines) {
            $oldPid = ($l.ToString().Trim() -split "\s+")[-1]
            Write-Host "  端口 $port 被 PID=$oldPid 占用，先结束它" -ForegroundColor DarkYellow
            Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 900
    }
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
    for ($i = 0; $i -lt 400; $i++) {
        $m = Get-Api "/api/metrics"
        if (-not $m.loading) { return $m }
        Start-Sleep -Milliseconds 300
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
    Write-Host ("  性能  吞吐 {0} 单/秒   全部请求 P99 {1} ms   成交单 P99 {2} ms" -f `
            $m.tps, $m.p99, $m.orderP99)
    Write-Host ("  库存  余 {0} / 已扣 {1} / 容量 {2}        队列峰值 {3} / 容量 {4}" -f `
            $m.stockRemaining, $m.stockDeducted, $m.stockCapacity, $m.queuePeak, $m.queueCapacity)
    Write-Host ("  对账  已占 {0}  已关 {1}  死信 {2}  待付 {3}  剩余 {4}  初始 {5}  一致 {6}" -f `
            $r.sold, $r.closed, $r.dead, $r.pending, $r.remaining, $r.initial, $r.consistent)

    if ($r.consistent) {
        Write-Host "  结论  恒等式成立：剩余 + 已占 + 已关 + 死信 = 初始库存" -ForegroundColor Green
    }
    else {
        Write-Host "  结论  恒等式不成立，库存与订单对不上" -ForegroundColor Red
    }
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

Invoke-Cycle "第 1 组 / 主链路：1000 件库存，2 万次抢购" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "map"; limiter = "none"; limitQps = 10000
    workers = 16; store = "h2"; inventory = 1000; ttlMillis = $ttl
} @{ concurrency = 64; total = 20000 }

Invoke-Cycle "第 2 组 / 同一用户重复提交，去重打开" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "map"; limiter = "none"; limitQps = 10000
    workers = 16; store = "h2"; inventory = 1000; ttlMillis = $ttl
} @{ concurrency = 16; total = 2000; sameUser = $true; reuseRequestId = $true }

Invoke-Cycle "第 3 组 / 同一用户重复提交，去重关闭（对照组）" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "none"; limiter = "none"; limitQps = 10000
    workers = 16; store = "h2"; inventory = 1000; ttlMillis = $ttl
} @{ concurrency = 16; total = 2000; sameUser = $true; reuseRequestId = $true }

Invoke-Cycle "第 4 组 / 令牌桶限流 5000 QPS（库存管够，看拦截）" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "none"; limiter = "token"; limitQps = 5000
    workers = 16; store = "memory"; inventory = 200000; ttlMillis = $ttl
} @{ concurrency = 128; total = 20000 }

Invoke-Cycle "第 5 组 / 有界队列背压：容量 64，只有 4 个消费者" @{
    stock = "CAS"; queue = "ArrayBlockingQueue"; queueCapacity = 64
    dedup = "none"; limiter = "none"; limitQps = 10000
    workers = 4; store = "memory"; inventory = 200000; ttlMillis = $ttl
} @{ concurrency = 128; total = 20000 }

Invoke-Cycle "第 6 组 / 库存扣减换成 LongAdder（对照 CAS）" @{
    stock = "AdderUnsafe"; queue = "ArrayBlockingQueue"; queueCapacity = 8192
    dedup = "none"; limiter = "none"; limitQps = 10000
    workers = 32; store = "h2"; inventory = 1000; ttlMillis = $ttl
} @{ concurrency = 256; total = 20000 }

Write-Host ""
Write-Host ("─" * 76) -ForegroundColor DarkCyan
Write-Host "  第 6 组要接着讲的一句话" -ForegroundColor Yellow
Write-Host ("─" * 76) -ForegroundColor DarkCyan
Write-Host @"
  上面这组用 LongAdder 扣库存，成交数同样是 1000，一件都没超卖。
  这不代表 LongAdder 是安全的 —— 它的"读-判断-写"不是原子操作，
  只是竞态窗口只有十几纳秒，端到端压测里每线程还要排队进 HTTP 处理，
  撞上的概率低到跑几万次都碰不到。
  真正的证据来自专用探针：把库存在一个进程内反复抢，去掉 HTTP 开销，
  128 线程和 256 线程下能稳定复现超卖 1 件。命令见 README。
"@ -ForegroundColor Gray
Write-Host "  也就是说：跑不出来不等于没有 bug，只能说明触发条件没凑齐。" -ForegroundColor Yellow

Write-Host ""
Write-Host ("─" * 76) -ForegroundColor DarkCyan
Write-Host "  服务仍在运行，可以打开面板继续讲" -ForegroundColor Green
Write-Host "    $base" -ForegroundColor White
Write-Host "  结束演示后清理： Stop-Process -Id $($proc.Id) -Force" -ForegroundColor DarkGray
Write-Host ("─" * 76) -ForegroundColor DarkCyan
Write-Host ""
