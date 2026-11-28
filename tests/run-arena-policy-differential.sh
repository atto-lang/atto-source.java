#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test "$(git hash-object tests/arena-policy-differential-v1.mjs)" = f98055af7bb9b8952664946d2b2a1f3a735dc283
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
export ATTO_POLICY_WORK="$work"
node --input-type=module <<'JS'
import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {arenaPolicyBatches} from './tests/arena-policy-differential-v1.mjs';
const batches=arenaPolicyBatches();
assert.equal(batches.length,22);
for(const [i,b] of batches.entries()){
 assert.equal(b.start,i*16);assert.equal(b.count,16);
 writeFileSync(join(process.env.ATTO_POLICY_WORK,'batch-'+b.start+'.atto'),b.source);
}
JS
mvn -B -Dtest=ArenaPolicyCompiledGuestTest "-Datto.arena.corpus.dir=$work" test
