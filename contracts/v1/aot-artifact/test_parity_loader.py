#!/usr/bin/env python3
"""Offline regression for the TypeSpec JSON Schema reference loader."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from test_typespec_parity import emitted_validator


class EmittedSchemaLoaderTest(unittest.TestCase):
    def test_local_references_with_and_without_explicit_draft(self):
        # TypeSpec may omit $schema on referenced model files. Resolving a
        # missing declaration must use Draft 2020-12, not throw
        # CannotDetermineSpecification or silently skip validation.
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "CompilerIdentity.json").write_text(json.dumps({
                "type": "object",
                "required": ["backend"],
                "additionalProperties": False,
                "properties": {"backend": {"const": "llvm"}},
            }), encoding="utf-8")
            root_file = root / "AotArtifactManifestV1.json"
            for explicit_draft in (False, True):
                schema = {"$ref": "CompilerIdentity.json"}
                if explicit_draft:
                    schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
                root_file.write_text(json.dumps(schema), encoding="utf-8")
                validator = emitted_validator(root)
                self.assertTrue(validator.is_valid({"backend": "llvm"}))
                self.assertFalse(validator.is_valid({"backend": "jit"}))
                self.assertFalse(validator.is_valid({"backend": "llvm", "allow_all": True}))

    def test_duplicate_json_keys_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "AotArtifactManifestV1.json").write_text(
                '{"type":"object","type":"string"}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
                emitted_validator(root)


if __name__ == "__main__":
    unittest.main()
