#!/usr/bin/env python3
"""
NFR-13 atomicity 自動検証ランナー (Phase 3 §7.2 / AC-09)。

実行例:
    adb logcat -d -s channel.glass:* channel.phone-state:* > /tmp/atomicity.log
    python3 tools/verify_atomicity.py /tmp/atomicity.log

Phase 4 で本実装。現状は placeholder。
"""

import re
import sys
from typing import Callable, Iterable

INVARIANTS: list[tuple[str, Callable[[dict[str, str]], bool]]] = [
    # (target_mode, predicate)
    ("PERMISSION_CONFIRMING", lambda e: e.get("pending_request_id", "null") != "null"),
    ("LISTENING", lambda e: e.get("transcript_state") == "LISTENING"),
    ("CONFIRMING", lambda e: (
        e.get("transcript_state") != "IDLE" or int(e.get("input_len", "0")) > 0
    )),
    ("IDLE", lambda e: True),
]


def parse_kv_line(line: str) -> dict[str, str]:
    """`event=X key1=v1 key2=v2 ...` を dict に。"""
    return dict(
        match.split("=", 1) for match in re.findall(r'\S+=\S+', line)
    )


def is_state_event(line: str) -> bool:
    return "glass_state_swap" in line or "phone_state_emit" in line


def verify(lines: Iterable[str]) -> int:
    """違反件数を返す。0 なら合格。"""
    violations: list[tuple[str, str]] = []
    parsed_count = 0
    for line in lines:
        if not is_state_event(line):
            continue
        parsed_count += 1
        kv = parse_kv_line(line)
        mode = kv.get("mode")
        for target_mode, pred in INVARIANTS:
            if mode == target_mode and not pred(kv):
                violations.append((line.rstrip(), target_mode))
    print(f"[verify_atomicity] parsed {parsed_count} state events")
    if violations:
        print(f"[verify_atomicity] FAIL: {len(violations)} violation(s)")
        for line, mode in violations:
            print(f"  [{mode}] {line}")
    else:
        print("[verify_atomicity] PASS: 0 violations")
    return len(violations)


def main() -> int:
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <logcat-output-file>", file=sys.stderr)
        return 2
    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        return 1 if verify(f) > 0 else 0


if __name__ == "__main__":
    sys.exit(main())
