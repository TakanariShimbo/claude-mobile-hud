#!/usr/bin/env python3
"""
NFR-10 SSE 再接続 latency 計測スクリプト (Phase 5、#188)。

ConnectionController の backoff loop 出力から SSE 復旧時間を集計。docs/03 §3.2.4
(connect_attempt / backoff / backoff_interrupted_by_reconnect)。

復旧 1 件 = (切断検知 → 復旧成立) の time diff。判定ルール:
  start = `channel.conn: event=connect_collect_threw`
        (SSE collect が例外で落ちた瞬間。Hub kill / Wi-Fi OFF / 401 等)
  end   = 次の `channel.conn: event=connect_attempt attempt=1`
        または `channel.conn: event=backoff_interrupted_by_reconnect`
        (= attempt が 1 にリセット = 直前の loop が成立し終わった証拠)

合否: 中央値 (p50) < 30000ms で NFR-10 / AC-04 合格。

実行例:
    $ADB logcat -d | python3 tools/measure_reconnect_latency.py
    python3 tools/measure_reconnect_latency.py /tmp/captured.log
    python3 tools/measure_reconnect_latency.py --from-adb
"""

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
    """(disconnect_ts, recovery_ms) のペア列を返す。orphan は warnings に集約。"""
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
                # 連続切断 (= 復旧前に再失敗) → orphan として記録、新規 pending に置き換える。
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
                # 直前の attempt が成功して loop が一度終わった = 復旧。
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
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
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
