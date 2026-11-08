#Requires -Version 7.0
<#
  PocketPlayer 音轨预处理脚本（Windows / PowerShell 7，UTF-8）
  把 AC3 / DTS / TrueHD 等设备常见不支持的音轨转成 AAC，视频轨直接 copy，
  不重编码、不改动原文件，输出同目录的「原名.aac.mp4」。拷回手机即可带声播放。

  用法（不需要管理员权限）：
    pwsh -ExecutionPolicy Bypass -File .\prepare_videos.ps1 -Path "D:\Videos"
    pwsh -ExecutionPolicy Bypass -File .\prepare_videos.ps1 -Path "D:\Videos" -DryRun

  依赖 ffmpeg / ffprobe（缺失时脚本会提示安装命令）。
  注意：输出为 MP4，字幕轨会丢弃；VP9 / AV1 等无法直接封装进 MP4 的视频轨会失败并跳过。
#>
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$extensions = @('.mp4', '.mkv', '.mov', '.m4v', '.ts', '.m2ts', '.avi', '.flv', '.webm')
$supportedAudio = @('aac', 'mp3', 'opus', 'vorbis', 'flac', 'alac', 'ac4')

$ffprobe = Get-Command ffprobe -ErrorAction SilentlyContinue
$ffmpeg = Get-Command ffmpeg -ErrorAction SilentlyContinue
if (-not $ffprobe -or -not $ffmpeg) {
    Write-Host '缺少 ffmpeg / ffprobe，请先安装：winget install --id Gyan.FFmpeg -e' -ForegroundColor Yellow
    exit 1
}
if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
    Write-Host "目录不存在：$Path" -ForegroundColor Red
    exit 1
}

$files = @(Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $extensions -contains $_.Extension.ToLowerInvariant() -and $_.Name -notlike '*.aac.mp4' })
Write-Host "扫描到 $($files.Count) 个视频" -ForegroundColor Cyan

$converted = 0
foreach ($f in $files) {
    $out = Join-Path $f.DirectoryName ($f.BaseName + '.aac.mp4')
    if (Test-Path -LiteralPath $out) {
        Write-Host "跳过（输出已存在）：$($f.Name)"
        continue
    }
    try {
        $raw = & $ffprobe.Source -v error -select_streams a -show_entries stream=codec_name -of csv=p=0 $f.FullName 2>$null
        $audioCodecs = @($raw | Where-Object { $_ -and $_.Trim() } | ForEach-Object { $_.Trim().ToLowerInvariant() })
        if ($audioCodecs.Count -eq 0) {
            continue
        }
        $unsupported = @($audioCodecs | Where-Object { $supportedAudio -notcontains $_ })
        if ($unsupported.Count -eq 0) {
            continue
        }
        Write-Host "需要转换：$($f.Name)  音轨 $($audioCodecs -join ',')" -ForegroundColor Yellow
        if ($DryRun) {
            continue
        }
        & $ffmpeg.Source -hide_banner -loglevel error -y -i $f.FullName -map 0:v -map 0:a -c:v copy -c:a aac -b:a 192k -sn -movflags +faststart $out
        if ($LASTEXITCODE -ne 0) {
            Write-Host "失败（视频轨可能无法封装进 MP4）：$($f.Name)" -ForegroundColor Red
            continue
        }
        $converted++
        Write-Host "完成：$([System.IO.Path]::GetFileName($out))" -ForegroundColor Green
    }
    catch {
        Write-Host "处理出错 $($f.Name)：$($_.Exception.Message)" -ForegroundColor Red
    }
}
Write-Host "共转换 $converted 个文件（原文件均未改动）" -ForegroundColor Cyan
