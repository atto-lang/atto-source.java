import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync,writeFileSync,readFileSync,rmSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
const cp=process.env.ATTO_JAVA_CP;
assert.ok(cp,'compiled production emitter classpath required');
const cli=process.env.ATTO_TEST_CLI==='1';
const native=process.env.ATTO_TEST_NATIVE_IMAGE==='1';
assert.ok(!native||cli,'native CI must exercise production CLI');
const dir=mkdtempSync(join(tmpdir(),'atto-scalar-jvm-'));
const source='define durable actor Ledger as state balance: int = -9223372036854775808; state open: bool = true; state last: int = 9223372036854775807; end';
const golden='4154544f5354303101f9b8eff04a5474590300010000000000000080020101ffffffffffffff7f4191e1156e4df645';
const input=join(dir,'ledger.atto'),output=join(dir,'AttoDurableScalarV1.java'),helper=join(dir,'Emitter.java');
const classpath=cp.split(':').map(x=>resolve(x)).join(':');
function run(exe,args,timeout=60000,ok=true){const r=spawnSync(exe,args,{encoding:'utf8',timeout,maxBuffer:1<<20});assert.equal(r.error,undefined);assert.equal(r.signal,null);if(ok)assert.equal(r.status,0,r.stderr);else assert.notEqual(r.status,0);return r.stdout.trim();}
function emit(text,ok=true){writeFileSync(input,text);rmSync(output,{force:true});run('java',cli?['-cp',classpath,'dev.oreslang.launcher.OresMain','--durable-scalar-codec-v1',input,'-o',output]:['-cp',classpath,helper,input,output],60000,ok);assert.equal(existsSync(output),ok);}
try{
 writeFileSync(helper,`import java.nio.file.*; import dev.oreslang.compiler.DurableScalarSourceV1;
 public class Emitter {public static void main(String[] a)throws Exception {Files.writeString(Path.of(a[1]),DurableScalarSourceV1.emitJava(Files.readString(Path.of(a[0]))));}}`);
 emit(source);const generated=readFileSync(output,'utf8');emit(source);assert.equal(readFileSync(output,'utf8'),generated);
 const driver=join(dir,'Driver.java');
 writeFileSync(driver,`import java.util.*;import java.nio.*;
 public class Driver {
 static void check(boolean x){if(!x)throw new AssertionError();}
 static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("accepted invalid state");}
 static void rehash(byte[] b){long h=0xcbf29ce484222325L;for(int i=0;i<b.length-8;i++)h=(h^(b[i]&255L))*0x100000001b3L;ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(b.length-8,h);}
 public static void main(String[] args){
 byte[] b=AttoDurableScalarV1.initial();check(HexFormat.of().formatHex(b).equals("${golden}"));check(Arrays.equals(AttoDurableScalarV1.decode(b),new Object[]{Long.MIN_VALUE,true,Long.MAX_VALUE}));
 for(long i=-5000;i<=5000;i++){Object[] v={i,i%2==0,-i};check(Arrays.equals(v,AttoDurableScalarV1.decode(AttoDurableScalarV1.encode(v))));}
 for(int i=0;i<b.length;i++){final int n=i;rejects(()->AttoDurableScalarV1.decode(Arrays.copyOf(b,n)));for(int bit=0;bit<8;bit++){byte[] bad=b.clone();bad[i]=(byte)(bad[i]^(1<<bit));rejects(()->AttoDurableScalarV1.decode(bad));}}
 byte[] bad=b.clone();bad[29]=2;rehash(bad);rejects(()->AttoDurableScalarV1.decode(bad));rejects(()->AttoDurableScalarV1.decode(Arrays.copyOf(b,b.length+1)));
 rejects(()->AttoDurableScalarV1.decode(null));rejects(()->AttoDurableScalarV1.encode((Object[])null));rejects(()->AttoDurableScalarV1.encode(0,true,0L));rejects(()->AttoDurableScalarV1.encode(0L,1,0L));rejects(()->AttoDurableScalarV1.encode(0L,true));
 b[0]=0;check(HexFormat.of().formatHex(AttoDurableScalarV1.initial()).equals("${golden}"));
 System.out.println("JVM_SCALAR_CODEC_PASS roundtrips=10001 bitflips=376");}}
 `);
 run('javac',['-Xlint:all','-Werror','-d',dir,output,driver]);const receipt=run('java',['-cp',dir,'Driver']);assert.equal(receipt,'JVM_SCALAR_CODEC_PASS roundtrips=10001 bitflips=376');console.log(receipt);
 if(native){run('native-image',['--no-fallback','-cp',dir,'-o',join(dir,'native-driver'),'Driver'],300000);assert.equal(run(join(dir,'native-driver'),[]),receipt);console.log('GRAALVM_NATIVE_SCALAR_CODEC_PASS');}
 for(const [s,expected] of [
 ['define durable actor Counter as state count: int = 100; state enabled: bool = true; end','4154544f5354303101c9a1af17fc8684bd020001640000000000000002018fd7fcf2f2744e29'],
 ['define durable actor Counter as state count: int = -9223372036854775808; state enabled: bool = false; end','4154544f5354303101c9a1af17fc8684bd0200010000000000000080020058ebbfc357901b30'],
 ['define durable actor EmptyActor as end','4154544f5354303101c3fbc65066197bff0000b3c015f0f5029da5'],
 ['define durable actor OtherCounter as state count: int = 100; end','4154544f53543031010201d7f4b9c36b6c0100016400000000000000eb0202552d636527']]){
 emit(s);writeFileSync(driver,'import java.util.*;public class Driver {public static void main(String[] a){byte[] b=AttoDurableScalarV1.initial();if(!Arrays.equals(b,AttoDurableScalarV1.encode(AttoDurableScalarV1.decode(b))))throw new AssertionError();System.out.println(HexFormat.of().formatHex(b));}}');run('javac',['-d',dir,output,driver]);assert.equal(run('java',['-cp',dir,'Driver']),expected);
 }
 const fields=Array.from({length:256},(_,i)=>`state f${i}: int = ${i};`).join(' ');
 emit('define durable actor Maximum as '+fields+' end');run('javac',['-d',dir,output]);writeFileSync(driver,'public class Driver {public static void main(String[] a){byte[] b=AttoDurableScalarV1.initial();Object[] v=AttoDurableScalarV1.decode(b);if(b.length!=2331||v.length!=256)throw new AssertionError();for(int i=0;i<256;i++)if(!v[i].equals((long)i))throw new AssertionError();}}');run('javac',['-cp',dir,'-d',dir,driver]);run('java',['-cp',dir,'Driver']);
 for(const s of [source.replace('durable','persistent'),source.replace('balance','self'),source.replace('state last','state balance'),source.replace('-9223372036854775808','-9223372036854775809'),source.replace('9223372036854775807','9223372036854775808'),source.replace('bool = true','bool = 1'),source.replace('bool = true','Socket = true'),source+' //comment',source+'\u0000',source+' fnc main(): int {return 0;}',source.replace('Ledger','"Ledger"'),source.replace('int = -9223372036854775808','int = 00'),'define durable actor Maximum as '+fields+' state extra: bool = true; end'])emit(s,false);
 console.log('JVM_SCALAR_SCHEMA_PASS shared_golden=4 max_fields=256 rejected_sources=13 cli='+cli+' native='+native);
}finally{rmSync(dir,{recursive:true,force:true});}
