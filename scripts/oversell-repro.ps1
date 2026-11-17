# 端到端超卖复现率实验
#
# 背景：AdderStock 的非安全分支在 26 组 E2 配置里 oversold 恒为 0，
# 一度被写成「端到端测不出来」。后来现场演示时偶然复现（success=1002，对账 remaining=-2），
# 说明「单次跑不出来」不等于「测不出来」。这个脚本量化两件事：
#   ① 同一配置重复跑 N 次，超卖出现的频率
#   ② 作为对照，CAS 版本重复同样次数，超卖始终为 0
#
# 输出：docs/experiments/results-oversell-e2e.csv
#
# 用法（项目根目录，需先 mvn -DskipTests package）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\oversell-repro.ps1 -Runs 40

param(
    [int]$Runs = 40,
    [int]$Concurrency = 256,
    [long]$Total = 20000,
    [int]$Inventory = 1000
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $repo "target\seckill.jar"
$port = 8099
$base = "http://127.0.0.1:$port"

if (-not (Test-Path $jar)) {
    throw "找不到 $jar，请先执行：mvn -DskipTests package"
}

$java = "java"
$oracleJava = "C:\Program Files\Common Files\Oracle\Java\javapath\java.exe"
if (Test-Path $oracleJava) { $java = $oracleJava }

function Post-Api($path, $obj) {
    Invoke-RestMethod -Uri "$base$path" -Method Post `
        -Body ($obj | ConvertTo-Json -Compress) `
        -ContentType "application/json; charset=utf-8" -TimeoutSec 120
}

function Get-Api($path) {
    Invoke-RestMethod -Uri "$base$path" -TimeoutSec 120
}

$lines = netstat -ano | Select-String ":$port\s" | Select-String "LISTENING"
foreach ($l in $lines) {
    $oldPid = ($l.ToString().Trim() -split "\s+")[-1]
    Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Milliseconds 900

$dataDir = Join-Path $repo "data"
if (Test-Path $dataDir) { Remove-Item -Recurse -Force $dataDir }

$outLog = Join-Path $env:TEMP "seckill-repro-out.log"
$errLog = Join-Path $env:TEMP "seckill-repro-err.log"
$proc = Start-Process -FilePath $java `
    -ArgumentList "-jar", "`"$jar`"", "$port" `
    -WorkingDirectory $repo `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
    -PassThru -WindowStyle Hidden

$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 300
    try { if ((Get-Api "/api/health").ok) { $ready = $true; break } } catch { }
}
if (-not $ready) { throw "服务启动失败，见 $errLog" }

$csv = Join-Path $repo "docs\experiments\results-oversell-e2e.csv"
$rows = New-Object System.Collections.Generic.List[string]
$rows.Add("impl,run,concurrency,total,inventory,success,oversold,remaining,consistent")

foreach ($impl in @("AdderUnsafe", "CAS")) {
    for ($k = 1; $k -le $Runs; $k++) {
        $cfg = @{
            stock = $impl; queue = "ArrayBlockingQueue"; queueCapacity = 8192
            dedup = "none"; limiter = "none"; limitQps = 10000
            workers = 32; store = "h2"; inventory = $Inventory; ttlMillis = 600000
        }
        Post-Api "/api/config/apply" $cfg | Out-Null
        Post-Api "/api/load/start" @{ concurrency = $Concurrency; total = $Total } | Out-Null

        $m = $null
        for ($i = 0; $i -lt 600; $i++) {
            Start-Sleep -Milliseconds 300
            $m = Get-Api "/api/metrics"
            if (-not $m.loading) { break }
        }

        # 超卖判据：成交数超过初始库存。remaining 用对账里的值，
        # 它在超卖时会变成负数（H2 的 remaining = initial - 已占用）。
        $r = (Get-Api "/api/orders?limit=1").reconcile
        $oversold = [int]$m.success - $Inventory

        $rows.Add(("{0},{1},{2},{3},{4},{5},{6},{7},{8}" -f `
                    $impl, $k, $Concurrency, $Total, $Inventory, `
                    $m.success, $oversold, $r.remaining, $r.consistent))

        $tag = if ($oversold -gt 0) { "  <== 超卖" } else { "" }
        Write-Host ("{0,-12} 第 {1,3} 次  成交 {2,5}  超卖 {3,3}  remaining {4,4}{5}" -f `
                $impl, $k, $m.success, $oversold, $r.remaining, $tag) `
            -ForegroundColor $(if ($oversold -gt 0) { "Red" } else { "Gray" })
    }
}

$rows | Set-Content -Path $csv -Encoding UTF8
Write-Host ""
Write-Host "已写入 $csv" -ForegroundColor Green

$data = Import-Csv $csv
foreach ($impl in @("AdderUnsafe", "CAS")) {
    $sub = $data | Where-Object impl -eq $impl
    $hits = ($sub | Where-Object { [int]$_.oversold -gt 0 }).Count
    $max = ($sub | Measure-Object -Property oversold -Maximum).Maximum
    Write-Host ("{0,-12} {1} 次里 {2} 次超卖，最多超 {3} 件，复现率 {4:P1}" -f `
            $impl, $sub.Count, $hits, $max, ($hits / $sub.Count))
}

Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
