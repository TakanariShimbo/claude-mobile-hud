#!/usr/bin/env python3
"""measure_reconnect_latency.py の self test。"""

from __future__ import annotations

import io
import os
import sys
import unittest
from contextlib import redirect_stdout
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from measure_reconnect_latency import measure, report  # noqa: E402


def _line(ts: str, event: str, **kv: str) -> str:
    fields = " ".join(f"{k}={v}" for k, v in kv.items())
    return f"05-17 {ts}  1000  1001 I channel.conn: event={event} {fields}".rstrip()


class MeasureTest(unittest.TestCase):
    def test_recovery_via_attempt_1_reset(self):
        lines = [
            _line("13:00:00.000", "connect_collect_threw"),
            _line("13:00:01.000", "backoff", attempt="1", delay_ms="1000"),
            _line("13:00:02.000", "connect_attempt", attempt="2"),
            _line("13:00:03.500", "backoff", attempt="2", delay_ms="2000"),
            _line("13:00:05.500", "connect_attempt", attempt="3"),
            _line("13:00:10.000", "connect_attempt", attempt="1"),  # ← recovery
        ]
        d, w = measure(lines)
        self.assertEqual(1, len(d))
        self.assertAlmostEqual(10_000, d[0][1], delta=2)
        self.assertEqual([], w)

    def test_recovery_via_manual_reconnect(self):
        lines = [
            _line("13:00:00.000", "connect_collect_threw"),
            _line("13:00:02.500", "backoff_interrupted_by_reconnect"),
        ]
        d, w = measure(lines)
        self.assertEqual(1, len(d))
        self.assertAlmostEqual(2_500, d[0][1], delta=1)
        self.assertEqual([], w)

    def test_multiple_disconnects(self):
        lines = [
            _line("13:00:00.000", "connect_collect_threw"),
            _line("13:00:05.000", "connect_attempt", attempt="1"),
            _line("13:01:00.000", "connect_collect_threw"),
            _line("13:01:30.000", "connect_attempt", attempt="1"),
        ]
        d, _ = measure(lines)
        self.assertEqual(2, len(d))
        self.assertAlmostEqual(5_000, d[0][1], delta=1)
        self.assertAlmostEqual(30_000, d[1][1], delta=1)

    def test_orphan_disconnect_without_recovery(self):
        lines = [
            _line("13:00:00.000", "connect_collect_threw"),
            _line("13:00:02.000", "backoff", attempt="1", delay_ms="1000"),
        ]
        d, w = measure(lines)
        self.assertEqual(0, len(d))
        self.assertEqual(1, len(w))
        self.assertIn("no recovery observed", w[0])

    def test_consecutive_disconnects_replace_pending(self):
        # 再失敗 (= 復旧前に再切断) は warn + 新規 pending に置き換え。
        lines = [
            _line("13:00:00.000", "connect_collect_threw"),
            _line("13:00:01.000", "connect_collect_threw"),
            _line("13:00:02.000", "connect_attempt", attempt="1"),
        ]
        d, w = measure(lines)
        self.assertEqual(1, len(d))
        self.assertEqual(1, len(w))
        self.assertAlmostEqual(1_000, d[0][1], delta=1)

    def test_attempt_1_without_prior_disconnect_is_ignored(self):
        # 初回 boot 時に attempt=1 が出る。pending が None なら skip。
        lines = [
            _line("13:00:00.000", "connect_attempt", attempt="1"),
            _line("13:00:01.000", "connect_attempt", attempt="2"),
        ]
        d, w = measure(lines)
        self.assertEqual(0, len(d))
        self.assertEqual(0, len(w))


class ReportTest(unittest.TestCase):
    def _capture(self, durations, warnings=None):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = report(durations, warnings or [])
        return rc, buf.getvalue()

    def test_pass_when_p50_under_budget(self):
        # p50 = 5000ms (3 値、median)
        rc, out = self._capture([
            (datetime(1970, 1, 1, 13), 1_000.0),
            (datetime(1970, 1, 1, 13), 5_000.0),
            (datetime(1970, 1, 1, 13), 10_000.0),
        ])
        self.assertEqual(0, rc)
        self.assertIn("PASS", out)
        self.assertIn("p50=5000ms", out)

    def test_fail_when_p50_exceeds_budget(self):
        # p50 = 35_000ms > 30_000ms
        rc, out = self._capture([
            (datetime(1970, 1, 1, 13), 5_000.0),
            (datetime(1970, 1, 1, 13), 35_000.0),
            (datetime(1970, 1, 1, 13), 60_000.0),
        ])
        self.assertEqual(1, rc)
        self.assertIn("FAIL", out)

    def test_empty_input_returns_zero(self):
        rc, out = self._capture([])
        self.assertEqual(0, rc)
        self.assertIn("no measurable", out)


if __name__ == "__main__":
    unittest.main()
