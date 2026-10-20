#!/usr/bin/env python3
"""Fail closed if TypeSpec-generated schemas admit more or less than the V1 contract."""
from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parent

def read_json(path: Path):
    def no_duplicate_keys(pairs):
        obj = {}
        for name, value in pairs:
            if name in obj:
                raise ValueError(f"duplicate JSON key {name!r} in {path}")
            obj[name] = value
        return obj
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys)

def emitted_validator(folder: Path):
    files = sorted(folder.rglob("*.json"))
    if not files:
        raise AssertionError("TypeSpec emitted no JSON Schema files")
    roots = [p for p in files if p.name.endswith("AotArtifactManifestV1.json")]
    if len(roots) != 1:
        raise AssertionError(f"expected exactly one emitted AotArtifactManifestV1.json, found: {roots}")
    registry = Registry()
    for file in files:
        document = read_json(file)
        Draft202012Validator.check_schema(document)
        # TypeSpec may omit $schema on emitted referenced models. Without a
        # default specification, referencing raises CannotDetermineSpecification
        # before differential validation even starts. The normative contract
        # is Draft 2020-12, so specify it for every resource explicitly.
        resource = Resource.from_contents(
            document, default_specification=DRAFT202012)
        registry = registry.with_resource(file.resolve().as_uri(), resource)
        registry = registry.with_resource(file.name, resource)
        if "$id" in document:
            registry = registry.with_resource(document["$id"], resource)
    return Draft202012Validator(read_json(roots[0]), registry=registry)

def cases(valid):
    yield "valid", valid
    alternate = copy.deepcopy(valid)
    alternate["compiler"]["backend"] = "java-graalvm"
    alternate["policy"]["required_capabilities"] = []
    yield "valid alternate backend", alternate

    mutations = [
        ("schema version", ("schema_version", "wrong")),
        ("unknown root field", ("unknown_field", 1)),
    ]
    for name, (key, value) in mutations:
        item = copy.deepcopy(valid)
        item[key] = value
        yield name, item

    for name, group, field, value in [
        ("unknown compiler", "compiler", "backend", "jit"),
        ("uppercase Git SHA", "compiler", "git_commit", "A" * 40),
        ("missing compiler version", "compiler", "version", ""),
        ("unknown executable format", "artifact", "format", "shell-script"),
        ("zero bytes", "artifact", "size_bytes", 0),
        ("fractional bytes", "artifact", "size_bytes", 2.5),
        ("unsafe byte count", "artifact", "size_bytes", 9007199254740992),
        ("macro runtime generation", "policy", "runtime_macro_expansion", True),
        ("annotation runtime generation", "policy", "runtime_annotation_generation", True),
        ("guest runtime compiler", "policy", "runtime_guest_compilation", True),
        ("duplicate authority", "policy", "required_capabilities", ["net", "net"]),
        ("empty authority", "policy", "required_capabilities", [""]),
        ("unknown authority field", "policy", "allow_all", True),
        ("unsigned artifact", "provenance", "signature", {}),
    ]:
        item = copy.deepcopy(valid)
        item[group][field] = value
        yield name, item

    for name, value in [
        ("wrong signature algorithm", "sha256"),
        ("noncanonical signature base64", "A" * 85 + "B=="),
        ("malformed signature length", "AQ=="),
    ]:
        item = copy.deepcopy(valid)
        item["provenance"]["signature"]["algorithm" if name == "wrong signature algorithm" else "value_base64"] = value
        yield name, item

    for name, field in [
        ("missing provenance", "provenance"),
        ("missing policy", "policy"),
        ("missing expanded digest", "expanded_sha256"),
    ]:
        item = copy.deepcopy(valid)
        item.pop(field)
        yield name, item

    for name, field, value in [
        ("short source digest", "source_sha256", "1"),
        ("uppercase expanded digest", "expanded_sha256", "F" * 64),
    ]:
        item = copy.deepcopy(valid)
        item[field] = value
        yield name, item

    # Ordinary regex $ accepts the position before a final newline.
    # TypeSpec-derived and normative schemas must agree on true EOF.
    for name, group, field, value in [
        ("newline source SHA", None, "source_sha256", "1" * 64 + "\n"),
        ("newline expansion SHA", None, "expanded_sha256", "2" * 64 + "\n"),
        ("newline generated AST SHA", None, "generated_ast_sha256", "3" * 64 + "\n"),
        ("newline binary SHA", "artifact", "file_sha256", "4" * 64 + "\n"),
        ("newline compiler Git SHA", "compiler", "git_commit", "a" * 40 + "\n"),
        ("newline Ed25519 signature", "signature", "value_base64", "A" * 86 + "==\n"),
    ]:
        item = copy.deepcopy(valid)
        if group == "signature":
            item["provenance"]["signature"][field] = value
        elif group is None:
            item[field] = value
        else:
            item[group][field] = value
        yield name, item

def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: test_typespec_parity.py <typespec-generated-output-directory>")
    normative = Draft202012Validator(read_json(ROOT / "manifest.schema.json"))
    generated = emitted_validator(Path(sys.argv[1]))
    original = read_json(ROOT / "fixtures" / "valid.json")
    count = 0
    for name, example in cases(original):
        expected = normative.is_valid(example)
        observed = generated.is_valid(example)
        if expected != observed:
            raise AssertionError(
                f"TypeSpec/JSON Schema admission mismatch for {name}: "
                f"normative={expected}, generated={observed}")
        if name.startswith("valid") and not expected:
            raise AssertionError(f"the valid fixture was rejected: {name}")
        if not name.startswith("valid") and expected:
            raise AssertionError(f"the negative fixture was accepted: {name}")
        count += 1
    print(f"PASS: TypeSpec/JSON Schema differential admission on {count} fixtures")

if __name__ == "__main__":
    main()
