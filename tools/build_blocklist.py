#!/usr/bin/env python3
"""
Velox 拦截规则编译器

把多个公开规则源编译成二进制资产 assets/blocklist.bin：

  头部  : magic "VLB1" | u32 count | u32 excCount | u32 bloomBits | u32 bloomK
  Bloom : ceil(bloomBits/8) 字节（对「域名及其所有父域后缀」入位）
  Hash  : count   * i64  升序（FNV-1a 64）
  Exc   : excCount* i64  升序（EasyList 中 @@ 例外规则，优先放行）

为什么用「Bloom 快速否决 + 有序 64 位哈希二分确认」而不是存字符串：
  - 10 万条域名存字符串约 2MB，Java 侧还要 ~8MB 对象头开销；
  - 64 位哈希只需 800KB，且二分 17 次整数比较 ≈ 50ns，完全可以在
    shouldInterceptRequest 的回调线程上零分配完成；
  - 64 位哈希在 10 万条规模下的随机碰撞期望 ≈ 1e-9，作为内容拦截用途足够，
    且任何碰撞最多是多拦一个广告域，不构成安全边界。

用法:
  python3 tools/build_blocklist.py --out app/src/main/assets/blocklist.bin \
      --hosts dl/hosts.txt --hosts dl/peter.txt --easylist dl/easylist.txt
"""
import argparse
import struct
import sys
from pathlib import Path

FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MASK64 = 0xFFFFFFFFFFFFFFFF


def fnv1a64(s: str) -> int:
    h = FNV_OFFSET
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * FNV_PRIME) & MASK64
    return h


def suffixes(host: str):
    """example.a.com -> [example.a.com, a.com, com]（不含单标签顶级域）"""
    labels = host.split(".")
    for i in range(len(labels) - 1):
        yield ".".join(labels[i:])


def is_valid_host(h: str) -> bool:
    if not h or len(h) > 253 or "." not in h:
        return False
    if h.startswith("-") or h.endswith("-"):
        return False
    for ch in h:
        if not (ch.isalnum() or ch in ".-_"):
            return False
    # 拒绝明显的通配/路径残留
    return "*" not in h and "/" not in h and " " not in h


def parse_hosts_file(path: Path):
    out = set()
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) >= 2 and (parts[0].startswith("127.") or parts[0] in ("0.0.0.0", "::1", "::")):
                host = parts[1].lower().rstrip(".")
                if is_valid_host(host):
                    out.add(host)
    return out


def parse_easylist(path: Path):
    """只取可安全降维成「整域拦截」的规则，其它（路径/正则/元素隐藏）一律丢弃。"""
    blocked, exceptions = set(), set()
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("!") or line.startswith("["):
                continue
            if "##" in line or "#@#" in line or "#?#" in line or "$#" in line:
                continue  # cosmetic
            is_exc = line.startswith("@@")
            rule = line[2:] if is_exc else line
            if not rule.startswith("||"):
                continue
            rest = rule[2:]
            # 只接受 ||domain^ / ||domain^$modifiers / ||domain$modifiers
            host = rest.split("^", 1)[0].split("$", 1)[0].strip()
            tail = rest[len(host):]
            if not (tail.startswith("^") or tail.startswith("$")):
                continue
            if "*" in host or "/" in host or host.startswith("."):
                continue
            if "$" in tail and ("domain=" in tail and not is_exc):
                # domain= 限定的规则降维后可能误伤，保守丢弃
                continue
            host = host.lower()
            if not is_valid_host(host):
                continue
            (exceptions if is_exc else blocked).add(host)
    return blocked, exceptions


def build_bloom(hashes, bits):
    bloom = bytearray((bits + 7) // 8)
    k = 4
    for h in hashes:
        for i in range(k):
            idx = ((h >> (i * 16)) ^ (h * (i + 1))) % bits
            bloom[idx >> 3] |= 1 << (idx & 7)
    return bytes(bloom), k


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--hosts", action="append", default=[])
    ap.add_argument("--easylist", action="append", default=[])
    ap.add_argument("--bloom-bits-per-item", type=int, default=12)
    args = ap.parse_args()

    blocked, exceptions = set(), set()
    for h in args.hosts:
        p = Path(h)
        if p.exists():
            d = parse_hosts_file(p)
            print(f"  hosts  {p.name}: {len(d)} domains")
            blocked |= d
    for e in args.easylist:
        p = Path(e)
        if p.exists():
            b, x = parse_easylist(p)
            print(f"  easylist {p.name}: +{len(b)} block / +{len(x)} exception")
            blocked |= b
            exceptions |= x

    # 例外优先：从黑名单里剔除显式例外域（含其父域冲突时保留更具体的例外）
    exceptions = {d for d in exceptions if is_valid_host(d)}
    blocked = {d for d in blocked if is_valid_host(d)}
    blocked -= exceptions

    # 同域内如果父域被拉黑而子域被放行，需要在匹配时先查例外表，故两者都保留
    hashes = sorted(fnv1a64(d) for d in blocked)
    exc_hashes = sorted(fnv1a64(d) for d in exceptions)

    total = len(hashes)
    bits = max(1 << 16, total * args.bloom_bits_per_item)
    # 把每个域名的所有父域后缀也入 Bloom，使「快速否决」与实际匹配语义一致
    bloom_input = set()
    for d in blocked:
        for s in suffixes(d):
            bloom_input.add(fnv1a64(s))
    bloom, k = build_bloom(sorted(bloom_input), bits)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as f:
        f.write(b"VLB1")
        f.write(struct.pack("<IIII", total, len(exc_hashes), bits, k))
        f.write(bloom)
        f.write(struct.pack("<%dQ" % total, *hashes))
        if exc_hashes:
            f.write(struct.pack("<%dQ" % len(exc_hashes), *exc_hashes))

    print(f"blocked={total} exceptions={len(exc_hashes)} bloom={len(bloom)/1024:.0f}KB "
          f"file={out.stat().st_size/1024:.0f}KB")


if __name__ == "__main__":
    sys.exit(main())
