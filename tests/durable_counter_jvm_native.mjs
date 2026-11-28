import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {existsSync,mkdtempSync,readFileSync,rmSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
const root=process.cwd();
const cp=process.env.ATTO_JAVA_CP;
assert.ok(cp,'exact compiler classpath required');
const dir=mkdtempSync(join(tmpdir(),'atto-java-durable-'));
const source=`define durable actor Counter as
    state count: int = 0;
    receive(ActorMail<int> mail) -> void {
        self.count = self.count + mail.value;
    }
end
`;
function run(command,args,input,timeout=30000){
  const r=spawnSync(command,args,{cwd:root,encoding:'utf8',input,timeout,maxBuffer:8<<20});
  assert.equal(r.error,undefined);return r;
}
function emit(text,ok=true){
  const input=join(dir,'counter.atto'),output=join(dir,'AttoDurableCounterV1.java');
  writeFileSync(input,text);rmSync(output,{force:true});
  const r=run('java',['-cp',cp,'dev.oreslang.launcher.OresMain','--durable-counter-actor-v1',input,'-o',output]);
  assert.equal(r.status,ok?0:1,r.stderr);
  assert.equal(existsSync(output),ok);
  if(!ok){assert.ok(r.stderr.trim());return;}
  return readFileSync(output);
}
function descriptor(field){
  const fnv=bytes=>{let h=0xcbf29ce484222325n;for(const b of bytes)h=BigInt.asUintN(64,(h^BigInt(b))*0x100000001b3n);return h;};
  const le=(n,size)=>{const b=Buffer.alloc(size);for(let i=0;i<size;i++)b[i]=Number((BigInt(n)>>BigInt(8*i))&255n);return b;};
  const actor=Buffer.from('Counter'),name=Buffer.from(field);
  const id=fnv(Buffer.concat([Buffer.from('atto.actor.state.schema.v1'),Buffer.from([0]),le(actor.length,4),actor,le(name.length,4),name,Buffer.from([1])]));
  const data=Buffer.concat([Buffer.from('ATTOST01'),Buffer.from([1]),le(id,8),le(1,2),Buffer.from([1]),le(0,8)]);
  const checkpoint=Buffer.concat([data,le(fnv(data),8)]);
  return Buffer.concat([Buffer.from([1,actor.length]),actor,Buffer.from([name.length]),name,Buffer.from([1,checkpoint.length]),checkpoint]);
}
try{
  const first=emit(source);assert.deepEqual(first,emit(source));
  assert.notDeepEqual(first,emit(source.replaceAll('count','balance').replaceAll('mail','event')));
  assert.deepEqual(first,emit(source.replaceAll('mail','event')));
  for(const bad of [
    source.replace('durable actor','actor'),source.replace('durable actor','persistent actor'),
    source.replace('= 0;','= 1;'),source.replace('state count','pub state count'),
    source.replace('self.count + mail.value','self.count - mail.value'),
    source.replace('mail.value','9'),source.replace('ActorMail<int>','ActorMail<bool>'),
    source.replace('self.count =','self.unknown ='),source.replace('receive(','pub receive('),
    source.replace('self.count = self.count + mail.value;','self.count = self.count + mail.value; self.count = 0;'),
    source+'\npub fnc main()->int {return 0;}',source+'//\u0000',source.replaceAll('count','self'),
    source.replace('durable','"durable"'),source.replace('count:', '"count":'),source.replace('= 0;', '= "0";')
  ])emit(bad,false);
  const invalid=join(dir,'volatile-denial.atto');writeFileSync(invalid,source+'\npub fnc main(): int {return 0;}');
  const denial=run('java',['-cp',cp,'dev.oreslang.launcher.OresMain','--check',invalid]);
  assert.ok(denial.status!==0&&denial.stderr.trim());
  emit(source);
  const driver=join(dir,'Driver.java');
  writeFileSync(driver,`public class Driver {
    public static void main(String[] args) throws Exception {
      System.out.println(java.util.HexFormat.of().formatHex(AttoDurableCounterV1.descriptor()));
      var reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
      for(String line;(line=reader.readLine())!=null;) {
        String[] p=line.split(" ");
        if(p.length!=3)throw new IllegalArgumentException("bad vector");
        System.out.println(AttoDurableCounterV1.transition(Long.parseLong(p[0]),Long.parseLong(p[1]),Long.parseLong(p[2])));
      }
    }
  }`);
  const javac=run('javac',['-d',dir,'-Xlint:all','-Werror',join(dir,'AttoDurableCounterV1.java'),driver]);
  assert.equal(javac.status,0,javac.stderr);
  const vectors=[],expected=[];
  const add=(s,o,d)=>{vectors.push([s,o,d]);expected.push(s<0n||s>1000000000n?-1n:o===2n?(d===0n?s:-1n):o!==1n||d<=0n||d>1000000000n||s+d>1000000000n?-1n:s+d);};
  for(let i=0n;i<100n;i++)add(i,1n,1n);
  for(const s of [-1n,0n,100n,999999999n,1000000000n,1000000001n])
    for(const o of [0n,1n,2n,3n])
      for(const d of [-1n,0n,1n,42n,1000000000n,1000000001n])add(s,o,d);
  const input=vectors.map(v=>v.join(' ')).join('\n')+'\n';
  function verify(g){
    assert.equal(g.status,0,g.stderr);assert.equal(g.stderr,'');
    const [hex,...values]=g.stdout.trim().split('\n');
    assert.equal(hex,descriptor('count').toString('hex'));
    assert.equal(values.length,244);assert.deepEqual(values.map(BigInt),expected);
  }
  verify(run('java',['-cp',dir,'Driver'],input));
  console.log('JAVA_JVM_DURABLE_SCHEMA_AND_244_TRANSITIONS_PASS');
  if(process.env.ATTO_TEST_NATIVE_IMAGE==='1'){
    const app=join(dir,'durable-guest');
    const ni=run('native-image',['--no-fallback','-cp',dir,'Driver',app],undefined,240000);
    assert.equal(ni.status,0,ni.stdout+'\n'+ni.stderr);
    assert.deepEqual(readFileSync(app).subarray(0,4),Buffer.from([127,69,76,70]));
    verify(run(app,[],input));
    console.log('JAVA_GRAAL_NATIVE_DURABLE_SCHEMA_AND_244_TRANSITIONS_PASS');
  }
}finally{rmSync(dir,{recursive:true,force:true});}
