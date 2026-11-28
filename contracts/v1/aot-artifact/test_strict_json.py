#!/usr/bin/env python3
"""Executable fail-closed JSON ingress checks; not cryptographic verification."""
from __future__ import annotations

import unittest

from test_contract import MAX_MANIFEST_BYTES, decode


class StrictAotJsonTest(unittest.TestCase):
    def test_accepts_valid_json_and_valid_unicode(self):
        self.assertEqual(decode('{"name":"ok","values":[1,2.25,false]}'),
                         {"name": "ok", "values": [1, 2.25, False]})
        # A valid surrogate pair represents one Unicode scalar value.
        self.assertEqual(decode('{"name":"\\ud83d\\ude00"}')["name"], "😀")

    def test_rejects_duplicate_decoded_keys(self):
        for source in ('{"a":1,"a":2}', '{"a":1,"\\u0061":2}',
                       '{"nested":{"x":0,"x":1}}'):
            with self.subTest(source=source), self.assertRaises(ValueError):
                decode(source)

    def test_rejects_nonstandard_or_nonfinite_numbers(self):
        for source in ('{"x":NaN}', '{"x":Infinity}', '{"x":-Infinity}',
                       '{"x":1e10000}', '{"x":-1e10000}'):
            with self.subTest(source=source), self.assertRaises(ValueError):
                decode(source)

    def test_rejects_unpaired_unicode_surrogates(self):
        for source in ('{"x":"\\uD800"}', '{"\\uDFFF":1}',
                       '{"nested":["\\uDC00"]}'):
            with self.subTest(source=source), self.assertRaises(ValueError):
                decode(source)

    def test_limits_nesting_and_payload_size(self):
        for source in ("[" * 65 + "0" + "]" * 65,
                       '"' + "x" * (MAX_MANIFEST_BYTES + 1) + '"'):
            with self.subTest(size=len(source)), self.assertRaises(ValueError):
                decode(source)

    def test_invalid_raw_unicode_and_deep_json_fail_as_value_errors(self):
        # Some callers provide already-decoded text, not JSON escape tokens.
        # Encoding and parser recursion must fail with the same admission
        # error category as ordinary malformed JSON.
        raw_surrogate = '{"name":"' + chr(0xD800) + '"}'
        pathological_depth = "[" * 1600 + "0" + "]" * 1600
        for source in (raw_surrogate, pathological_depth):
            with self.subTest(size=len(source)), self.assertRaises(ValueError):
                decode(source)

    def test_rejects_trailing_json(self):
        with self.assertRaises(ValueError):
            decode('{"ok":true}{"unsafe":true}')


if __name__ == "__main__":
    unittest.main()
