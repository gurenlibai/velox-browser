#!/usr/bin/env python3
"""从 `adb shell dumpsys gfxinfo <pkg>` 输出中统计慢帧与分位帧时长。"""
import re
import sys


def main(path):
    text = open(path, encoding="utf-8", errors="ignore").read()
    m = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", text)
    slow = re.search(r"Slow UI thread:\s*(\d+)", text)
    print(f"janky={m.group(1) if m else '?'} ({m.group(2) if m else '?'}%)")
    for name, pattern in (
        ("p50", r"50th percentile:\s*(\d+)ms"),
        ("p90", r"90th percentile:\s*(\d+)ms"),
        ("p95", r"95th percentile:\s*(\d+)ms"),
        ("p99", r"99th percentile:\s*(\d+)ms"),
    ):
        g = re.search(pattern, text)
        print(f"{name}={g.group(1) + 'ms' if g else '?'}")
    print(f"slow_ui_thread={slow.group(1) if slow else '?'}")


if __name__ == "__main__":
    main(sys.argv[1])
