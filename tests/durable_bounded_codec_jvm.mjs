import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
assert.ok(process.env.ATTO_JAVA_CP);
const cp=process.env.ATTO_JAVA_CP.split(':').map(x=>resolve(x)).join(':');
const dir=mkdtempSync(join(tmpdir(),'atto-bounded-jvm-'));
const emitter=join(dir,'Emitter.java'),output=join(dir,'AttoDurableBoundedV2.java'),driver=join(dir,'Driver.java');
const golden='4154544f53543032025b193c24a8de14db0400010000000000000080020103080000004100c3a9f09f9880040300000000ff8074c4cc420e041ac0';
function run(exe,args,timeout=60000){const r=spawnSync(exe,args,{encoding:'utf8',timeout,maxBuffer:1<<20});assert.equal(r.error,undefined);assert.equal(r.signal,null);assert.equal(r.status,0,r.stdout+'\n'+r.stderr);return r.stdout.trim();}
try{
 writeFileSync(emitter,`import java.nio.file.*;import java.util.*;import dev.oreslang.compiler.DurableBoundedSourceV2;import dev.oreslang.compiler.DurableBoundedSourceV2.*;
 public class Emitter {public static void main(String[] args)throws Exception{var fields=new ArrayList<Field>();String actor=args[1];if(actor.equals("Ledger")){fields.addAll(List.of(new Field("count",Kind.INT),new Field("open",Kind.BOOL),new Field("name",Kind.STRING),new Field("payload",Kind.BYTES)));}else if(actor.equals("Large")){for(String n:List.of("a","b","c","d"))fields.add(new Field(n,Kind.BYTES));}else if(actor.equals("Many")){for(int i=0;i<256;i++)fields.add(new Field("f"+i,Kind.BOOL));}else if(!actor.equals("Empty"))throw new AssertionError();Files.writeString(Path.of(args[0]),DurableBoundedSourceV2.emitJava(actor,fields));}}`);
 function emit(actor){run('java',['-cp',cp,emitter,output,actor]);return readFileSync(output,'utf8');}
 const first=emit('Ledger');assert.equal(emit('Ledger'),first);
 writeFileSync(driver,`import java.util.*;import java.nio.*;
 public class Driver {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static void reject(Runnable r){try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("accepted invalid state");}
 static void rehash(byte[] b){long h=0xcbf29ce484222325L;for(int i=0;i<b.length-8;i++)h=(h^(b[i]&255L))*0x100000001b3L;ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(b.length-8,h);}
 public static void main(String[] a){
 var v=new Object[]{Long.MIN_VALUE,true,"A\\0é😀",new byte[]{0,-1,-128}};byte[] b=AttoDurableBoundedV2.encode(v);check(HexFormat.of().formatHex(b).equals("${golden}"));check(Arrays.deepEquals(v,AttoDurableBoundedV2.decode(b)));
 for(long i=-5000;i<=5000;i++){Object[] x={i,i%2==0,"a".repeat((int)((i+5000)%257)),new byte[]{(byte)i,0,-1}};check(Arrays.deepEquals(x,AttoDurableBoundedV2.decode(AttoDurableBoundedV2.encode(x))));}
 for(int i=0;i<b.length;i++){int n=i;reject(()->AttoDurableBoundedV2.decode(Arrays.copyOf(b,n)));for(int bit=0;bit<8;bit++){byte[] bad=b.clone();bad[i]^=(byte)(1<<bit);reject(()->AttoDurableBoundedV2.decode(bad));}}
 for(int i:new int[]{8,29,31,37}){byte[] bad=b.clone();bad[i]=(byte)255;rehash(bad);reject(()->AttoDurableBoundedV2.decode(bad));}
 for(String s:new String[]{"", "\\ufeffa", "e\\u0301", "é", "\\0",new String(Character.toChars(0x10ffff))})check(AttoDurableBoundedV2.decode(AttoDurableBoundedV2.encode(0L,false,s,new byte[0]))[2].equals(s));
 for(String s:new String[]{"\\ud800","\\udc00","a\\ud800b","😀".repeat(4097),"a".repeat(16385)})reject(()->AttoDurableBoundedV2.encode(0L,false,s,new byte[0]));
 reject(()->AttoDurableBoundedV2.encode(0L,false,"",new byte[16385]));reject(()->AttoDurableBoundedV2.encode(0,false,"",new byte[0]));reject(()->AttoDurableBoundedV2.decode(new byte[65537]));
 check(((String)AttoDurableBoundedV2.decode(AttoDurableBoundedV2.encode(0L,false,"a".repeat(16384),new byte[16384]))[2]).length()==16384);
 ((byte[])v[3])[0]=9;((byte[])AttoDurableBoundedV2.decode(b)[3])[0]=9;check(HexFormat.of().formatHex(b).equals("${golden}"));
 System.out.println("JVM_BOUNDED_CODEC_V2_PASS roundtrips=10001 bitflips=472");}}
 `);
 run('javac',['-Xlint:all','-Werror','-encoding','UTF-8','-d',dir,output,driver]);assert.equal(run('java',['-Xmx256m','-cp',dir,'Driver']),'JVM_BOUNDED_CODEC_V2_PASS roundtrips=10001 bitflips=472');console.log('JVM_BOUNDED_CODEC_V2_PASS roundtrips=10001 bitflips=472');
 if(process.env.ATTO_TEST_NATIVE_IMAGE==='1'){run('native-image',['--no-fallback','-cp',dir,'-o',join(dir,'native-driver'),'Driver'],300000);assert.equal(run(join(dir,'native-driver'),[]),'JVM_BOUNDED_CODEC_V2_PASS roundtrips=10001 bitflips=472');console.log('GRAALVM_NATIVE_BOUNDED_V2_PASS');}
 emit('Large');writeFileSync(driver,`import java.util.*;public class Driver{public static void main(String[] a){Object[] v={new byte[16384],new byte[16384],new byte[16384],new byte[16337]};byte[] b=AttoDurableBoundedV2.encode(v);if(b.length!=65536||!Arrays.deepEquals(v,AttoDurableBoundedV2.decode(b)))throw new AssertionError();v[3]=new byte[16338];try{AttoDurableBoundedV2.encode(v);}catch(IllegalArgumentException e){return;}throw new AssertionError();}}`);run('javac',['-d',dir,output,driver]);run('java',['-cp',dir,'Driver']);
 emit('Many');writeFileSync(driver,'import java.util.*;public class Driver{public static void main(String[] a){Object[] v=new Object[256];Arrays.fill(v,true);if(!Arrays.deepEquals(v,AttoDurableBoundedV2.decode(AttoDurableBoundedV2.encode(v))))throw new AssertionError();}}');run('javac',['-d',dir,output,driver]);run('java',['-cp',dir,'Driver']);
 emit('Empty');writeFileSync(driver,'public class Driver{public static void main(String[] a){byte[] b=AttoDurableBoundedV2.encode();if(b.length!=27||AttoDurableBoundedV2.decode(b).length!=0)throw new AssertionError();}}');run('javac',['-d',dir,output,driver]);run('java',['-cp',dir,'Driver']);
 console.log('JVM_BOUNDED_SCHEMA_V2_PASS max_snapshot=65536 max_field=16384 max_fields=256');
}finally{rmSync(dir,{recursive:true,force:true});}
