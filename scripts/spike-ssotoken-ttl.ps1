# spike-ssotoken-ttl.ps1
#
# Measures how long an oasis.ssu.ac.kr Pyxis-Auth-Token stays valid by
# polling /pyxis-api/1/seat-rooms at a fixed interval until it returns
# the `needLogin` auth error.
#
# Why this exists: the Pyxis SPA on oasis.ssu.ac.kr authenticates API
# calls with a `Pyxis-Auth-Token` request header (NOT a Cookie). The
# library upstream's TTL drives PR 13c's storage policy decision —
# multi-day TTL → simple persistent store; sub-hour sliding TTL → need
# refresh / re-paste UX.
#
# Usage (PowerShell):
#   $env:OASIS_PYXIS_TOKEN = "<paste captured Pyxis-Auth-Token value>"
#   .\scripts\spike-ssotoken-ttl.ps1
#
# The legacy var name OASIS_SSOTOKEN is also accepted for back-compat
# with earlier runs, but new captures should use OASIS_PYXIS_TOKEN.
#
# Optional:
#   $env:OASIS_TTL_INTERVAL_SEC = "300"          # default 300 (5 min)
#   $env:OASIS_TTL_LOG = "scripts/ssotoken-ttl.log"
#   $env:OASIS_TTL_URL = "<override probe URL>"  # rarely needed
#
# Output: per-poll line to console + log file. On expiry, prints
# duration and exits. The log file matches *.log so it is gitignored.

$token = if ($env:OASIS_PYXIS_TOKEN) { $env:OASIS_PYXIS_TOKEN } else { $env:OASIS_SSOTOKEN }
if (-not $token) {
    Write-Error "OASIS_PYXIS_TOKEN env var is required. Capture it from devtools: any /pyxis-api/* request -> Request Headers -> Pyxis-Auth-Token."
    exit 1
}

$intervalSec = if ($env:OASIS_TTL_INTERVAL_SEC) { [int]$env:OASIS_TTL_INTERVAL_SEC } else { 300 }
$logPath = if ($env:OASIS_TTL_LOG) { $env:OASIS_TTL_LOG } else { "scripts/ssotoken-ttl.log" }
$url = if ($env:OASIS_TTL_URL) { $env:OASIS_TTL_URL } else { "https://oasis.ssu.ac.kr/pyxis-api/1/seat-rooms?smufMethodCode=PC&branchGroupId=1" }
$referer = "https://oasis.ssu.ac.kr/library-services/smuf/reading-rooms"
$userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

# Don't echo the token; only its fingerprint.
$tokenBytes = [System.Text.Encoding]::UTF8.GetBytes($token)
$sha = [System.Security.Cryptography.SHA256]::Create()
$fingerprint = ([System.BitConverter]::ToString($sha.ComputeHash($tokenBytes)) -replace '-','').Substring(0,8).ToLower()

$startedAt = Get-Date
$startedLine = "started=$($startedAt.ToString('o')) fingerprint=$fingerprint interval=${intervalSec}s url=$url"
Write-Host $startedLine
Add-Content -Path $logPath -Value $startedLine

function ConvertTo-Utf8String {
    param($content)
    if ($null -eq $content) { return "" }
    if ($content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($content)
    }
    return [string]$content
}

$pollCount = 0
while ($true) {
    $pollCount++
    $now = Get-Date
    $ts = $now.ToString('o')
    try {
        $headers = @{
            "Pyxis-Auth-Token" = $token
            "Accept"           = "application/json, text/plain, */*"
            "Accept-Language"  = "ko"
            "Referer"          = $referer
            "User-Agent"       = $userAgent
        }
        $resp = Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing -ErrorAction Stop
        # PS 5.1 returns Content as byte[] for some content types; PS 7 as
        # string. Normalize so the substring check is always against a string.
        $body = ConvertTo-Utf8String $resp.Content
        if ($body -match 'needLogin') {
            $elapsed = [int]($now - $startedAt).TotalSeconds
            $line = "$ts poll=$pollCount status=expired elapsed=${elapsed}s"
            Write-Host $line -ForegroundColor Red
            Add-Content -Path $logPath -Value $line
            Write-Host ""
            Write-Host "===== TTL measurement complete ====="
            Write-Host "Token died after $elapsed seconds ($([math]::Round($elapsed/3600, 2)) hours)."
            Write-Host "Log: $logPath"
            break
        }
        $line = "$ts poll=$pollCount status=ok bodylen=$($body.Length)"
        Write-Host $line
        Add-Content -Path $logPath -Value $line
    }
    catch {
        $line = "$ts poll=$pollCount status=error msg=$($_.Exception.Message)"
        Write-Host $line -ForegroundColor Yellow
        Add-Content -Path $logPath -Value $line
    }
    Start-Sleep -Seconds $intervalSec
}
