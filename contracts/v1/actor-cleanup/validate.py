#!/usr/bin/env python3
"""Validate the actor cleanup JSON Schema against exact conformance vectors."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator

root = Path(__file__).parent
schema = json.loads((root / "cleanup.schema.json").read_text())
vectors = json.loads((root / "cases.json").read_text())
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)
for index, value in enumerate(vectors["valid"]):
    problems = list(validator.iter_errors(value))
    assert not problems, f"valid[{index}]: {problems}"
for index, value in enumerate(vectors["invalid"]):
    problems = list(validator.iter_errors(value))
    assert problems, f"invalid[{index}] was unexpectedly accepted"
print(f"ACTOR_CLEANUP_JSON_SCHEMA_PASSED valid={len(vectors['valid'])} invalid={len(vectors['invalid'])}")
