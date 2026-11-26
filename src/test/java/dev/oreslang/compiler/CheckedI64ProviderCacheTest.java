package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class CheckedI64ProviderCacheTest {
    static final byte[] MASTER=new byte[32];
    static {Arrays.fill(MASTER,(byte)7);}
    static byte[] hash(byte[] b)throws Exception{return MessageDigest.getInstance("SHA-256").digest(b);}
    static String digest(String s)throws Exception{return HexFormat.of().formatHex(hash(s.getBytes(StandardCharsets.UTF_8)));}
    static List<PersistentPrimitiveClassfileSession.ProviderInput> inventory(String expression,String other){
        return List.of(new PersistentPrimitiveClassfileSession.ProviderInput("src/billing.atto","billing","get",2,
            "namespace billing;\npub fnc get(int x, int y): int { return "+expression+"; }\n"),
            new PersistentPrimitiveClassfileSession.ProviderInput("src/identity.atto","identity","other",0,
            "namespace identity;\npub fnc other(): int { return "+other+"; }\n"));
    }
    static String[] pins(List<PersistentPrimitiveClassfileSession.ProviderInput> inputs)throws Exception{
        var m=new StringBuilder("atto.java.checked-i64-provider-inventory.v1\n");
        var s=new StringBuilder("atto.java.checked-i64-provider-sources.v1\n");
        for(var p:inputs.stream().sorted(Comparator.comparing(PersistentPrimitiveClassfileSession.ProviderInput::path)).toList()){
            m.append(p.path()).append('\n').append(p.namespace()).append('\n').append(p.symbol()).append('\n').append(p.arity()).append('\n');
            s.append(p.path()).append('\n').append(digest(p.source())).append('\n');
        }return new String[]{digest(m.toString()),digest(s.toString())};
    }
    static PersistentPrimitiveClassfileSession.Build build(Path root,List<PersistentPrimitiveClassfileSession.ProviderInput> inputs,String[] pins,boolean cold)throws Exception{
        var session=new PersistentPrimitiveClassfileSession(root,"tenant/i64",digest("fixture exact compiler"),digest(System.getProperty("java.runtime.version")));
        byte[] record=CompilerCacheLedger.record("tenant/i64",2,MASTER);
        return session.compileCheckedI64Provider(inputs,pins[0],pins[1],"billing","get",2,
            new RealtimePrimitiveCompiler.Budget(256,3),record,hash(record),MASTER,cold);
    }
    static PersistentPrimitiveClassfileSession.Build build(Path root,String expr,String other,boolean cold)throws Exception{
        var inputs=inventory(expr,other);return build(root,inputs,pins(inputs),cold);
    }
    static java.lang.reflect.Method entry(byte[] bytes)throws Exception{
        class GuestLoader extends ClassLoader{
            GuestLoader(){super(ClassLoader.getPlatformClassLoader());}
            Class<?> define(){return defineClass(CheckedI64ProviderClassfile.CLASS_NAME,bytes,0,bytes.length);}
        }
        return new GuestLoader().define().getMethod("invoke",long.class,long.class);
    }
    static void overflow(java.lang.reflect.Method m,long x,long y){
        var e=assertThrows(InvocationTargetException.class,()->m.invoke(null,x,y));
        assertInstanceOf(ArithmeticException.class,e.getCause());
    }
    @Test void checkedI64Persistent(@TempDir Path root)throws Exception{
        var a=build(root,"x * y + 2","900",true);assertFalse(a.reused());
        var fn=entry(a.bytes());assertEquals(42L,fn.invoke(null,10L,4L));
        assertEquals(8_000_000_002L,fn.invoke(null,4_000_000_000L,2L));
        assertEquals(-38L,fn.invoke(null,-10L,4L));
        overflow(fn,Long.MAX_VALUE,2L);overflow(fn,Long.MAX_VALUE,1L);
        assertEquals(42L,fn.invoke(null,10L,4L));
        var clean=CheckedI64ProviderClassfile.admit(OresCompiler.parseAndTypeCheck("pub fnc get(int x, int y): int { return x * y + 2; }"),"get",2,new RealtimePrimitiveCompiler.Budget(256,3)).emitClassfile();
        assertArrayEquals(clean,a.bytes());
        var warm=build(root,"x * y + 2","901",false);assertTrue(warm.reused());assertArrayEquals(a.bytes(),warm.bytes());
        var local=inventory("x * y + 2","901");
        assertThrows(IllegalArgumentException.class,()->build(root,local,pins(inventory("x * y + 2","900")),false));
        assertThrows(IllegalArgumentException.class,()->build(root,"x * y + 2","missing",false));
        assertThrows(IllegalArgumentException.class,()->build(root,"x / y","901",false));
        assertTrue(build(root,"x * y + 2","901",false).reused());
        Path log=Files.createTempFile("atto-i64-child-",".log");
        try{
            String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                CheckedI64ProviderCacheTest.class.getName(),root.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!child.waitFor(90,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("child timeout");}
            String out=Files.readString(log);assertEquals(0,child.exitValue(),out);
            assertTrue(out.contains("JAVA_I64_BOUND_CHILD|hit|guest=42"),out);
            System.out.println("JAVA_I64_BOUND_CHILD|hit|guest=42");
        }finally{Files.deleteIfExists(log);}
        var b=build(root,"x * y + 3","901",false);assertFalse(b.reused());assertEquals(43L,entry(b.bytes()).invoke(null,10L,4L));
        var n=build(root,"-x","901",false);overflow(entry(n.bytes()),Long.MIN_VALUE,0L);
        assertEquals(Long.MAX_VALUE,entry(n.bytes()).invoke(null,-Long.MAX_VALUE,0L));
        var sub=build(root,"x - y","901",false);overflow(entry(sub.bytes()),Long.MIN_VALUE,1L);
        assertEquals(Long.MIN_VALUE,entry(sub.bytes()).invoke(null,Long.MIN_VALUE,0L));
        System.out.println("JAVA_I64_BOUND_PROVIDER|42/42/43|wide=8000000002|overflow-traps|restart-hit|clean-equivalent");
    }
    @Test void independentAuditRejectsMalformedAndAuthenticatedInvalidArtifact(@TempDir Path root)throws Exception{
        var a=build(root,"x * y + 2","900",true);
        byte[] original=a.bytes();
        CheckedI64ClassfileAudit.audit(original,2,256);
        assertThrows(IllegalArgumentException.class,()->CheckedI64ClassfileAudit.audit(original,1,256));
        assertThrows(IllegalArgumentException.class,()->CheckedI64ClassfileAudit.audit(original,2,1));
        for(int n=0;n<original.length;n++){
            byte[] truncated=Arrays.copyOf(original,n);
            assertThrows(IllegalArgumentException.class,()->CheckedI64ClassfileAudit.audit(truncated,2,256));
        }
        byte[] trailing=Arrays.copyOf(original,original.length+1);
        assertThrows(IllegalArgumentException.class,()->CheckedI64ClassfileAudit.audit(trailing,2,256));
        // Last instruction is lreturn, followed by three zero u2 fields.
        int ret=original.length-7;
        assertEquals(0xad,original[ret]&255);assertEquals(0xb8,original[ret-3]&255);
        byte[] external=original.clone();external[ret-1]=19;
        byte[] unchecked=original.clone();unchecked[ret-3]=0x61; // unchecked ladd
        byte[] noReturn=original.clone();noReturn[ret]=0;
        byte[] stack=original.clone();stack[ret-1]=23; // negateExact leaves extra operand
        for(byte[] invalid:List.of(external,unchecked,noReturn,stack))
            assertThrows(IllegalArgumentException.class,()->CheckedI64ClassfileAudit.audit(invalid,2,256));
        byte[] badOwner=original.clone();
        byte[] name="java/lang/Math".getBytes(StandardCharsets.US_ASCII);
        int offset=-1;
        outer:for(int i=0;i<=badOwner.length-name.length;i++){
            for(int j=0;j<name.length;j++)if(badOwner[i+j]!=name[j])continue outer;
            offset=i;break;
        }
        assertTrue(offset>=0);badOwner[offset+name.length-1]='X';
        assertThrows(IllegalArgumentException.class,()->CheckedI64ClassfileAudit.audit(badOwner,2,256));
        Path slot;try(var paths=Files.list(root)){
            slot=paths.filter(p->p.getFileName().toString().startsWith("slot-")).findFirst().orElseThrow();
        }
        byte[] envelope=Files.readAllBytes(slot);
        String key=HexFormat.of().formatHex(envelope,8,40);
        byte[] record=CompilerCacheLedger.record("tenant/i64",2,MASTER);
        byte[] secret=CompilerCacheLedger.admit("tenant/i64",record,hash(record),MASTER);
        try{
            // Simulate an authenticated but structurally invalid publisher artifact.
            byte[] poison=CompilerArtifactReceipt.seal(key,external,secret);
            Files.write(slot,poison);
            var ex=assertThrows(IllegalArgumentException.class,()->build(root,"x * y + 2","901",false));
            assertTrue(ex.getMessage().startsWith("E_I64_CLASSFILE_AUDIT:"));
            assertArrayEquals(poison,Files.readAllBytes(slot));
        }finally{Arrays.fill(secret,(byte)0);Files.write(slot,envelope);}
        assertTrue(build(root,"x * y + 2","901",false).reused());
        System.out.println("JAVA_I64_AUDIT|truncation|trailing|abi|budget|external-call|unchecked-op|stack|owner|authenticated-invalid-denied|restored-hit");
    }
    public static void main(String[] args)throws Exception{
        var b=build(Path.of(args[0]),"x * y + 2","901",false);
        assertTrue(b.reused());assertEquals(42L,entry(b.bytes()).invoke(null,10L,4L));
        System.out.println("JAVA_I64_BOUND_CHILD|hit|guest=42");
    }
}
