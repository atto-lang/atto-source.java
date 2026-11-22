import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../stdlib/perf.atto',import.meta.url),'utf8');
const ids=readFileSync(new URL('../stdlib/perf_ids.atto',import.meta.url),'utf8');
test('Atto perf module uses only source-language hooks and owner-local buffer',()=>{
 assert.match(source,/define module Perf as/);
 assert.match(source,/pub define class Buffer as/);
 assert.match(source,/let mut Array<Event> items/);
 assert.doesNotMatch(source,/java\.|System\.|interop\.|stdio\.|writech|tcp\.|http\.|spawn/);
});
test('disabled begin and sample check before all clock/record operations',()=>{
 const body=source.split('pub fnc begin_ns')[1].split('pub fnc duration_ns')[0];
 assert.ok(body.indexOf('if !enabled')<body.indexOf('process.monotonic_ns()'));
 for(const method of ['record_counter','record_gauge']){
  const block=source.split('pub '+method)[1].split('pub ')[0];
  assert.ok(block.indexOf('if !enabled')<block.indexOf('new Event('));
 }
 const span=source.split('pub record_span')[1].split('pub record_counter')[0];
 assert.ok(span.indexOf('if started_ns <= 0')<span.indexOf('process.monotonic_ns()'));
});
test('bounded drop-on-full, no resizing or producer exports',()=>{
 assert.match(source,/capacity < 1 \|\| capacity > 65536/);
 assert.match(source,/self.items.size >= self.capacity/);
 assert.match(source,/self.dropped = self.dropped \+ 1/);
 assert.doesNotMatch(source,/self.items\.clear|println|export\(/);
});
test('stable typed numeric IDs align with existing CorePerf phases',()=>{
 const matches=[...ids.matchAll(/pub fnc ([a-z0-9_]+)\(\) -> int \{ return (\d+); \}/g)];
 assert.equal(matches.length,7);
 assert.deepEqual(matches.map(m=>Number(m[2])),[23,24,25,26,27,28,29]);
 assert.equal(new Set(matches.map(m=>m[1])).size,7);
});
