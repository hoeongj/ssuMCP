param(
    [Parameter(Mandatory = $true)]
    [string]$BackendHost,

    [Parameter(Mandatory = $true)]
    [string]$FrontendOrigin,

    [Parameter(Mandatory = $true)]
    [string]$OperatorEmail,

    [string]$Image = "ghcr.io/hoeongj/ssumcp:latest",

    [string]$ChartDir = "deploy/charts/ssuai-backend",

    [string]$ClusterIssuerTemplate = "deploy/cluster-bootstrap/clusterissuer.yaml",

    [string]$OutputDir = "deploy/generated/gitops-breakglass",

    [string]$HelmExecutable = "helm"
)

$ErrorActionPreference = "Stop"

function Require-NoTrailingSlash {
    param(
        [string]$Name,
        [string]$Value
    )

    if ($Value.EndsWith("/")) {
        throw "$Name must not end with '/': $Value"
    }
}

function Require-HostOnly {
    param([string]$CheckHost)

    if ($CheckHost.StartsWith("http://") -or $CheckHost.StartsWith("https://") -or $CheckHost.Contains("/")) {
        throw "BackendHost must be a host only, for example 'ssumcp.duckdns.org'. Received: $CheckHost"
    }
}

function Split-TaggedImage {
    param([string]$TaggedImage)

    $lastSlash = $TaggedImage.LastIndexOf("/")
    $lastColon = $TaggedImage.LastIndexOf(":")

    if ($lastColon -le $lastSlash -or $lastColon -eq ($TaggedImage.Length - 1)) {
        throw "Image must include a tag, for example ghcr.io/hoeongj/ssumcp:sha-<full-sha>. Received: $TaggedImage"
    }

    return @{
        Repository = $TaggedImage.Substring(0, $lastColon)
        Tag = $TaggedImage.Substring($lastColon + 1)
    }
}

Require-HostOnly -CheckHost $BackendHost
Require-NoTrailingSlash -Name "FrontendOrigin" -Value $FrontendOrigin

if (-not (Test-Path $ChartDir)) {
    throw "ChartDir not found: $ChartDir"
}

if (-not (Test-Path $ClusterIssuerTemplate)) {
    throw "ClusterIssuerTemplate not found: $ClusterIssuerTemplate"
}

if (-not (Get-Command $HelmExecutable -ErrorAction SilentlyContinue)) {
    throw "Helm executable was not found: $HelmExecutable"
}

$imageParts = Split-TaggedImage -TaggedImage $Image
$tlsSecretName = (($BackendHost -replace "[^A-Za-z0-9-]", "-") + "-tls")

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$clusterIssuer = Get-Content -Raw -Encoding UTF8 $ClusterIssuerTemplate
$clusterIssuer = $clusterIssuer.Replace("REPLACE_WITH_OPERATOR_EMAIL@example.com", $OperatorEmail)
Set-Content -Encoding UTF8 -NoNewline -Path (Join-Path $OutputDir "clusterissuer.yaml") -Value $clusterIssuer

$helmArgs = @(
    "template",
    "ssuai-backend",
    $ChartDir,
    "--namespace",
    "ssuai-prod",
    "--set-string",
    "image.repository=$($imageParts.Repository)",
    "--set-string",
    "image.tag=$($imageParts.Tag)",
    "--set-string",
    "env.frontendOrigin=$FrontendOrigin",
    "--set-string",
    "ingress.host=$BackendHost",
    "--set-string",
    "ingress.tlsSecretName=$tlsSecretName"
)

$rendered = & $HelmExecutable @helmArgs 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "helm template failed: $($rendered -join [Environment]::NewLine)"
}

Set-Content -Encoding UTF8 -Path (Join-Path $OutputDir "backend.yaml") -Value ($rendered -join [Environment]::NewLine)

Write-Host "Generated break-glass deploy manifests:"
Write-Host "- $OutputDir"
Write-Host ""
Write-Host "Backend MCP endpoint:"
Write-Host "https://$BackendHost/mcp"
Write-Host ""
Write-Host "Next step after kubectl is connected:"
Write-Host "powershell -ExecutionPolicy Bypass -File deploy/scripts/apply-live-deploy.ps1 -ManifestDir $OutputDir"
