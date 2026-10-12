#!/usr/bin/env python3
"""Fail-closed normative JSON Schema admission checks (not signature verification)."""
from __future__ import annotations

import copy
import json
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent

def unique_keys(pairs):
    data = {}
    for key, value in pairs:
        if key in data:
            raise ValueError("duplicate manifest JSON key: " + key)
        data[key] = value
    return data

def decode(text):
    return json.loads(text, object_pairs_hook=unique_keys)

def check():
    schema = decode((ROOT / "manifest.schema.json").read_text())
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    original = decode((ROOT / "fixtures" / "valid.json").read_text())
    validator.validate(original)

    def rejected(name, edit):
        candidate = copy.deepcopy(original)
        edit(candidate)
        if validator.is_valid(candidate):
            raise AssertionError(name + " bypassed fail-closed schema admission")

    rejected("unknown version", lambda x: x.update(schema_version="oreslang.aot-artifact.v2"))
    rejected("unknown backend", lambda x: x["compiler"].update(backend="jit"))
    rejected("uppercase digest", lambda x: x.update(source_sha256="A" * 64))
    rejected("short digest", lambda x: x["artifact"].update(file_sha256="bad"))
    rejected("zero-size artifact", lambda x: x["artifact"].update(size_bytes=0))
    rejected("fractional artifact size", lambda x: x["artifact"].update(size_bytes=1.5))
    rejected("non-lossless artifact size", lambda x: x["artifact"].update(size_bytes=9007199254740992))
    rejected("macro generation enabled", lambda x: x["policy"].update(runtime_macro_expansion=True))
    rejected("annotation generation enabled", lambda x: x["policy"].update(runtime_annotation_generation=True))
    rejected("runtime compilation enabled", lambda x: x["policy"].update(runtime_guest_compilation=True))
    rejected("duplicate capabilities", lambda x: x["policy"].update(required_capabilities=["io", "io"]))
    rejected("unrecognized authority field", lambda x: x["policy"].update(allow_all=True))
    rejected("unknown manifest key", lambda x: x.update(runtime_codegen=True))
    rejected("invalid signature algorithm", lambda x: x["provenance"]["signature"].update(algorithm="none"))
    rejected("empty signer identity", lambda x: x["provenance"]["signature"].update(key_id=""))
    rejected("truncated signature", lambda x: x["provenance"]["signature"].update(value_base64="AA"))
    rejected("noncanonical signature pad bits", lambda x: x["provenance"]["signature"].update(value_base64="A" * 85 + "B=="))
    rejected("missing provenance", lambda x: x.pop("provenance"))
    try:
        decode('{"schema_version": "v1", "schema_version": "v2"}')
    except ValueError:
        pass
    else:
        raise AssertionError("duplicate JSON key escaped input admission")
    print("AOT manifest V1 schema: valid fixture and 19 negative checks passed")

if __name__ == "__main__":
    check()
