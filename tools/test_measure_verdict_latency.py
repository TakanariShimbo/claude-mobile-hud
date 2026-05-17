#!/usr/bin/env python3
"""measure_verdict_latency.py の self test。inline fixture で動作 pin。"""

from __future__ import annotations

import io
import os
import sys
import unittest
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from measure_verdict_latency import measure, report  # noqa: E402


def _line(ts: str, tag: str, event: str, **kv: str) -> str:
    """`MM-DD HH:MM:SS.SSS  PID TID I tag: event=... k=v ...` 形式の logcat 行を組み立て。"""
    fields = " ".join(f"{k}={v}" for k, v in kv.items())
    return f"05-17 {ts}  1000  1001 I {tag}: event={event} {fields}"


class MeasureTest(unittest.TestCase):
    def test_in_proc_pair_yields_one_duration(self):
        lines = [
            _line("13:00:00.000", "channel.verdict", "verdict_inproc", request_id="req-1", decision="ALLOW"),
            _line("13:00:00.300", "channel.service", "permission_canceled", request_id="req-1", notif_id="42"),
        ]
        d, w = measure(lines)
        self.assertEqual(1, len(d))
        self.assertEqual("req-1", d[0][0])
        self.assertAlmostEqual(300, d[0][1], delta=1)
        self.assertEqual([], w)

    def test_cold_start_pair_uses_dispatch_started(self):
        lines = [
            _line("13:00:00.000", "channel.svc.verdict", "verdict_dispatch_started", request_id="req-2"),
            _line("13:00:01.234", "channel.service", "permission_canceled", request_id="req-2", notif_id="99"),
        ]
        d, _ = measure(lines)
        self.assertEqual(1, len(d))
        self.assertAlmostEqual(1234, d[0][1], delta=1)

    def test_unmatched_start_is_warned(self):
        lines = [
            _line("13:00:00.000", "channel.verdict", "verdict_inproc", request_id="req-3", decision="ALLOW"),
        ]
        d, w = measure(lines)
        self.assertEqual(0, len(d))
        self.assertEqual(1, len(w))
        self.assertIn("req-3", w[0])

    def test_end_without_start_is_ignored(self):
        lines = [
            _line("13:00:00.000", "channel.service", "permission_canceled", request_id="req-orphan", notif_id="0"),
        ]
        d, w = measure(lines)
        self.assertEqual(0, len(d))
        self.assertEqual(0, len(w))

    def test_pair_match_is_by_request_id_even_across_unrelated_lines(self):
        lines = [
            _line("13:00:00.000", "channel.verdict", "verdict_inproc", request_id="req-A", decision="ALLOW"),
            "noise irrelevant log line",
            _line("13:00:00.100", "channel.verdict", "verdict_inproc", request_id="req-B", decision="DENY"),
            _line("13:00:00.500", "channel.service", "permission_canceled", request_id="req-A", notif_id="1"),
            _line("13:00:00.300", "channel.service", "permission_canceled", request_id="req-B", notif_id="2"),
        ]
        d, w = measure(lines)
        self.assertEqual(2, len(d))
        self.assertEqual([], w)
        by_id = {req: ms for req, ms in d}
        self.assertAlmostEqual(500, by_id["req-A"], delta=1)
        self.assertAlmostEqual(200, by_id["req-B"], delta=1)

    def test_duplicate_starts_keep_first(self):
        # 重複 start (= 同 request_id の verdict event が複数行) は 1 件目を採用。
        lines = [
            _line("13:00:00.000", "channel.verdict", "verdict_inproc", request_id="req-dup", decision="ALLOW"),
            _line("13:00:00.100", "channel.verdict", "verdict_inproc", request_id="req-dup", decision="DENY"),
            _line("13:00:00.400", "channel.service", "permission_canceled", request_id="req-dup", notif_id="3"),
        ]
        d, _ = measure(lines)
        self.assertEqual(1, len(d))
        # 1 件目 (13:00:00.000) との差 400ms を採用 (重複の 2 件目 100ms ではない)。
        self.assertAlmostEqual(400, d[0][1], delta=1)


class ReportTest(unittest.TestCase):
    def _capture(self, durations, warnings=None):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = report(durations, warnings or [])
        return rc, buf.getvalue()

    def test_pass_when_all_within_budget(self):
        rc, out = self._capture([("a", 100.0), ("b", 4000.0)])
        self.assertEqual(0, rc)
        self.assertIn("PASS", out)
        self.assertIn("pairs measured: 2", out)

    def test_fail_when_any_exceeds_budget(self):
        rc, out = self._capture([("a", 100.0), ("b", 5500.0)])
        self.assertEqual(1, rc)
        self.assertIn("FAIL", out)
        self.assertIn("5500ms", out)
        self.assertIn("request_id=b", out)

    def test_empty_input_returns_zero(self):
        rc, out = self._capture([])
        self.assertEqual(0, rc)
        self.assertIn("no measurable pairs", out)


if __name__ == "__main__":
    unittest.main()
