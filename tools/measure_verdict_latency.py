#!/usr/bin/env python3
"""
NFR-14 verdict latency 計測スクリプト (Phase 5、#182 / #187)。

start / end のペア:
  start = `channel.verdict: event=verdict_inproc request_id=X decision=Y`
        または `channel.svc.verdict: event=verdict_dispatch_started request_id=X`
        (= ユーザがボタンを押した瞬間。Application-alive 経路と cold-start 経路の両方)
  end   = `channel.service: event=permission_canceled request_id=X notif_id=Z`
        (= verdict 完了で通知 cancel が走った瞬間。POST /permission 成功後の handler)

集計値:
  count / p50 / p95 / p99 / max
  合否: p99 < 5000ms で AC-14 合格 (NFR-14 verdict 5s 予算)

実行例:
    $ADB logcat -d | python3 tools/measure_verdict_latency.py
    python3 tools/measure_verdict_latency.py /tmp/captured.log
    python3 tools/measure_verdict_latency.py --from-adb
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


NFR14_BUDGET_MS = 5_000


# Android logcat の prefix (`MM-DD HH:MM:SS.SSS PID TID LEVEL TAG:`) を parse。
_PREFIX_RE = re.compile(
    r"^(?P<ts>\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})"
    r"\s+\d+\s+\d+\s+[VDIWE]\s+\S+:"
)

_KV_RE = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\S+)')

# 開始 / 終了 event 名。前者は 2 経路 (inproc / dispatch_started) いずれも accept。
_START_EVENTS = {"verdict_inproc", "verdict_dispatch_started"}
_END_EVENT = "permission_canceled"


def _parse_ts(text: str) -> datetime | None:
    # 年が無いので便宜上 1970 を入れる (差分計算には年は無関係)。
    try:
        return datetime.strptime(f"1970-{text}", "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return None


def _kv(line: str) -> dict[str, str]:
    return dict(m.group(1, 2) for m in _KV_RE.finditer(line))


def _percentile(values: list[float], p: float) -> float:
    """0 <= p <= 100、線形補間で percentile を返す (numpy 不使用)。"""
    if not values:
        return float("nan")
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def measure(lines: Iterable[str]) -> tuple[list[tuple[str, float]], list[str]]:
    """各 request_id について `start → end` のペアを取り、(request_id, ms) を返す。
    対応する end が見つからなかった orphan 開始 event は warnings として返す。"""
    starts: dict[str, datetime] = {}  # request_id → start datetime
    durations: list[tuple[str, float]] = []
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
        req = kv.get("request_id")
        if not event or not req:
            continue
        if event in _START_EVENTS:
            # 既に同一 request_id の start があれば 1 件目を上書きしない (重複防御)。
            starts.setdefault(req, ts)
        elif event == _END_EVENT:
            start = starts.pop(req, None)
            if start is None:
                # start が観測されない end (= 計測対象外、別経路で発火した cancel)。
                continue
            delta_ms = (ts - start).total_seconds() * 1000
            durations.append((req, delta_ms))

    for req in starts:
        warnings.append(f"start without matching end: request_id={req}")
    return durations, warnings


def report(durations: list[tuple[str, float]], warnings: list[str]) -> int:
    print(f"[measure_verdict_latency] pairs measured: {len(durations)}")
    for w in warnings:
        print(f"[measure_verdict_latency] WARN {w}")
    if not durations:
        print("[measure_verdict_latency] (no measurable pairs in input)")
        return 0
    values = [ms for _, ms in durations]
    p50 = _percentile(values, 50)
    p95 = _percentile(values, 95)
    p99 = _percentile(values, 99)
    avg = statistics.fmean(values)
    print(
        f"  min={min(values):.0f}ms  p50={p50:.0f}ms  p95={p95:.0f}ms"
        f"  p99={p99:.0f}ms  max={max(values):.0f}ms  avg={avg:.0f}ms"
    )
    print("  top 5 slowest:")
    for req, ms in sorted(durations, key=lambda x: -x[1])[:5]:
        print(f"    {ms:>7.0f}ms  request_id={req}")

    over = [(req, ms) for req, ms in durations if ms > NFR14_BUDGET_MS]
    if over:
        print(
            f"[measure_verdict_latency] FAIL: {len(over)} pair(s) exceed "
            f"NFR-14 budget ({NFR14_BUDGET_MS}ms)"
        )
        for req, ms in over:
            print(f"    {ms:>7.0f}ms  request_id={req}")
        return 1
    print(f"[measure_verdict_latency] PASS: all pairs within {NFR14_BUDGET_MS}ms")
    return 0


def _from_adb(serial: str | None) -> list[str]:
    adb = os.environ.get("ADB", "adb")
    cmd = [adb]
    if serial:
        cmd += ["-s", serial]
    cmd += ["logcat", "-d"]
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
