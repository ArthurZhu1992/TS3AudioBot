#!/usr/bin/env bash
set -euo pipefail

root="${1:-$(pwd)}"
force=0
skip_ffmpeg=0
skip_ytdlp=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force) force=1 ;;
    --skip-ffmpeg) skip_ffmpeg=1 ;;
    --skip-ytdlp) skip_ytdlp=1 ;;
    *) root="$1" ;;
  esac
  shift
done

ffmpeg_dir="${root}/ffmpeg"
yt_dir="${root}/yt-dlp"

mkdir -p "${ffmpeg_dir}" "${yt_dir}"

download() {
  local url="$1"
  local out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -L -o "${out}" "${url}"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "${out}" "${url}"
  else
    echo "Error: curl or wget is required."
    exit 1
  fi
}

if [[ "${skip_ffmpeg}" -eq 0 ]]; then
  arch="$(uname -m)"
  if [[ "${arch}" == "x86_64" || "${arch}" == "amd64" ]]; then
    ffmpeg_url="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
    ffmpeg_archive="${ffmpeg_dir}/ffmpeg-linux-amd64.tar.xz"
  elif [[ "${arch}" == "aarch64" || "${arch}" == "arm64" ]]; then
    ffmpeg_url="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz"
    ffmpeg_archive="${ffmpeg_dir}/ffmpeg-linux-arm64.tar.xz"
  else
    echo "Unsupported architecture: ${arch}"
    exit 1
  fi

  if [[ "${force}" -eq 1 || ! -f "${ffmpeg_archive}" ]]; then
    echo "Downloading FFmpeg..."
    download "${ffmpeg_url}" "${ffmpeg_archive}"
  else
    echo "FFmpeg archive already exists: ${ffmpeg_archive}"
  fi
fi

if [[ "${skip_ytdlp}" -eq 0 ]]; then
  ytdlp_url="https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
  ytdlp_bin="${yt_dir}/yt-dlp"
  if [[ "${force}" -eq 1 || ! -f "${ytdlp_bin}" ]]; then
    echo "Downloading yt-dlp..."
    download "${ytdlp_url}" "${ytdlp_bin}"
    chmod +x "${ytdlp_bin}"
  else
    echo "yt-dlp already exists: ${ytdlp_bin}"
  fi
fi

echo "Done."
