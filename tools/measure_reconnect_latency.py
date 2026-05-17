#!/usr/bin/env python3
"""NFR-10 SSE 再接続 latency 計測 (docs/03 §7.5 / §7.5.1-.3, Phase 5)。"""

from __future__ import annotations

import argparse
import os
import re
import statistics
import subprocess
import sys
from collections.abc import Iterable
from datetime import datetime


NFR10_BUDGET_MEDIAN_MS = 30_000


_PREFIX_RE = re.compile(
    r"^(?P<ts>\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})"
    r"\s+\d+\s+\d+\s+[VDIWE]\s+\S+:"
)
_KV_RE = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\S+)')


def _parse_ts(text: str) -> datetime | None:
    try:
        return datetime.strptime(f"1970-{text}", "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return None


def _kv(line: str) -> dict[str, str]:
    return dict(m.group(1, 2) for m in _KV_RE.finditer(line))


def _percentile(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def measure(lines: Iterable[str]) -> tuple[list[tuple[datetime, float]], list[str]]:
    """docs/03 §7.5.1 / §7.5.2: disconnect→recover pair 化、連続切断は orphan で記録。"""
    pending: datetime | None = None
    durations: list[tuple[datetime, float]] = []
    warnings: list[str] = []

    for raw in lines:
        line = raw.rstrip("\n")
        prefix = _PREFIX_RE.match(line)
        if not prefix:
            continue
        ts = _parse_ts(prefix.group("ts"))
        if ts is None:
            continue
        kv = _kv(line)
        event = kv.get("event")
        if event == "connect_collect_threw":
            if pending is not None:
                warnings.append(
                    f"unrecovered disconnect at {pending.time().isoformat()} "
                    f"(replaced by newer disconnect at {ts.time().isoformat()})"
                )
            pending = ts
        elif event == "connect_attempt":
            if pending is None:
                continue
            attempt = kv.get("attempt")
            if attempt == "1":
                delta_ms = (ts - pending).total_seconds() * 1000
                durations.append((pending, delta_ms))
                pending = None
        elif event == "backoff_interrupted_by_reconnect":
            if pending is None:
                continue
            delta_ms = (ts - pending).total_seconds() * 1000
            durations.append((pending, delta_ms))
            pending = None

    if pending is not None:
        warnings.append(
            f"unrecovered disconnect at {pending.time().isoformat()} (no recovery observed)"
        )
    return durations, warnings


def report(
    durations: list[tuple[datetime, float]],
    warnings: list[str],
) -> int:
    print(f"[measure_reconnect_latency] disconnects measured: {len(durations)}")
    for w in warnings:
        print(f"[measure_reconnect_latency] WARN {w}")
    if not durations:
        print("[measure_reconnect_latency] (no measurable disconnect/recovery pairs)")
        return 0
    values = [ms for _, ms in durations]
    p50 = _percentile(values, 50)
    p90 = _percentile(values, 90)
    p99 = _percentile(values, 99)
    avg = statistics.fmean(values)
    print(
        f"  min={min(values):.0f}ms  p50={p50:.0f}ms  p90={p90:.0f}ms"
        f"  p99={p99:.0f}ms  max={max(values):.0f}ms  avg={avg:.0f}ms"
    )
    print("  top 5 slowest:")
    for ts, ms in sorted(durations, key=lambda x: -x[1])[:5]:
        print(f"    {ms:>7.0f}ms  disconnect_at={ts.time().isoformat()}")

    if p50 > NFR10_BUDGET_MEDIAN_MS:
        print(
            f"[measure_reconnect_latency] FAIL: p50 {p50:.0f}ms exceeds NFR-10 "
            f"budget (median < {NFR10_BUDGET_MEDIAN_MS}ms)"
        )
        return 1
    print(
        f"[measure_reconnect_latency] PASS: p50 {p50:.0f}ms within "
        f"{NFR10_BUDGET_MEDIAN_MS}ms"
    )
    return 0


def _from_adb(serial: str | None) -> list[str]:
    adb = os.environ.get("ADB", "adb")
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    cmd += ["logcat", "-d", "-s", "channel.conn:*"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    except FileNotFoundError:
        # docs/03 §7.2.4: adb 不在は exit 2 (env エラー扱い)。
        print(f"adb not found: {adb} (set ADB env to absolute path)", file=sys.stderr)
        sys.exit(2)
    if proc.returncode != 0:
        print(f"adb failed: {proc.stderr}", file=sys.stderr)
        sys.exit(2)
    return proc.stdout.splitlines()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", nargs="?", help="logcat ファイル。省略時は stdin / --from-adb")
    parser.add_argument("--from-adb", action="store_true", help="adb logcat -d で自走")
    parser.add_argument(
        "--serial", default=os.environ.get("ANDROID_SERIAL"),
        help="adb -s 引数 (env ANDROID_SERIAL でも可)",
    )
    args = parser.parse_args(argv)

    if args.from_adb:
        lines: Iterable[str] = _from_adb(args.serial)
    elif args.path:
        with open(args.path, encoding="utf-8") as f:
            lines = f.readlines()
    elif not sys.stdin.isatty():
        lines = sys.stdin.readlines()
    else:
        parser.print_help(sys.stderr)
        return 2

    durations, warnings = measure(lines)
    return report(durations, warnings)


if __name__ == "__main__":
    sys.exit(main())
