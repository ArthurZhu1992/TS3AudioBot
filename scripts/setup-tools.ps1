param(
    [string]$Root = (Get-Location).Path,
    [switch]$Force,
    [switch]$SkipFfmpeg,
    [switch]$SkipYtDlp
)

$ErrorActionPreference = "Stop"

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch {
    # Ignore on newer PowerShell where this is unnecessary.
}

$ffmpegDir = Join-Path $Root "ffmpeg"
$ytDir = Join-Path $Root "yt-dlp"

New-Item -ItemType Directory -Force -Path $ffmpegDir | Out-Null
New-Item -ItemType Directory -Force -Path $ytDir | Out-Null

if (-not $SkipFfmpeg) {
    $ffmpegUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
    $ffmpegArchive = Join-Path $ffmpegDir "ffmpeg-windows-amd64.zip"
    if ($Force -or -not (Test-Path $ffmpegArchive)) {
        Write-Host "Downloading FFmpeg..."
        Invoke-WebRequest -Uri $ffmpegUrl -OutFile $ffmpegArchive
    } else {
        Write-Host "FFmpeg archive already exists: $ffmpegArchive"
    }
}

if (-not $SkipYtDlp) {
    $ytDlpUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
    $ytDlpExe = Join-Path $ytDir "yt-dlp.exe"
    if ($Force -or -not (Test-Path $ytDlpExe)) {
        Write-Host "Downloading yt-dlp..."
        Invoke-WebRequest -Uri $ytDlpUrl -OutFile $ytDlpExe
    } else {
        Write-Host "yt-dlp already exists: $ytDlpExe"
    }
}

Write-Host "Done."
