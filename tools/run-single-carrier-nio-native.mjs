import assert from 'node:assert/strict';
import net from 'node:net';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';

// Black-box packaged CLI liveness check, not a runtime trace or identity proof.
// Reuse the exact guest from the instrumented JVM test rather than duplicating it.
const [exe, expectedHash] = process.argv.slice(2);
assert.ok(exe && path.isAbsolute(exe) && /^[a-f0-9]{64}$/.test(expectedHash ?? ''),
  'usage: node tools/run-single-carrier-nio-native.mjs /abs/native-binary sha256');
const hash = async () => createHash('sha256').update(await readFile(exe)).digest('hex');
assert.equal(await hash(), expectedHash);
const java = await readFile(new URL('../src/test/java/dev/oreslang/runtime/SingleCarrierNioContinuationGuestTest.java', import.meta.url), 'utf8');
const match = java.match(/String source = """\s*([\s\S]*?)"""\.formatted\(port\);/g);
assert.equal(match?.length, 1, 'ambiguous guest source');
const guest = match[0].match(/"""\s*([\s\S]*?)"""/)[1];
assert.equal(guest.split('%d').length, 2, 'expected exactly one port placeholder');

const deadline = (promise, label, ms = 10000) => {
  let timer;
  return Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('deadline: ' + label)), ms);
  })]).finally(() => clearTimeout(timer));
};
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function run(shared) {
  const reservation = net.createServer();
  reservation.listen(0, '127.0.0.1');
  await once(reservation, 'listening');
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const dir = await mkdtemp(path.join(tmpdir(), 'atto-nio-cli-'));
  let child, exited;
  const sockets = new Set();
  try {
    const file = path.join(dir, 'causal.atto');
    await writeFile(file, guest.replace('%d', String(port)));
    const args = ['-Dores.runtime.nio.shared-selector=' + shared,
      '--control-workers=1', '--allow=NETWORK,STDOUT', '--no-prompt', file];
    child = spawn(exe, args, { shell: false, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', result;
    let spawnError;
    child.on('error', error => { spawnError = error; });
    exited = new Promise(resolve => child.once('close', (code, signal) => {
      result = { code, signal }; resolve(result);
    }));
    for (const stream of ['stdout', 'stderr']) child[stream].on('data', b => {
      if (stream === 'stdout') stdout += b.toString(); else stderr += b.toString();
      if (stdout.length + stderr.length > 65536) child.kill('SIGKILL');
    });
    const checkAlive = () => {
      if (spawnError) throw spawnError;
      assert.ok(!result, 'guest exited early: ' + JSON.stringify(result) + ' ' + stderr);
    };
    async function until(predicate, label) {
      for (let i = 0; i < 500; i++) {
        checkAlive();
        if (predicate()) return;
        await delay(10);
      }
      throw new Error('deadline: ' + label + ' stderr=' + stderr);
    }
    async function connect() {
      for (let i = 0; i < 500; i++) {
        checkAlive();
        const s = net.createConnection({ host: '127.0.0.1', port });
        sockets.add(s);
        try { await once(s, 'connect'); return s; }
        catch (e) { s.destroy(); sockets.delete(s); if (e.code !== 'ECONNREFUSED') throw e; }
        await delay(10);
      }
      throw new Error('listener deadline');
    }
    function readAll(s) {
      const chunks = [];
      s.on('data', x => chunks.push(x));
      return once(s, 'end').then(() => Buffer.concat(chunks));
    }
    const a = await connect();
    const aRead = readAll(a);
    // Attach rejection handlers immediately; the final awaits still propagate errors.
    aRead.catch(() => {});
    await until(() => stdout.includes('ARMED|'), 'first task armed');
    assert.ok(!stdout.includes('RESUMED|'));
    const b = await connect();
    const bRead = readAll(b);
    bRead.catch(() => {});
    b.write(Buffer.from([7]));
    assert.deepEqual(await deadline(bRead, 'second task echo and EOF'), Buffer.from([7]));
    await until(() => stdout.includes('SIGNAL|'), 'second task signal');
    assert.ok(!stdout.includes('RESUMED|'), 'first task resumed without external release');
    a.write(Buffer.from([42]));
    assert.deepEqual(await deadline(aRead, 'first task echo and EOF'), Buffer.from([42]));
    assert.deepEqual(await deadline(exited, 'native process retirement'), { code: 0, signal: null }, stderr);
    assert.equal(stdout, 'ARMED|SIGNAL|RESUMED|');
    assert.equal(await hash(), expectedHash);
    console.log('NATIVE_CLI_CAUSAL_TCP_PASS shared=' + shared);
  } finally {
    for (const s of sockets) s.destroy();
    if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    if (exited) await deadline(exited, 'cleanup', 5000);
    await rm(dir, { recursive: true, force: true });
  }
}
await run(false);
await run(true);
console.log('SCOPE=PACKAGED_NATIVE_CLI_CAUSAL_TCP_CONFIGURED_ONE_CARRIER_NOT_INTERNAL_TRACE');
