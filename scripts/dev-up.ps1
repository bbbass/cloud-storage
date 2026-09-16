<#
.SYNOPSIS
    Bring up everything cloud-storage needs, then verify it. Run this first every session.
.DESCRIPTION
    Idempotent and safe to re-run. It does:
      1. boot the WSL distro (if stopped)
      2. start sshd inside WSL
      3. start the docker daemon if it is not running (the init script fails with a ulimit error,
         so dockerd is launched directly)
      4. start the MinIO / RocketMQ containers
      5. make sure Redis is listening on 6379 with the project password
      6. make sure the RocketMQ topic cloud-storage-processing exists
      7. check MySQL (Windows service) and the Windows-side ports
      8. print a PASS/FAIL summary

    Usage:
        $env:CS_REDIS_PASSWORD = '<your local Redis password>'
        powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1
        powershell -ExecutionPolicy Bypass -File scripts\dev-up.ps1 -Repair

    The Redis password is read from the CS_REDIS_PASSWORD environment variable (or -RedisPassword)
    instead of being hard-coded, so this file carries no credential. When it is not set, the Redis
    check is skipped and reported as SKIP; Redis must then already be running with that password.

    -Repair additionally recreates the RocketMQ broker container when it cannot reach the name
    server (the original container shipped with NAMESRV_ADDR=127.0.0.1, which points at itself).
    Broker data lives in /data/rocketmq bind mounts, so recreating the container keeps the data.

    NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI (code page 936 on
    this machine) unless the file has a UTF-8 BOM, and non-ASCII literals corrupt the parser.
    Every multi-line WSL script is passed as base64 to avoid PowerShell quote mangling.
#>
param(
    [string]$Distro = 'Ubuntu-22.04',
    [string]$RedisPassword = $env:CS_REDIS_PASSWORD,
    [switch]$Repair,
    [int]$PortTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$results = New-Object System.Collections.Generic.List[object]

function Write-Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Add-Result([string]$name, [bool]$ok, [string]$detail) {
    $results.Add([pscustomobject]@{ Name = $name; Ok = $ok; Detail = $detail })
    if ($ok) { Write-Host ("  [OK]   {0} - {1}" -f $name, $detail) -ForegroundColor Green }
    else { Write-Host ("  [FAIL] {0} - {1}" -f $name, $detail) -ForegroundColor Red }
}

function Invoke-WslRoot([string]$script) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script))
    $output = wsl.exe -d $Distro -u root bash -lc "echo $b64 | base64 -d | bash" 2>&1 | Out-String
    return $output.Trim()
}

function Test-Port([int]$port) {
    return (Test-NetConnection -ComputerName 127.0.0.1 -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue)
}

function Wait-Port([int]$port, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $port) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

Write-Step "1) WSL distro: $Distro"
$states = (wsl.exe -l -v) -join "`n"
if ($states -match [regex]::Escape($Distro) -and $states -match 'Running') {
    Add-Result 'wsl' $true 'already running'
} else {
    $boot = Invoke-WslRoot 'echo booted'
    Add-Result 'wsl' ($boot -match 'booted') "booted ($boot)"
}

Write-Step "2) WSL services: sshd + dockerd + containers + Redis"
$bootstrap = @'
service ssh start >/dev/null 2>&1 || true

if ! docker info >/dev/null 2>&1; then
    nohup dockerd >/var/log/dockerd.log 2>&1 &
    for i in $(seq 1 30); do
        docker info >/dev/null 2>&1 && break
        sleep 1
    done
fi

for c in minio rocketmq-namesrv rocketmq-broker; do
    running=$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null || echo missing)
    if [ "$running" != "true" ]; then
        docker start "$c" >/dev/null 2>&1 && echo "started:$c" || echo "failed:$c"
    fi
done

# Password is injected from the caller as __REDIS_PASSWORD__ (empty means "skip Redis handling").
if [ -n "__REDIS_PASSWORD__" ]; then
    if ! redis-cli -p 6379 -a "__REDIS_PASSWORD__" --no-auth-warning ping 2>/dev/null | grep -q PONG; then
        pkill -x redis-server >/dev/null 2>&1 || true
        sleep 1
        redis-server --port 6379 --requirepass "__REDIS_PASSWORD__" --daemonize yes --appendonly no
        sleep 1
    fi
    echo "redis=$(redis-cli -p 6379 -a "__REDIS_PASSWORD__" --no-auth-warning ping 2>/dev/null | head -1)"
else
    echo "redis=skipped"
fi

echo "ssh=$(service ssh status >/dev/null 2>&1 && echo up || echo down)"
echo "docker=$(docker info >/dev/null 2>&1 && echo up || echo down)"
echo "containers=$(docker ps --format '{{.Names}}' | tr '\n' ',')"
'@
$bootstrap = $bootstrap.Replace('__REDIS_PASSWORD__', $RedisPassword)
$boot = Invoke-WslRoot $bootstrap
$boot | ForEach-Object { if ($_) { Write-Host "    $_" } }
Add-Result 'sshd'  ($boot -match 'ssh=up')    'inside WSL'
Add-Result 'dockerd' ($boot -match 'docker=up') 'inside WSL'
if ($RedisPassword) {
    Add-Result 'redis-ping' ($boot -match 'redis=PONG') 'inside WSL'
} else {
    Write-Host '  [SKIP] redis-ping - set CS_REDIS_PASSWORD to verify Redis' -ForegroundColor Yellow
}
Add-Result 'containers' ($boot -match 'minio') 'minio / rocketmq running'

Write-Step "3) RocketMQ cluster + topic"
$clusterScript = @'
count=$(docker exec rocketmq-broker sh -c "cd /home/rocketmq/rocketmq-5.3.1/bin && sh mqadmin clusterList -n rocketmq-namesrv:9876 2>/dev/null" | grep -c DefaultCluster)
echo "cluster=$count"
if [ "$count" -eq 0 ]; then
    echo "broker has no route to the name server"
    exit 9
fi
docker exec rocketmq-broker sh -c "cd /home/rocketmq/rocketmq-5.3.1/bin && sh mqadmin updateTopic -n rocketmq-namesrv:9876 -c DefaultCluster -t cloud-storage-processing -r 8 -w 8" >/dev/null 2>&1
topics=$(docker exec rocketmq-broker sh -c "cd /home/rocketmq/rocketmq-5.3.1/bin && sh mqadmin topicList -n rocketmq-namesrv:9876 2>/dev/null" | grep -c cloud-storage-processing)
echo "topic=$topics"
'@
$cluster = Invoke-WslRoot $clusterScript
$cluster | ForEach-Object { if ($_) { Write-Host "    $_" } }

if ($cluster -notmatch 'cluster=[1-9]') {
    if ($Repair) {
        Write-Host "  repairing: recreating rocketmq-broker on network rocketmq-net" -ForegroundColor Yellow
        $repairScript = @'
set -e
docker network create rocketmq-net >/dev/null 2>&1 || true
docker network connect rocketmq-net rocketmq-namesrv >/dev/null 2>&1 || true
docker stop rocketmq-broker >/dev/null 2>&1 || true
docker rm rocketmq-broker >/dev/null 2>&1 || true
docker run -d --name rocketmq-broker --restart always --network rocketmq-net \
  -p 10909:10909 -p 10911:10911 -p 10912:10912 \
  -v /data/rocketmq/broker/logs:/root/logs \
  -v /data/rocketmq/broker/store:/root/store \
  -v /data/rocketmq/conf/broker.conf:/home/rocketmq/rocketmq-5.3.1/conf/broker.conf \
  -e NAMESRV_ADDR=rocketmq-namesrv:9876 \
  apache/rocketmq:5.3.1 sh mqbroker -c /home/rocketmq/rocketmq-5.3.1/conf/broker.conf >/dev/null
sleep 20
docker exec rocketmq-broker sh -c "cd /home/rocketmq/rocketmq-5.3.1/bin && sh mqadmin updateTopic -n rocketmq-namesrv:9876 -c DefaultCluster -t cloud-storage-processing -r 8 -w 8" >/dev/null 2>&1
echo "repaired"
'@
        $repaired = Invoke-WslRoot $repairScript
        Write-Host "    $repaired"
        $cluster = Invoke-WslRoot $clusterScript
        $cluster | ForEach-Object { if ($_) { Write-Host "    $_" } }
    }
}
Add-Result 'rocketmq-cluster' ($cluster -match 'cluster=[1-9]') 'broker registered on name server'
Add-Result 'rocketmq-topic'   ($cluster -match 'topic=[1-9]')   'cloud-storage-processing exists'

Write-Step "4) Windows side: MySQL service + ports"
$mysqlService = Get-Service -Name 'MySQL' -ErrorAction SilentlyContinue
Add-Result 'mysql-service' ($null -ne $mysqlService -and $mysqlService.Status -eq 'Running') `
    $(if ($mysqlService) { $mysqlService.Status.ToString() } else { 'service not found' })

foreach ($port in 3306, 6379, 9000, 9876, 10911) {
    $ok = Wait-Port $port $PortTimeoutSeconds
    Add-Result "port-$port" $ok $(if ($ok) { 'reachable from Windows' } else { "not reachable after ${PortTimeoutSeconds}s" })
}

Write-Step "5) MinIO health endpoint"
try {
    $response = Invoke-WebRequest -Uri 'http://127.0.0.1:9000/minio/health/live' -UseBasicParsing -TimeoutSec 10
    Add-Result 'minio-health' ($response.StatusCode -eq 200) "HTTP $($response.StatusCode)"
} catch {
    Add-Result 'minio-health' $false $_.Exception.Message
}

Write-Step "summary"
$failed = @($results | Where-Object { -not $_.Ok })
$results | Format-Table -AutoSize Name, Ok, Detail | Out-String | Write-Host
if ($failed.Count -eq 0) {
    Write-Host "ENVIRONMENT READY - next: start the app, then run scripts\smoke-upload.ps1" -ForegroundColor Green
    exit 0
}
Write-Host ("NOT READY: {0} check(s) failed. Fix them and re-run this script." -f $failed.Count) -ForegroundColor Red
Write-Host "Hint: if rocketmq-cluster failed, re-run with -Repair" -ForegroundColor Yellow
exit 1
