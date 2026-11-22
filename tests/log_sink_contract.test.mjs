import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const sink = readFileSync(new URL('../stdlib/log_sink.atto', import.meta.url),'utf8');
const log = readFileSync(new URL('../stdlib/log.atto', import.meta.url),'utf8');

// These are source guards only; the Java/Graal compiler must execute the guest
// and the #1344 async stdio dependency before an executable sink claim.
test('sink is a separate file-level namespace that imports native log', () => {
  assert.match(sink,/^namespace log_sink;$/m);
  assert.match(sink,/import \* as log from "\.\/log\.atto";/);
  assert.match(log,/^namespace log;$/m);
  assert.doesNotMatch(sink,/define module|java\.|System\.|ffi\.|new Thread/);
});

test('sink validates record and every variable field before output', () => {
  const guard = sink.slice(sink.indexOf('pub fnc valid_for_line'),sink.indexOf('pub async routine'));
  const call = sink.slice(sink.indexOf('pub async routine'));
  assert.ok(guard.indexOf('log.valid_entry(record)') >= 0);
  for (const k of ['target','message','field1_key','field1_value','field2_key','field2_value']) {
    assert.match(guard,new RegExp('safe_component\\(record\\.'+k+','));
  }
  assert.ok(call.indexOf('valid_for_line(record)') < call.indexOf('await stdio.stdout.log_async'));
});

test('source validates one-line text and rejects line-forging delimiters', () => {
  assert.match(sink,/text\.codepoints\(\)/);
  assert.match(sink,/points\.size > max_scalars/);
  for (const cp of [127,8232,8233,124,61,58,92]) {
    assert.match(sink,new RegExp('code == '+cp));
  }
  assert.match(sink,/code < 32/);
  assert.match(sink,/max_scalars > 16384/);
});

test('only privileged async stdio receives completed line; no ACK across await', () => {
  const write = sink.slice(sink.indexOf('pub async routine write_record'));
  assert.match(write,/await stdio\.stdout\.log_async\(/);
  assert.match(write,/return true;/);
  assert.doesNotMatch(write,/\.ack_one\(/);
  assert.doesNotMatch(write,/\.offer\(/);
  assert.doesNotMatch(sink,/\.log\(/); // no synchronous logger
  assert.equal((write.match(/await /g) || []).length, 1);
});

test('only 22 fixed arguments supplied (within current async stdio 32 max)', () => {
  const call = sink.split('await stdio.stdout.log_async(')[1].split(');')[0];
  const lines = call.split('\n').map(x=>x.trim()).filter(Boolean);
  const args=lines.join(' ').split(',').map(x=>x.trim()).filter(Boolean);
  assert.equal(args.length,22);
});
