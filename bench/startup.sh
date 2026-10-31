#!/usr/bin/env bash
# 用法: ./bench/startup.sh <package> [iterations] [activity]
set -euo pipefail
PKG="${1:?package required}"
N="${2:-30}"
ACT="${3:-.MainActivity}"
for i in $(seq 1 "$N"); do
  adb shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 0.4
  adb shell am start -W -n "$PKG/$ACT" 2>/dev/null | awk '/TotalTime/{print $2}'
done
