<#
.SYNOPSIS
    Stress suite: rate limiter QPS, quota concurrency, cache hit rate, MQ peak/drain.
.DESCRIPTION
    The backend must already be running on 8080 (see deployment.md step 4).
    The QPS and cache runs need the limiter thresholds raised, otherwise the
    requests are rejected with 429 long before the run ends:

      java -jar cloud-storage-web\target\cloud-storage-web.jar --spring.profiles.active=dev `
        --app.limiter.scenes.file-meta.user-limit=100000000 `
        --app.limiter.scenes.file-meta.device-limit=100000000

    Usage:
      powershell -ExecutionPolicy Bypass -File scripts\stress\run-stress.ps1
      powershell -ExecutionPolicy Bypass -File scripts\stress\run-stress.ps1 -SkipMq

    NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI
    (code page 936 here), so non-ASCII literals break the parser.
#>
param(
    [string]$BaseUrl = 'http://localhost:8080/api/v1',
    [long]$UserId = 1,
    [string]$DeviceId = 'stress-device',
    [int]$QpsConnections = 64,
    [int]$QpsSeconds = 15,
    [int]$CacheRequests = 2000,
    [int]$QuotaConcurrency = 100,
    [int]$MqTasks = 3000,
    [switch]$SkipMq,
    [string]$MysqlExe = 'D:\MySQL\mysql-9.6.0-winx64\bin\mysql.exe',
    [string]$MysqlPassword = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if ([string]::IsNullOrEmpty($MysqlPassword)) {
    if ($env:CS_MYSQL_PASSWORD) { $MysqlPassword = $env:CS_MYSQL_PASSWORD } else { $MysqlPassword = '123456' }
}

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
try {
    Write-Host "`n########## 1/4 rate-limited read QPS ##########" -ForegroundColor Cyan
    node scripts/stress/limiter-qps.mjs --base-url $BaseUrl --user-id $UserId --device-id $DeviceId `
        --connections $QpsConnections --duration $QpsSeconds --label limiter-on

    Write-Host "`n########## 2/4 quota concurrency ##########" -ForegroundColor Cyan
    node scripts/stress/quota-concurrency.mjs --base-url $BaseUrl --device-id $DeviceId `
        --concurrency $QuotaConcurrency --mysql-exe $MysqlExe --mysql-password $MysqlPassword

    Write-Host "`n########## 3/4 cache hit rate ##########" -ForegroundColor Cyan
    node scripts/stress/cache-hitrate.mjs --base-url $BaseUrl --user-id $UserId --device-id $DeviceId `
        --requests $CacheRequests

    if (-not $SkipMq) {
        Write-Host "`n########## 4/4 MQ peak / drain ##########" -ForegroundColor Cyan
        node scripts/stress/mq-peak.mjs --base-url $BaseUrl --device-id $DeviceId --tasks $MqTasks `
            --mysql-exe $MysqlExe --mysql-password $MysqlPassword
    } else {
        Write-Host "`n########## 4/4 MQ peak / drain (skipped) ##########" -ForegroundColor DarkGray
    }

    Write-Host "`nSTRESS SUITE DONE" -ForegroundColor Green
} finally {
    Pop-Location
}
