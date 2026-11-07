#!/usr/bin/env python3
"""Adversarial source-free tests for the non-authorizing provenance verifier."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("verify-private-ci-post-merge-provenance-repair.py")


def load_module():
    spec = importlib.util.spec_from_file_location("provenance_verifier_under_test", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class ProvenanceVerifierTest(unittest.TestCase):
    def setUp(self):
        self.v = load_module()
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.v.SOURCE_REPOSITORY = "synthetic/source"
        self.v.SOURCE_REPOSITORY_ID = 424242
        self.v.SOURCE_MAIN_SHA = "1" * 40
        self.v.MANIFEST = "snapshot.json"
        self.v.LEDGER = "ledger.txt"
        self.controls = {
            "control.txt": b"control-v1\n",
            self.v.LEDGER: b"# no active destinations\n",
        }
        self._write_valid_snapshot()

    def tearDown(self):
        self.tmp.cleanup()

    def _manifest(self):
        return {
            "schema": self.v.SCHEMA,
            "source_repository": self.v.SOURCE_REPOSITORY,
            "source_repository_id": self.v.SOURCE_REPOSITORY_ID,
            "source_main_sha": self.v.SOURCE_MAIN_SHA,
            "authorizes_source_transfer": False,
            "required_active_destinations": 0,
            "controls": [
                {"path": path, "blob_sha": self.v.git_blob_sha(data)}
                for path, data in self.controls.items()
            ],
        }

    def _write_valid_snapshot(self):
        for path, data in self.controls.items():
            p = self.root / path
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        self.v.EXPECTED = {
            path: self.v.git_blob_sha(data)
            for path, data in self.controls.items()
        }
        (self.root / self.v.MANIFEST).write_text(
            json.dumps(self._manifest(), separators=(",", ":")),
            encoding="utf-8",
        )

    def _write_manifest(self, doc):
        (self.root / self.v.MANIFEST).write_text(
            json.dumps(doc, separators=(",", ":")),
            encoding="utf-8",
        )

    def assert_rejected(self, needle):
        with self.assertRaises(self.v.Rejected) as cm:
            self.v.verify(self.root)
        self.assertIn(needle, str(cm.exception))

    def test_synthetic_happy_path_is_non_authorizing(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.v.verify(self.root)
        text = out.getvalue()
        self.assertIn("PRIVATE_CI_PROVENANCE_SNAPSHOT_PASS", text)
        self.assertIn("PRIVATE_CI_SOURCE_TRANSFER_AUTHORIZED=false", text)
        self.assertIn("PRIVATE_CI_ACTIVE_DESTINATIONS=0", text)

    def test_repository_id_float_is_rejected(self):
        doc = self._manifest()
        doc["source_repository_id"] = float(self.v.SOURCE_REPOSITORY_ID)
        self._write_manifest(doc)
        self.assert_rejected("source repository ID mismatch")

    def test_boolean_active_destination_count_is_rejected(self):
        doc = self._manifest()
        doc["required_active_destinations"] = False
        self._write_manifest(doc)
        self.assert_rejected("empty active destination ledger")

    def test_non_string_source_main_sha_is_rejected(self):
        doc = self._manifest()
        doc["source_main_sha"] = 111
        self._write_manifest(doc)
        self.assert_rejected("source main SHA mismatch")

    def test_non_utf8_ledger_is_rejected_cleanly(self):
        bad = b"\xff\xfe\xfd"
        (self.root / self.v.LEDGER).write_bytes(bad)
        self.controls[self.v.LEDGER] = bad
        self._write_valid_snapshot()
        self.assert_rejected("ledger is not UTF-8")

    def test_active_destination_is_rejected_even_when_blob_is_pinned(self):
        active = b"trusted/carrier 123456\n"
        (self.root / self.v.LEDGER).write_bytes(active)
        self.controls[self.v.LEDGER] = active
        self._write_valid_snapshot()
        self.assert_rejected("active private-CI destination rows are present")

    def test_blob_drift_is_rejected(self):
        (self.root / "control.txt").write_bytes(b"tampered\n")
        self.assert_rejected("blob mismatch for control.txt")

    def test_symlinked_control_is_rejected(self):
        target = self.root / "real-control.txt"
        target.write_bytes(b"control-v1\n")
        control = self.root / "control.txt"
        control.unlink()
        control.symlink_to(target.name)
        self.assert_rejected("not a regular non-symlink file")

    def test_duplicate_json_keys_are_rejected(self):
        m = self._manifest()
        controls = json.dumps(m["controls"], separators=(",", ":"))
        raw = (
            '{"schema":"' + self.v.SCHEMA + '",'
            '"schema":"' + self.v.SCHEMA + '",'
            '"source_repository":"' + self.v.SOURCE_REPOSITORY + '",'
            '"source_repository_id":424242,'
            '"source_main_sha":"' + self.v.SOURCE_MAIN_SHA + '",'
            '"authorizes_source_transfer":false,'
            '"required_active_destinations":0,'
            '"controls":' + controls + '}'
        )
        (self.root / self.v.MANIFEST).write_text(raw, encoding="utf-8")
        self.assert_rejected("duplicate JSON key")

    def test_unsafe_paths_are_rejected(self):
        for path in ("../escape", "/absolute", "dir\\win", ".git/config", "dir/../escape"):
            with self.subTest(path=path):
                with self.assertRaises(self.v.Rejected):
                    self.v.safe_relative_path(path)

    def test_oversize_manifest_rejected_before_json_parsing(self):
        # Valid JSON with >1 MiB of whitespace must fail even if it parses.
        raw = (self.root / self.v.MANIFEST).read_bytes()
        oversized = raw + b" " * (1024 * 1024 + 1)
        (self.root / self.v.MANIFEST).write_bytes(oversized)
        self.assert_rejected("invalid provenance snapshot JSON")

    def test_nonfinite_json_constants_denied_at_parser_boundary(self):
        raw = (self.root / self.v.MANIFEST).read_text(encoding="utf-8")
        token = '"source_repository_id":424242'
        self.assertIn(token, raw)
        for value in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(value=value):
                (self.root / self.v.MANIFEST).write_text(
                    raw.replace(token, '"source_repository_id":' + value),
                    encoding="utf-8",
                )
                self.assert_rejected("invalid provenance snapshot JSON")

    def test_json_depth_limit_has_exact_boundary(self):
        self.v.require_json_depth(b"[" * self.v.MAX_JSON_DEPTH + b"0" + b"]" * self.v.MAX_JSON_DEPTH)
        with self.assertRaisesRegex(self.v.Rejected, "invalid provenance snapshot JSON"):
            self.v.require_json_depth(b"[" * (self.v.MAX_JSON_DEPTH + 1)
                                      + b"0" + b"]" * (self.v.MAX_JSON_DEPTH + 1))

    def test_json_depth_scan_ignores_brackets_in_quoted_values(self):
        self.v.require_json_depth(b'{"value":"' + b"[" * 2000 + b'"}')
        self.v.require_json_depth(b'{"value":"escaped\\\"' + b"{" * 2000 + b'"}')

    def test_excessively_nested_json_rejected_cleanly(self):
        raw = "[" * 2200 + "0" + "]" * 2200
        (self.root / self.v.MANIFEST).write_text(raw, encoding="utf-8")
        self.assert_rejected("invalid provenance snapshot JSON")

    def test_manifest_cannot_widen_authorization(self):
        doc = self._manifest()
        doc["authorizes_source_transfer"] = True
        self._write_manifest(doc)
        self.assert_rejected("must never authorize source transfer")


if __name__ == "__main__":
    unittest.main(verbosity=2)
