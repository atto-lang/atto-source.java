#!/usr/bin/env python3
"""Fail-closed normative JSON Schema admission checks (not signature verification)."""
from __future__ import annotations

import copy
import json
import math
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

def reject_non_json_constant(value):
    # Python's json.loads accepts NaN/Infinity by default even though those
    # are invalid JSON literals. A signed admission payload must be strict.
    raise ValueError("non-JSON numeric literal in AOT manifest: " + value)


MAX_MANIFEST_BYTES = 64 * 1024
MAX_MANIFEST_DEPTH = 64


def reject_noncanonical_value(root):
    """Reject values that cannot be represented as interoperable signed JSON."""
    stack = [(root, 0)]
    while stack:
        value, depth = stack.pop()
        if depth > MAX_MANIFEST_DEPTH:
            raise ValueError("AOT manifest nesting depth exceeded")
        if isinstance(value, str):
            if any(0xD800 <= ord(char) <= 0xDFFF for char in value):
                raise ValueError("unpaired Unicode surrogate in AOT manifest")
        elif isinstance(value, float):
            # JSON 1e10000 is a legal numeric token but Python turns it into
            # Infinity. It cannot be signed as a finite RFC 8785 JSON number.
            if not math.isfinite(value):
                raise ValueError("nonfinite JSON number in AOT manifest")
        elif isinstance(value, list):
            stack.extend((entry, depth + 1) for entry in value)
        elif isinstance(value, dict):
            for key, entry in value.items():
                stack.append((key, depth + 1))
                stack.append((entry, depth + 1))
    return root


def decode(text):
    if not isinstance(text, str) or len(text.encode("utf-8")) > MAX_MANIFEST_BYTES:
        raise ValueError("AOT manifest must be UTF-8 text of at most 64 KiB")
    return reject_noncanonical_value(json.loads(
        text, object_pairs_hook=unique_keys,
        parse_constant=reject_non_json_constant))

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
    # JSON Schema's ordinary $ anchor also matches before a final newline.
    # Exact digests, commit IDs and signatures MUST reject those aliases.
    rejected("digest final newline", lambda x: x.update(source_sha256="1" * 64 + "\n"))
    rejected("expanded digest final newline", lambda x: x.update(expanded_sha256="2" * 64 + "\n"))
    rejected("AST digest final newline", lambda x: x.update(generated_ast_sha256="3" * 64 + "\n"))
    rejected("artifact hash final newline", lambda x: x["artifact"].update(file_sha256="4" * 64 + "\n"))
    rejected("Git commit final newline", lambda x: x["compiler"].update(git_commit="a" * 40 + "\n"))
    rejected("signature final newline", lambda x: x["provenance"]["signature"].update(
        value_base64="A" * 86 + "==\n"))

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
    for invalid in ("NaN", "Infinity", "-Infinity"):
        try:
            decode('{"size_bytes": ' + invalid + '}')
        except ValueError:
            pass
        else:
            raise AssertionError("nonstandard JSON number escaped admission: " + invalid)
    try:
        decode('{"size_bytes": 1, "size_bytes": 2}')
    except ValueError:
        pass
    else:
        raise AssertionError("duplicate nested JSON key escaped admission")
    for name, invalid in (
        ("JSON exponent overflow", '{"n":1e10000}'),
        ("lone Unicode surrogate value", '{"name":"\\uD800"}'),
        ("lone Unicode surrogate key", '{"\\uDC00":"unsafe"}'),
        ("manifest nesting exhaustion", "[" * 66 + "0" + "]" * 66),
        ("manifest input size", '"' + "A" * (MAX_MANIFEST_BYTES + 1) + '"'),
    ):
        try:
            decode(invalid)
        except (ValueError, RecursionError):
            pass
        else:
            raise AssertionError(name + " escaped strict signed JSON admission")
    print("AOT manifest V1 schema: valid fixture and 34 negative checks passed")

if __name__ == "__main__":
    check()
