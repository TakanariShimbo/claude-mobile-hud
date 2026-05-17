#!/usr/bin/env python3
"""
verify_atomicity.py の self test。実機 logcat の代わりに inline fixture を流す。

実行:
    python3 tools/test_verify_atomicity.py
"""

from __future__ import annotations

import io
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from verify_atomicity import parse_kv_line, verify  # noqa: E402


def _log(
    *,
    mode: str,
    pending_request_id: str = "null",
    transcript_state: str = "IDLE",
    confirming: str = "false",
    input_len: int = 0,
    mic_source: str = "GLASS",
    seq: int = 1,
    tag: str = "channel.phone-state",
) -> str:
    return (
        f"05-17 14:23:45.123  1000  1001 I {tag}: "
        f"event=phone_state_emit seq={seq} "
        f"mode={mode} pending_request_id={pending_request_id} "
        f"transcript_state={transcript_state} confirming={confirming} "
        f"input_len={input_len} mic_source={mic_source}"
    )


class ParseKvLineTest(unittest.TestCase):
    def test_parses_basic_pairs(self):
        kv = parse_kv_line(
            "I channel.phone-state: event=phone_state_emit seq=42 mode=idle"
        )
        self.assertEqual("phone_state_emit", kv["event"])
        self.assertEqual("42", kv["seq"])
        self.assertEqual("idle", kv["mode"])

    def test_parses_quoted_value_with_spaces(self):
        kv = parse_kv_line('event=foo product="Rokid Glass" ok=true')
        self.assertEqual("Rokid Glass", kv["product"])
        self.assertEqual("true", kv["ok"])

    def test_parses_quoted_value_with_escape(self):
        kv = parse_kv_line(r'event=x message="a\"b" k=v')
        self.assertEqual('a"b', kv["message"])
        self.assertEqual("v", kv["k"])


class VerifyTest(unittest.TestCase):
    def _run(self, lines: list[str]) -> tuple[int, str]:
        buf = io.StringIO()
        rc = verify(lines, stream=buf)
        return rc, buf.getvalue()

    def test_ignores_unrelated_lines(self):
        rc, out = self._run(
            [
                "irrelevant log line",
                "channel.input: event=mic_toggled",
            ]
        )
        self.assertEqual(0, rc)
        self.assertIn("parsed 0 state events", out)

    def test_passes_when_invariants_hold(self):
        lines = [
            _log(mode="idle", seq=1),
            _log(mode="listening", transcript_state="LISTENING", seq=2),
            _log(
                mode="permission_confirming",
                pending_request_id="req-abc",
                transcript_state="IDLE",
                seq=3,
            ),
            _log(mode="confirming", confirming="true", seq=4),
            _log(mode="idle", seq=5),
        ]
        rc, out = self._run(lines)
        self.assertEqual(0, rc, msg=out)
        self.assertIn("PASS: 0 violations", out)

    def test_violation_listening_without_transcript_listening(self):
        lines = [_log(mode="listening", transcript_state="IDLE")]
        rc, out = self._run(lines)
        self.assertEqual(1, rc)
        self.assertIn("[listening]", out)
        self.assertIn("FAIL: 1 violation", out)

    def test_violation_permission_without_pending(self):
        lines = [
            _log(
                mode="permission_confirming",
                pending_request_id="null",
                seq=10,
            )
        ]
        rc, out = self._run(lines)
        self.assertEqual(1, rc)
        self.assertIn("[permission_confirming]", out)

    def test_violation_confirming_without_flag(self):
        lines = [_log(mode="confirming", confirming="false")]
        rc, out = self._run(lines)
        self.assertEqual(1, rc)
        self.assertIn("[confirming]", out)

    def test_idle_always_passes_regardless_of_other_fields(self):
        # docs/03 §3.2.1.2.1 通り IDLE は無条件 OK (他 invariant の補集合扱い)。
        rc, _ = self._run([_log(mode="idle", pending_request_id="dangling-id")])
        self.assertEqual(0, rc)

    def test_priority_listening_does_not_double_count_with_permission(self):
        # mode が listening のとき pending_request_id が non-null でも違反扱いしない
        # (LISTENING > PERMISSION_CONFIRMING の優先順位)。
        lines = [
            _log(
                mode="listening",
                transcript_state="LISTENING",
                pending_request_id="req-1",
            )
        ]
        rc, out = self._run(lines)
        self.assertEqual(0, rc, msg=out)

    def test_glass_state_swap_lines_are_parsed(self):
        line = (
            "05-17 14:23:45.123 I channel.glass: "
            "event=glass_state_swap seq=42 mode=permission_confirming "
            "pending_request_id=req-x transcript_state=IDLE confirming=false"
        )
        rc, out = self._run([line])
        self.assertEqual(0, rc, msg=out)
        self.assertIn("parsed 1 state events", out)


if __name__ == "__main__":
    unittest.main()
