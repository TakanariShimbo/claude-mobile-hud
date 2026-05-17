#!/usr/bin/env python3
"""
NFR-13 atomicity 自動検証ランナー (Phase 3 §7.2 / AC-09)。

実行例:
    # 1) 実機で logcat 取得 → ファイル経由で verify
    $ADB logcat -d -s channel.glass:* channel.phone-state:* > /tmp/atomicity.log
    python3 tools/verify_atomicity.py /tmp/atomicity.log

    # 2) stdin 直結 (実機が手元にある場合の手軽な経路)
    $ADB logcat -d -s channel.glass:* channel.phone-state:* | python3 tools/verify_atomicity.py

    # 3) adb 自走 (PATH に adb が通っている前提。ANDROID_SERIAL 指定可、
    #    or env ADB でフルパス上書きも可)
    python3 tools/verify_atomicity.py --from-adb

exit code:
    0 — 違反 0 件 (合格)
    1 — 違反検出
    2 — 引数 / 実行環境エラー

invariant の優先順位は docs/03 §3.2.1.2.1 と §7.2 に従う。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from collections.abc import Callable, Iterable
from typing import TextIO


# 大文字小文字を許容する (構造化ログ側は小文字を使う設計だが、過去 / 将来の表記揺れに耐性)。
def _norm(value: str | None) -> str:
    return (value or "").strip().lower()


# 優先順位: docs/03 §3.2.1.2.1 (POC 流) ─
#   LISTENING > PERMISSION_CONFIRMING > CONFIRMING > IDLE
# 「mode が X を表示しているなら predicate が true である」べき、という invariant。
# 1 行に対して上から順に評価し、最初に該当した mode の predicate が満たされなければ
# 違反として記録する (一致 mode が 1 つに絞られる構造なので、複数違反は出ない)。
INVARIANTS: list[tuple[str, Callable[[dict[str, str]], bool]]] = [
    ("listening", lambda e: _norm(e.get("transcript_state")) == "listening"),
    (
        "permission_confirming",
        lambda e: _norm(e.get("pending_request_id")) not in ("", "null"),
    ),
    ("confirming", lambda e: _norm(e.get("confirming")) == "true"),
    ("idle", lambda _e: True),
]

STATE_EVENTS = ("glass_state_swap", "phone_state_emit")


def parse_kv_line(line: str) -> dict[str, str]:
    """`event=X key1=v1 key2=v2 ...` を dict に。`key=v alue` のような空白入り値は
    `"v alue"` で quote されている前提 (StructuredLog 側で escape 済み)。"""
    kv: dict[str, str] = {}
    # `key=` の後ろは `"..."` か空白までの非空白列。
    for m in re.finditer(r'(\w+)=("(?:[^"\\]|\\.)*"|\S+)', line):
        k, raw = m.group(1), m.group(2)
        if raw.startswith('"') and raw.endswith('"') and len(raw) >= 2:
            raw = (
                raw[1:-1]
                .replace(r"\"", '"')
                .replace(r"\\", "\\")
                .replace(r"\n", "\n")
                .replace(r"\t", "\t")
            )
        kv[k] = raw
    return kv


def is_state_event(line: str) -> bool:
    return any(token in line for token in STATE_EVENTS)


def verify(lines: Iterable[str], *, stream: TextIO = sys.stdout) -> int:
    """違反件数を返す。0 なら合格。"""
    violations: list[tuple[str, str]] = []
    parsed_count = 0
    for raw in lines:
        line = raw.rstrip("\n")
        if not is_state_event(line):
            continue
        parsed_count += 1
        kv = parse_kv_line(line)
        mode = _norm(kv.get("mode"))
        if not mode:
            continue
        for target_mode, pred in INVARIANTS:
            if mode == target_mode:
                if not pred(kv):
                    violations.append((line, target_mode))
                break
    print(f"[verify_atomicity] parsed {parsed_count} state events", file=stream)
    if violations:
        print(
            f"[verify_atomicity] FAIL: {len(violations)} violation(s)",
            file=stream,
        )
        for line, mode in violations:
            print(f"  [{mode}] {line}", file=stream)
    else:
        print("[verify_atomicity] PASS: 0 violations", file=stream)
    return len(violations)


def _read_from_adb(serial: str | None) -> Iterable[str]:
    # `ADB` env でフルパス上書き可 (CLAUDE.md 参照、PATH に adb 未登録の開発機向け)。
    adb_bin = os.environ.get("ADB", "adb")
    cmd = [adb_bin]
    if serial:
        cmd += ["-s", serial]
    cmd += ["logcat", "-d", "-s", "channel.glass:*", "channel.phone-state:*"]
    proc = subprocess.run(
        cmd, capture_output=True, text=True, check=False,
    )
    if proc.returncode != 0:
        print(
            f"[verify_atomicity] adb failed (rc={proc.returncode}):\n{proc.stderr}",
            file=sys.stderr,
        )
        sys.exit(2)
    return proc.stdout.splitlines()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "path",
        nargs="?",
        help="logcat 出力ファイル。省略時は stdin (or --from-adb)。",
    )
    parser.add_argument(
        "--from-adb",
        action="store_true",
        help="adb logcat -d で自走 (PATH 通っていること)。",
    )
    parser.add_argument(
        "--serial",
        default=os.environ.get("ANDROID_SERIAL"),
        help="adb device serial (--from-adb と組合せ)。env ANDROID_SERIAL でも可。",
    )
    args = parser.parse_args(argv)

    if args.from_adb:
        lines = _read_from_adb(args.serial)
    elif args.path:
        with open(args.path, encoding="utf-8") as f:
            return 1 if verify(f.readlines()) > 0 else 0
    elif not sys.stdin.isatty():
        lines = sys.stdin.readlines()
    else:
        parser.print_help(sys.stderr)
        return 2

    return 1 if verify(lines) > 0 else 0


if __name__ == "__main__":
    sys.exit(main())
