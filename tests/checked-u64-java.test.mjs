import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const bytes=readFileSync(new URL('../contracts/atto-xo-checked-u64-debit-v1/cases.tsv',import.meta.url));
assert.equal(createHash('sha256').update(bytes).digest('hex'),'3a79dc1db8ee896fc2b39a4df136c102ebfa341f282b8d16785fb035b663ca58');
const rows=bytes.toString('utf8').trimEnd().split('\n').map(line=>{const f=line.split('|');assert.equal(f.length,9);return {id:f[0],expected:f[1],source:f[8]};});
assert.equal(rows.length,12);assert.equal(new Set(rows.map(x=>x.id)).size,12);
const dir=mkdtempSync(join(tmpdir(),'atto-u64-java-'));
const run=(cmd,args,input)=>spawnSync(cmd,args,{input,cwd:dir,encoding:'utf8',timeout:30000,maxBuffer:1024*1024});
function ok(r){assert.ifError(r.error);assert.equal(r.signal,null);assert.equal(r.status,0,r.stderr);return r.stdout;}
try {
  ok(run('javac',['-Xlint:all','-Werror','-d',dir,fileURLToPath(new URL('../tools/CheckedU64.java',import.meta.url))]));
  function lower(source){return run('java',['-cp',dir,'CheckedU64'],source);}
  function observe(source,expected){
    writeFileSync(join(dir,'AttoGuest.java'),ok(lower(source)));
    ok(run('javac',['-Xlint:all','-Werror','AttoGuest.java']));
    for(const mode of ['-Xint','-Xmixed']){
      const r=run('java',[mode,'-cp',dir,'AttoGuest']);
      if(expected==='overflow'){assert.ifError(r.error);assert.equal(r.signal,null);assert.equal(r.status,70);assert.equal(r.stdout,'');assert.equal(r.stderr,'E_U64_OVERFLOW\n');}
      else {assert.equal(ok(r),expected+'\n');assert.equal(r.stderr,'');}
    }
  }
  for(const row of rows) await test(row.id,()=>observe(row.source,row.expected));
  await test('forward call and precedence',()=>observe('pub fnc main(): uint {return later((4+5)*6)-3-2;} fnc later(u64 n):u64{return n+1;}','50'));
  await test('reject unsupported or invalid source before code emission',()=>{
    const g=x=>'pub fnc main():u64{return '+x+';}';
    for(const source of [g('18446744073709551616'),g('-1'),g('1/2'),g('x'),g('main()'),g('f(1)')+'fnc f():u64{return 1;}',g('1')+'fnc f(int n):u64{return 1;}',g('1')+'fnc f(u64 n,u64 n):u64{return n;}',g('1')+g('2'),g('1')+'fnc f():u64{return g();} fnc g():u64{return f();}',g('1'+'+1'.repeat(100)),g('('.repeat(40)+'1'+')'.repeat(40)),g('01'),g('1')+'\u202e',g('1')+' // comment',g('1')+' '.repeat(17000)]){
      const r=lower(source);assert.ifError(r.error);assert.equal(r.status,2);assert.equal(r.stdout,'');assert.match(r.stderr,/E_U64_PROFILE/);
    }
    let s=g('f15()')+'fnc f0():u64{return 1;}';for(let i=1;i<=15;i++)s+=`fnc f${i}():u64{return f${i-1}()+f${i-1}();}`;
    const r=lower(s);assert.equal(r.status,2);assert.equal(r.stdout,'');assert.match(r.stderr,/work budget/);
  });
} finally {rmSync(dir,{recursive:true,force:true});}
