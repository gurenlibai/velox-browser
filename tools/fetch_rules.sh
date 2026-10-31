#!/usr/bin/env bash
# 拉取公共拦截规则源（构建期使用，不随仓库分发原始规则文本）
set -euo pipefail
DL="${1:-dl}"
mkdir -p "$DL"
echo "→ StevenBlack hosts"
curl -sSL -o "$DL/stevenblack-hosts.txt" https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts
echo "→ Peter Lowe's list"
curl -sSL -o "$DL/peter-lowe-hosts.txt" \
  'https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext'
echo "→ EasyList"
curl -sSL -o "$DL/easylist.txt" https://easylist.to/easylist/easylist.txt
echo "→ compile"
python3 tools/build_blocklist.py --out app/src/main/assets/blocklist.bin \
  --hosts "$DL/stevenblack-hosts.txt" --hosts "$DL/peter-lowe-hosts.txt" --easylist "$DL/easylist.txt"
