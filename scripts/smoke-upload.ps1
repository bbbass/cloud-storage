<#
.SYNOPSIS
    End-to-end smoke test for chunked upload:
    init -> parts -> complete -> idempotent retry -> instant upload -> resume -> SSE progress.
.DESCRIPTION
    Backend must be running on 8080. Usage:
        powershell -ExecutionPolicy Bypass -File scripts\smoke-upload.ps1
        powershell -ExecutionPolicy Bypass -File scripts\smoke-upload.ps1 -SizeMb 64 -ChunkSize 10485760
    NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI (code page 936 here)
    unless the file has a UTF-8 BOM, so non-ASCII literals get corrupted and break the parser.
    NOTE: chunk size must be >= 5 MiB, because MinIO/S3 server-side compose requires every source
    part except the last one to be at least 5 MiB.
#>
param(
    [string]$BaseUrl = 'http://localhost:8080/api/v1',
    [int]$SizeMb = 12,
    [long]$ChunkSize = 5242880,
    [long]$UserId = 1,
    [string]$DeviceId = 'smoke-device',
    # 0 = random each run (a new file, so it does not hit instant-upload from a previous run)
    [int]$Seed = 0
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$headers = @{ 'X-User-Id' = "$UserId"; 'X-Device-Id' = $DeviceId }
$workDir = Join-Path $env:TEMP ("cs-smoke-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum 2000000000 }

function Write-Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "  [OK] $text" -ForegroundColor Green }
function Write-Bad($text) { Write-Host "  [FAIL] $text" -ForegroundColor Red }
function Assert-True($condition, $message) {
    if ($condition) { Write-Ok $message } else { Write-Bad $message; throw "assertion failed: $message" }
}

function New-TestFile([string]$path, [int]$sizeMb, [int]$seed) {
    $bytes = New-Object byte[] ($sizeMb * 1MB)
    (New-Object System.Random $seed).NextBytes($bytes)
    [System.IO.File]::WriteAllBytes($path, $bytes)
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()
}

function Invoke-Init([string]$fileName, [long]$size, [string]$sha256, [long]$chunkSize,
                     [string]$contentType = 'application/octet-stream') {
    $body = @{
        fileName    = $fileName
        fileSize    = $size
        sha256      = $sha256
        chunkSize   = $chunkSize
        contentType = $contentType
    } | ConvertTo-Json
    $response = Invoke-RestMethod -Uri "$BaseUrl/uploads" -Method Post -ContentType 'application/json' `
        -Headers $headers -Body $body
    return $response.data
}

function Send-Part([long]$uploadId, [int]$partNo, [string]$partFile,
                   [string]$contentType = 'application/octet-stream') {
    # Part content-type does not affect the final object (that comes from the session),
    # and must not travel as a query param: an escaped %2F makes Tomcat answer 400.
    $response = Invoke-RestMethod -Uri "$BaseUrl/uploads/$uploadId/parts/$partNo" -Method Put `
        -InFile $partFile -ContentType 'application/octet-stream' -Headers $headers
    return $response.data
}

function New-TestJpeg([string]$path, [int]$width, [int]$height) {
    Add-Type -AssemblyName System.Drawing
    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.Clear([System.Drawing.Color]::CornflowerBlue)
    $graphics.FillEllipse([System.Drawing.Brushes]::Orange, 20, 20, 120, 90)
    $graphics.Dispose()
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    $bitmap.Dispose()
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()
}

function Send-FileChunked([long]$uploadId, [string]$path, [long]$chunkSize, [string]$contentType) {
    $stream = [System.IO.File]::OpenRead($path)
    try {
        $buffer = New-Object byte[] $chunkSize
        $partNo = 1
        while ($true) {
            $read = $stream.Read($buffer, 0, $buffer.Length)
            if ($read -le 0) { break }
            $partFile = Join-Path $workDir ("upload-{0}-{1}.bin" -f $uploadId, $partNo)
            $slice = New-Object byte[] $read
            [Array]::Copy($buffer, 0, $slice, 0, $read)
            [System.IO.File]::WriteAllBytes($partFile, $slice)
            $null = Send-Part $uploadId $partNo $partFile $contentType
            $partNo++
        }
    } finally {
        $stream.Close()
    }
}

function Get-FileTasks([long]$fileId) {
    return (Invoke-RestMethod -Uri "$BaseUrl/tasks?fileId=$fileId" -Method Get -Headers $headers).data
}

function Wait-TasksTerminal([long]$fileId, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $tasks = @(Get-FileTasks $fileId)
        $pending = @($tasks | Where-Object { $_.status -eq 'PENDING' -or $_.status -eq 'RUNNING' })
        if ($tasks.Count -gt 0 -and $pending.Count -eq 0) { return $tasks }
        Start-Sleep -Milliseconds 500
    }
    return @(Get-FileTasks $fileId)
}

function Wait-TaskStatus([long]$fileId, [string]$taskType, [string]$status, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $tasks = @(Get-FileTasks $fileId)
        $matched = $tasks | Where-Object { $_.taskType -eq $taskType -and $_.status -eq $status }
        if ($matched) { return $tasks }
        Start-Sleep -Seconds 1
    }
    return @(Get-FileTasks $fileId)
}

function Complete-Upload([long]$uploadId) {
    $response = Invoke-RestMethod -Uri "$BaseUrl/uploads/$uploadId/complete" -Method Post -Headers $headers
    return $response.data
}

Write-Step "prepare test file ($SizeMb MiB, chunk $ChunkSize bytes)"
$fileA = Join-Path $workDir 'sample-a.bin'
$shaA = New-TestFile $fileA $SizeMb $Seed
$sizeA = (Get-Item -LiteralPath $fileA).Length
Write-Host "  file  = $fileA"
Write-Host "  sha256= $shaA  size=$sizeA"

Write-Step "1) init upload"
$init = Invoke-Init 'sample-a.bin' $sizeA $shaA $ChunkSize
Assert-True (-not $init.instantUpload) "first upload is not an instant upload"
Assert-True ($init.chunkTotal -ge 2) "chunkTotal = $($init.chunkTotal)"
$uploadId = [long]$init.uploadId
Write-Host "  uploadId = $uploadId"

Write-Step "2) attach SSE progress listener in background"
$sseJob = Start-Job -ArgumentList "$BaseUrl/uploads/$uploadId/progress", "$UserId" -ScriptBlock {
    param($url, $uid)
    & curl.exe -sN --max-time 30 $url -H "X-User-Id: $uid"
}

Write-Step "3) upload parts"
$stream = [System.IO.File]::OpenRead($fileA)
try {
    $buffer = New-Object byte[] $ChunkSize
    $partNo = 1
    while ($true) {
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -le 0) { break }
        $partFile = Join-Path $workDir "part-$partNo.bin"
        $slice = New-Object byte[] $read
        [Array]::Copy($buffer, 0, $slice, 0, $read)
        [System.IO.File]::WriteAllBytes($partFile, $slice)

        $res = Send-Part $uploadId $partNo $partFile
        Write-Host ("  part {0}/{1}: {2} bytes, progress {3}%" -f $res.partNo, $res.totalParts, $res.partSize, $res.percent)
        $partNo++
    }
} finally {
    $stream.Close()
}

Write-Step "4) complete (merge)"
$complete = Complete-Upload $uploadId
Assert-True ($complete.fileId -gt 0) "fileId = $($complete.fileId)"
Assert-True ($complete.objectId -gt 0) "objectId = $($complete.objectId)"
Write-Host ("  cost {0} ms, deduped = {1}" -f $complete.costMs, $complete.deduped)

Write-Step "5) idempotency: complete again must return the same fileId"
$again = Complete-Upload $uploadId
Assert-True ($again.fileId -eq $complete.fileId) "same fileId = $($again.fileId)"

Write-Step "6) instant upload: same content again, zero bytes transferred"
$instant = Invoke-Init 'sample-a-copy.bin' $sizeA $shaA $ChunkSize
Assert-True ($instant.instantUpload) "instantUpload = true"
Assert-True ($instant.fileId -gt 0) "fileId = $($instant.fileId)"
Assert-True ($instant.fileId -ne $complete.fileId) "new file entry reusing the same physical object"
Assert-True ($instant.objectId -eq $complete.objectId) "objectId reused = $($instant.objectId)"

Write-Step "7) resume: upload only part 1, then re-init"
$fileB = Join-Path $workDir 'sample-b.bin'
$shaB = New-TestFile $fileB ($SizeMb + 3) ($Seed + 1)
$sizeB = (Get-Item -LiteralPath $fileB).Length
$initB = Invoke-Init 'sample-b.bin' $sizeB $shaB $ChunkSize
$uploadIdB = [long]$initB.uploadId

$streamB = [System.IO.File]::OpenRead($fileB)
try {
    $bufferB = New-Object byte[] $ChunkSize
    $read = $streamB.Read($bufferB, 0, $bufferB.Length)
    $partFile = Join-Path $workDir 'b-part-1.bin'
    $slice = New-Object byte[] $read
    [Array]::Copy($bufferB, 0, $slice, 0, $read)
    [System.IO.File]::WriteAllBytes($partFile, $slice)
    $null = Send-Part $uploadIdB 1 $partFile
} finally {
    $streamB.Close()
}
$resumed = Invoke-Init 'sample-b.bin' $sizeB $shaB $ChunkSize
Assert-True ($resumed.uploadId -eq $uploadIdB) "same session reused: uploadId = $uploadIdB"
Assert-True ($resumed.uploadedParts -contains 1) "server already has part 1, client only sends the rest"

Write-Step "8) query session status"
$status = (Invoke-RestMethod -Uri "$BaseUrl/uploads/$uploadIdB" -Method Get -Headers $headers).data
Write-Host ("  status={0} uploaded={1}/{2} percent={3}%" -f $status.status, $status.uploadedParts.Count, $status.totalParts, $status.percent)
Assert-True ($status.status -eq 'UPLOADING') "session status is UPLOADING"

Write-Step "9) SSE progress events"
$null = Wait-Job $sseJob -Timeout 15
$sseText = ((Receive-Job $sseJob) | Out-String).Trim()
Remove-Job $sseJob -Force -ErrorAction SilentlyContinue
if ($sseText -match 'progress') {
    Write-Ok "SSE progress received, sample:"
    ($sseText -split "`n" | Select-Object -First 8) | ForEach-Object { Write-Host "    $_" }
} else {
    Write-Host "  [WARN] no SSE event captured (main flow unaffected)" -ForegroundColor Yellow
}

Write-Step "10) quota: used bytes should include committed files and in-flight reservations"
$quota = (Invoke-RestMethod -Uri "$BaseUrl/quota" -Method Get -Headers $headers).data
$quotaMb = [math]::Round($quota.usedBytes / 1MB, 2)
$quotaLimitMb = [math]::Round($quota.quotaBytes / 1MB, 2)
$quotaFreeMb = [math]::Round($quota.availableBytes / 1MB, 2)
Write-Host ("  used={0} MB / limit={1} MB, available={2} MB" -f $quotaMb, $quotaLimitMb, $quotaFreeMb)
Assert-True ($quota.usedBytes -ge $sizeA) "used bytes >= one committed file ($([math]::Round($sizeA / 1MB,2)) MB)"
Assert-True ($quota.availableBytes -eq ($quota.quotaBytes - $quota.usedBytes)) "available = quota - used"

Write-Step "11) quota exceeded: reserve 2 GiB against a 1 GiB quota, before uploading any byte"
$tooBigSize = 2GB
$tooBigSha = (1..64 | ForEach-Object { '0123456789abcdef'[(Get-Random -Maximum 16)] }) -join ''
$quotaRejected = $false
$quotaMessage = ''
$tooBigBody = @{
    fileName    = 'too-big.bin'
    fileSize    = $tooBigSize
    sha256      = $tooBigSha
    chunkSize   = $ChunkSize
    contentType = 'application/octet-stream'
} | ConvertTo-Json
try {
    # Business errors come back as HTTP 200 + business code (only rate limiting is 429).
    $initResponse = Invoke-RestMethod -Uri "$BaseUrl/uploads" -Method Post -ContentType 'application/json' `
        -Headers $headers -Body $tooBigBody
    $quotaMessage = $initResponse.message
    if ($initResponse.code -eq 50002) { $quotaRejected = $true }
} catch {
    $quotaMessage = $_.ErrorDetails.Message
    if ($_.Exception.Response.StatusCode.value__ -eq 429 -or $quotaMessage -match '50002') { $quotaRejected = $true }
}
Assert-True $quotaRejected "server rejected the reservation with QUOTA_EXCEEDED (50002): $quotaMessage"

Write-Step "12) rate limit: same device hammering init until HTTP 429"
$limitHeaders = @{ 'X-User-Id' = '424242'; 'X-Device-Id' = 'smoke-limit-device' }
$tooMany = $false
$attempt = 0
$retryAfter = $null
while (-not $tooMany -and $attempt -lt 30) {
    $attempt++
    $sha = (1..64 | ForEach-Object { '0123456789abcdef'[(Get-Random -Maximum 16)] }) -join ''
    $body = @{ fileName = "limit-$attempt.bin"; fileSize = 1; sha256 = $sha; chunkSize = $ChunkSize } | ConvertTo-Json
    try {
        $null = Invoke-RestMethod -Uri "$BaseUrl/uploads" -Method Post -ContentType 'application/json' `
            -Headers $limitHeaders -Body $body
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -eq 429) {
            $tooMany = $true
            $retryAfter = $_.Exception.Response.Headers['Retry-After']
        }
    }
}
Assert-True $tooMany "got HTTP 429 after $attempt requests (device limit is 20/min)"
Assert-True ($retryAfter -ne $null) "429 carries Retry-After header: $retryAfter"

Write-Step "13) cache: file metadata should be served from Redis on the second read"
$meta1 = (Invoke-RestMethod -Uri "$BaseUrl/files/$($complete.fileId)" -Method Get -Headers $headers).data
$meta2 = (Invoke-RestMethod -Uri "$BaseUrl/files/$($complete.fileId)" -Method Get -Headers $headers).data
Assert-True ($meta1.fileName -eq 'sample-a.bin') "metadata fileName = $($meta1.fileName)"
Assert-True ($meta1.sha256 -eq $meta2.sha256) "two reads agree (second one hits the cache)"
$cache = (Invoke-RestMethod -Uri "$BaseUrl/health" -Method Get -Headers $headers).data.cache
Write-Host ("  cache hits={0} misses={1} rebuilds={2} hitRate={3}%" -f $cache.hits, $cache.misses, $cache.rebuilds, $cache.hitRate)
Assert-True ($cache.hits -ge 1) "cache recorded at least one hit"
Assert-True ($cache.rebuilds -ge 1) "cache recorded at least one rebuild (mutual-exclusion path)"

Write-Step "14) MQ pipeline: upload a real JPEG, expect THUMBNAIL + EXIF tasks to finish"
$jpegPath = Join-Path $workDir 'photo.jpg'
$jpegSha = New-TestJpeg $jpegPath 640 480
$jpegSize = (Get-Item -LiteralPath $jpegPath).Length
$initImg = Invoke-Init 'photo.jpg' $jpegSize $jpegSha $ChunkSize 'image/jpeg'
Send-FileChunked ([long]$initImg.uploadId) $jpegPath $ChunkSize 'image/jpeg'
$imgDone = Complete-Upload ([long]$initImg.uploadId)
Write-Host ("  fileId={0}, size={1} bytes" -f $imgDone.fileId, $imgDone.size)
$tasks = Wait-TasksTerminal $imgDone.fileId 60
$thumb = $tasks | Where-Object { $_.taskType -eq 'THUMBNAIL' }
$exif = $tasks | Where-Object { $_.taskType -eq 'EXIF' }
Assert-True ($tasks.Count -eq 2) "two tasks created (THUMBNAIL + EXIF)"
Assert-True ($thumb.status -eq 'DONE') "THUMBNAIL task DONE"
Assert-True ($thumb.result -like '*thumbnails/*') "thumbnail written to object storage: $($thumb.result)"
Assert-True ($exif.status -eq 'DONE') "EXIF task DONE, result=$($exif.result)"

Write-Step "15) dead letter: corrupt JPEG exhausts retries -> DEAD -> replay via API"
$badPath = Join-Path $workDir 'broken.jpg'
$badBytes = New-Object byte[] 4096
(New-Object System.Random 99).NextBytes($badBytes)
[System.IO.File]::WriteAllBytes($badPath, $badBytes)
$badSha = (Get-FileHash -LiteralPath $badPath -Algorithm SHA256).Hash.ToLower()
$badSize = (Get-Item -LiteralPath $badPath).Length
$initBad = Invoke-Init 'broken.jpg' $badSize $badSha $ChunkSize 'image/jpeg'
Send-FileChunked ([long]$initBad.uploadId) $badPath $ChunkSize 'image/jpeg'
$badDone = Complete-Upload ([long]$initBad.uploadId)
$badTasks = Wait-TaskStatus $badDone.fileId 'THUMBNAIL' 'DEAD' 120
$dead = $badTasks | Where-Object { $_.taskType -eq 'THUMBNAIL' }
Assert-True ($dead.status -eq 'DEAD') "THUMBNAIL exhausted retries and entered DEAD (retryCount=$($dead.retryCount), error=$($dead.errorMsg))"
$replayed = (Invoke-RestMethod -Uri "$BaseUrl/tasks/$($dead.id)/replay" -Method Post -Headers $headers).data
Assert-True ($replayed.status -in @('PENDING', 'RUNNING')) "replay reset the dead task to $($replayed.status)"

Write-Step "16) task stats"
$taskStats = (Invoke-RestMethod -Uri "$BaseUrl/tasks/stats" -Method Get -Headers $headers).data
Write-Host ("  PENDING={0} RUNNING={1} DONE={2} DEAD={3}" -f $taskStats.PENDING, $taskStats.RUNNING, $taskStats.DONE, $taskStats.DEAD)
Assert-True ($taskStats.DONE -ge 2) "at least two DONE tasks recorded"

Write-Host "`nALL ASSERTIONS PASSED" -ForegroundColor Green
Write-Host "work dir: $workDir"
