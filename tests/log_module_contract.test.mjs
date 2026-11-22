import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { atto_pure } from './atto_pure_subset.mjs';

// These tests execute ONLY a narrow pure-subset translation of the .atto
// severity functions and statically inspect queue safety invariants.
// They DO NOT execute the Atto compiler, guest runtime, AOT, or sink I/O.
const source = readFileSync(new URL('../stdlib/log.atto', import.meta.url), 'utf8');

const validSeverity = atto_pure(source, 'valid_severity');
const validThreshold = atto_pure(source, 'valid_threshold', { valid_severity: validSeverity });
const enabled = atto_pure(source, 'enabled', {
  valid_severity: validSeverity,
  valid_threshold: validThreshold
});
const severityName = atto_pure(source, 'severity_name');
const sinkByteBudgetOk = atto_pure(source, 'sink_byte_budget_ok');

test('standard numeric severities and OFF are disjoint', () => {
  assert.deepEqual([10,20,30,40,50,60].map(level=>validSeverity(level)), [true,true,true,true,true,true]);
  for (const n of [-1,0,1,9,11,35,65,70,71,2147483647]) {
    assert.equal(validSeverity(n), false);
  }
  assert.equal(validThreshold(70), true);
  for (const n of [-1,0,15,65,71]) assert.equal(validThreshold(n), false);
});

test('level labels are stable and unrecognized values are distinct', () => {
  assert.deepEqual([10,20,30,40,50,60,70,-1].map(level=>severityName(level)),
    ['TRACE','DEBUG','INFO','WARN','ERROR','FATAL','UNKNOWN','UNKNOWN']);
});

test('all level thresholds, OFF, and malformed filters fail closed', () => {
  for (const threshold of [10,20,30,40,50,60,70,-1,0,25,80]) {
    for (const severity of [10,20,30,40,50,60,70,-1,25]) {
      const expected = validSeverity(severity) && validThreshold(threshold) &&
        threshold !== 70 && severity >= threshold;
      assert.equal(enabled(severity,threshold),expected,
        'severity=' + severity + ' threshold=' + threshold);
    }
  }
});

test('native module exposes typed immutable logging records and factories', () => {
  assert.match(source, /^namespace log;$/m);
  assert.doesNotMatch(source, /define module Log as/);
  assert.match(source, /pub define class LogEntry as/);
  assert.equal((source.match(/pub val /g) || []).length,11);
  assert.match(source, /pub fnc entry_with_fields\(/);
  assert.match(source, /pub fnc valid_entry\(LogEntry record\)/);
  for (const attr of ['timestamp_ns','actor_id','trace_id','span_id']) {
    assert.match(source, new RegExp('record\\.' + attr + ' < 0'));
  }
});

test('bounded queue rejects before mutation and counts only FULL', () => {
  const start = source.indexOf('pub offer(mut self)');
  const end = source.indexOf('pub set_threshold',start);
  const offer = source.slice(start,end);
  assert.ok(start >= 0 && end > start);
  for (const status of ['return 3;', 'return 4;', 'return 0;', 'return 2;', 'return 1;']) {
    assert.ok(offer.includes(status), status);
  }
  assert.ok(offer.indexOf('if self.closed') < offer.indexOf('self.items.add'));
  assert.ok(offer.indexOf('if !valid_entry') < offer.indexOf('self.items.add'));
  assert.ok(offer.indexOf('if !enabled') < offer.indexOf('self.items.add'));
  assert.ok(offer.indexOf('self.items.size >= self.capacity') < offer.indexOf('self.items.add'));
  assert.ok(offer.indexOf('self.dropped = self.dropped + 1') <
    offer.indexOf('self.items.add'));
  assert.match(source,/capacity < 1 \|\| capacity > 4096/);
  assert.match(source,/self.dropped < 2147483647/);
  assert.match(source,/self.items.size >= self.capacity && self.head > 0/);
  assert.match(source,/remaining.add\(self.items.get\(index\)\)/);
  assert.match(source,/self.items = remaining/);
});

test('one-consumer peek/ack retains entries until acknowledged and then resets', () => {
  const start = source.indexOf('pub ack_one(mut self)');
  const end = source.indexOf('pub close(mut self)',start);
  const ack = source.slice(start,end);
  assert.ok(start >= 0 && end > start);
  assert.ok(ack.indexOf('if self.head >= self.items.size') <
    ack.indexOf('self.head = self.head + 1'));
  assert.ok(ack.indexOf('self.head = self.head + 1') <
    ack.indexOf('if self.head == self.items.size'));
  assert.match(ack,/self.items = new Array<LogEntry>\(\)/);
  assert.match(source,/return self.items.get\(self.head\)/);
  assert.match(source,/return self.items.size - self.head/);
});

test('close rejects production but does not prohibit draining', () => {
  assert.match(source,/pub close\(mut self\) -> void/);
  assert.match(source,/self.closed = true/);
  assert.match(source,/pub is_drained\(\) -> bool/);
  const after = source.slice(source.indexOf('pub peek()'));
  assert.doesNotMatch(after.split('pub is_drained')[0], /if self.closed/);
});

test('no host implementation, incidental I/O, async blocking, dynamic runtime, or reflection', () => {
  assert.doesNotMatch(source, /java\.|System\.|interop\.|\bstdio\.|\bprintln\s*\(|writech|\bspawn\b|\bawait\b|\bffi\b|\breflect\b/);
  assert.doesNotMatch(source,/\b(?:log_async|flush_async_logs)\s*\(/);
});

test('trusted sink byte policy checks overflow and exact boundary without adding sizes', () => {
  assert.equal(sinkByteBudgetOk(0,65536,65536,8388608), true);
  assert.equal(sinkByteBudgetOk(8388607,1,65536,8388608), true);
  assert.equal(sinkByteBudgetOk(8388608,1,65536,8388608), false);
  assert.equal(sinkByteBudgetOk(0,65537,65536,8388608), false);
  assert.equal(sinkByteBudgetOk(0,-1,65536,8388608), false);
  assert.equal(sinkByteBudgetOk(-1,1,65536,8388608), false);
  assert.equal(sinkByteBudgetOk(3,0,65536,2), false);
  assert.equal(sinkByteBudgetOk(0,1,-1,8388608), false);
  assert.equal(sinkByteBudgetOk(0,1,65536,-1), false);
  const max = Number.MAX_SAFE_INTEGER;
  assert.equal(sinkByteBudgetOk(max-2,2,2,max),true);
  assert.equal(sinkByteBudgetOk(max-2,3,3,max),false);
});

test('structured fields reject orphaned values and repeated nonempty keys', () => {
  assert.match(source,/record.field1_key eq "" && record.field1_value neq ""/);
  assert.match(source,/record.field2_key eq "" && record.field2_value neq ""/);
  assert.match(source,/record.field1_key neq "" && record.field1_key eq record.field2_key/);
});

test('source files use one flat namespace and no runtime module wrapper', () => {
  assert.equal((source.match(/^namespace /gm) || []).length,1);
  assert.ok(source.indexOf('namespace log;') < source.indexOf('pub fnc enabled'));
  assert.ok(source.indexOf('pub define class LogEntry as') < source.indexOf('pub define class Buffer as'));
  assert.doesNotMatch(source,/define namespace|define module|use namespace .*\\./);
});

test('source interpreter rejects arbitrary host code, globals and unsupported statements', () => {
  assert.throws(() => atto_pure('pub fnc evil() -> bool { process.exit(1);\\n}', 'evil'));
  assert.throws(() => atto_pure('pub fnc evil() -> bool { return globalThis;\\n}', 'evil')());
  assert.throws(() => atto_pure('pub fnc evil() -> bool { while true; do return true; fi\\n}', 'evil'));
});

test('source mutation changes the checked policy rather than relying on a JS oracle', () => {
  const changed = source.replace('return severity >= threshold;', 'return severity < threshold;');
  const broken = atto_pure(changed, 'enabled', {
    valid_severity: validSeverity, valid_threshold: validThreshold
  });
  assert.equal(enabled(40,30),true);
  assert.equal(broken(40,30),false);
});
