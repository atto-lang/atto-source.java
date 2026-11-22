import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// These tests execute ONLY a narrow pure-subset translation of the .atto
// severity functions and statically inspect queue safety invariants.
// They DO NOT execute the Atto compiler, guest runtime, AOT, or sink I/O.
const source = readFileSync(new URL('../stdlib/log.atto', import.meta.url), 'utf8');

function pureFunction(name, dependencies = {}) {
  const pattern = new RegExp('pub fnc ' + name +
    '\\(([^)]*)\\) -> (?:bool|String) \\{([\\s\\S]*?)\\n  \\}');
  const found = source.match(pattern);
  assert.ok(found, 'missing native Atto function ' + name);
  const parameters = found[1].split(',').map((p) => p.trim().split(/\\s+/).at(-1));
  const body = found[2]
    .replace(/\\bif\\s+([^;]+);\\s*do/g, (_, condition) => 'if (' + condition + ') {')
    .replace(/\\bfi\\b/g, '}');
  // Fail on unsupported Atto constructs rather than executing a JS rewrite
  // whose semantics might silently diverge from the .atto source.
  assert.doesNotMatch(body, /\\b(?:await|spawn|rt|new|self|for|while|match)\\b/);
  const dependenciesNames = Object.keys(dependencies);
  const js = new Function(...dependenciesNames, ...parameters, '"use strict";\\n' + body);
  return (...args) => js(...dependenciesNames.map((key) => dependencies[key]), ...args);
}

const validSeverity = pureFunction('valid_severity');
const validThreshold = pureFunction('valid_threshold', { valid_severity: validSeverity });
const enabled = pureFunction('enabled', {
  valid_severity: validSeverity,
  valid_threshold: validThreshold
});
const severityName = pureFunction('severity_name');

test('standard numeric severities and OFF are disjoint', () => {
  assert.deepEqual([10,20,30,40,50,60].map(validSeverity), [true,true,true,true,true,true]);
  for (const n of [-1,0,1,9,11,35,65,70,71,2147483647]) {
    assert.equal(validSeverity(n), false);
  }
  assert.equal(validThreshold(70), true);
  for (const n of [-1,0,15,65,71]) assert.equal(validThreshold(n), false);
});

test('level labels are stable and unrecognized values are distinct', () => {
  assert.deepEqual([10,20,30,40,50,60,70,-1].map(severityName),
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
  assert.match(source, /define module Log as/);
  assert.match(source, /pub define class LogEntry as/);
  assert.equal((source.match(/pub val /g) || []).length,11);
  assert.match(source, /pub fnc entry_with_fields\\(/);
  assert.match(source, /pub fnc valid_entry\\(LogEntry record\\)/);
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
  assert.match(source,/capacity < 1 \\|\\| capacity > 4096/);
  assert.match(source,/self.dropped < 2147483647/);
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
  assert.match(ack,/self.items = new Array<LogEntry>\\(\\)/);
  assert.match(source,/return self.items.get\\(self.head\\)/);
  assert.match(source,/return self.items.size - self.head/);
});

test('close rejects production but does not prohibit draining', () => {
  assert.match(source,/pub close\\(mut self\\) -> void/);
  assert.match(source,/self.closed = true/);
  assert.match(source,/pub is_drained\\(\\) -> bool/);
  const after = source.slice(source.indexOf('pub peek()'));
  assert.doesNotMatch(after.split('pub is_drained')[0], /if self.closed/);
});

test('no host implementation, incidental I/O, async blocking, dynamic runtime, or reflection', () => {
  assert.doesNotMatch(source, /java\\.|System\\.|interop\\.|\\bstdio\\.|\\bprintln\\s*\\(|writech|\\bspawn\\b|\\bawait\\b|\\bffi\\b|\\breflect\\b/);
  assert.doesNotMatch(source,/\\b(?:log_async|flush_async_logs)\\s*\\(/);
});
