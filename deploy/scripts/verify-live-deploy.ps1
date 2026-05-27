param(
    [Parameter(Mandatory = $true)]
    [string]$BackendHost,

    [string]$FrontendOrigin = ""
)

$ErrorActionPreference = "Stop"

if ($BackendHost.StartsWith("http://") -or $BackendHost.StartsWith("https://") -or $BackendHost.Contains("/")) {
    throw "BackendHost must be a host only, for example 'ssumcp.duckdns.org'. Received: $BackendHost"
}

$baseUrl = "https://$BackendHost"

Write-Host "Checking backend health..."
curl.exe -i "$baseUrl/actuator/health"

Write-Host ""
Write-Host "Checking REST API..."
curl.exe "$baseUrl/api/meals/today"

if ($FrontendOrigin) {
    Write-Host ""
    Write-Host "Checking CORS allowlist for frontend origin..."
    curl.exe -i -H "Origin: $FrontendOrigin" "$baseUrl/api/meals/today"
}

Write-Host ""
Write-Host "Checking MCP Streamable HTTP initialize response..."
$initializeRequest = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"deploy-verify","version":"1.0.0"}}}'
curl.exe -i -X POST "$baseUrl/mcp" `
    -H "Content-Type: application/json" `
    -H "Accept: application/json, text/event-stream" `
    --data-raw $initializeRequest
