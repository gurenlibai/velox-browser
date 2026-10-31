#!/usr/bin/env python3
"""比较两组启动耗时（每行一个毫秒值），输出 p50/p90 与差值。"""
import sys


def load(path):
    vals = [int(x) for x in (l.strip() for l in open(path)) if x.isdigit()]
    return sorted(vals)


def pct(vals, q):
    return vals[min(len(vals) - 1, int(len(vals) * q))] if vals else 0


def main(a, b):
    va, vb = load(a), load(b)
    print(f"{'':<26}{'n':>5}{'p50':>8}{'p90':>8}")
    print(f"{a[-24:]:<26}{len(va):>5}{pct(va, .5):>8}{pct(va, .9):>8}")
    print(f"{b[-24:]:<26}{len(vb):>5}{pct(vb, .5):>8}{pct(vb, .9):>8}")
    if va and vb:
        print(f"delta(p90) = {pct(vb, .9) - pct(va, .9)}ms (正数 = 第二组更慢)")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
