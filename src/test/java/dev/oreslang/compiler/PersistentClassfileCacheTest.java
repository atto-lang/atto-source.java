package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.net.URLClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/** Closed scalar compiler-backed classfile persistence fixture, not a
 * production checked-AST serializer, native image or general cache API.
 * Host owns private directory and all ancestors; no hostile filesystem writer.
 * Descriptor-relative Java NIO fixture. Requires a SecureDirectoryStream
 * provider and trusted/cooperating filesystem writers. Java does not expose
 * O_PATH, inode link count or directory fsync; not native Linux parity. */
public final class PersistentClassfileCacheTest {
    private static final String A="pub fnc tick(i32 x): i32 { return x + 1; }";
    private static final String B="pub fnc tick(i32 x): i32 { return x + 2; }";
    private static final String BAD="pub fnc tick(i32 x): i32 { return missing; }";
    private static final byte[] MASTER=new byte[32];
    static { Arrays.fill(MASTER,(byte)7); }
    private static byte[] hash(byte[] bytes)throws Exception{return MessageDigest.getInstance("SHA-256").digest(bytes);}
    private static String digest(String text)throws Exception{return HexFormat.of().formatHex(hash(text.getBytes(StandardCharsets.UTF_8)));}
    private static String key(String source)throws Exception{
        // Fixture-only closure: full source compiler/toolchain identity would
        // be supplied by a protected host for production activation.
        return CompilerCacheKey.encode(new CompilerCacheKey.Input("jvm-classfile","@test/java-cache","source-unit","main.atto",
                digest("fixture exact compiler checkout"),digest(System.getProperty("java.runtime.version")),
                digest("jvm-classfile8"),digest("tick/1;budget=200,3"),digest(source),digest("no imports"),null,List.of()));
    }
    private static byte[] secret(byte[] pin)throws Exception{
        return CompilerCacheLedger.admit("tenant/a",CompilerCacheLedger.record("tenant/a",2,MASTER),pin,MASTER);
    }
    private static byte[] currentPin()throws Exception{return hash(CompilerCacheLedger.record("tenant/a",2,MASTER));}
    private static Path slot(Path root,String source)throws Exception{return root.resolve("slot-"+key(source).charAt(0));}
    private static byte[] compile(Path root,String source)throws Exception{
        var out=Files.createTempDirectory(root,"compiler-");
        try{
            var artifact=OresCompiler.compileRealtimePrimitive(source,"tick",1,new RealtimePrimitiveCompiler.Budget(200,3),out);
            return Files.readAllBytes(artifact.classFile());
        }finally{try(var paths=Files.walk(out)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static final class Directory implements AutoCloseable {
        private final SecureDirectoryStream<Path> stream;
        private final PosixFileAttributes identity;
        private Directory(SecureDirectoryStream<Path> stream)throws IOException{
            this.stream=stream;identity=stream.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
            if(!identity.isDirectory()||identity.fileKey()==null||
                !identity.permissions().equals(PosixFilePermissions.fromString("rwx------")))
                throw new IOException("E_CACHE_PATH: private root required");
        }
        static SecureDirectoryStream<Path> secure(DirectoryStream<Path> d)throws IOException{
            if(d instanceof SecureDirectoryStream<Path> s)return s;
            d.close();throw new IOException("E_CACHE_PATH: secure directory provider required");
        }
        static Directory open(Path root)throws IOException{
            if(!root.isAbsolute())root=root.toAbsolutePath();
            var current=secure(Files.newDirectoryStream(root.getRoot()));
            try{
                for(Path part:root){
                    if(part.toString().equals(".."))throw new IOException("E_CACHE_PATH: traversal");
                    if(part.toString().equals("."))continue;
                    var next=current.newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS);current.close();current=next;
                }
                return new Directory(current);
            }catch(IOException|RuntimeException e){current.close();throw e;}
        }
        private PosixFileAttributes attributes(Path name)throws IOException{
            try{
                var a=stream.getFileAttributeView(name,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
                if(!a.isRegularFile()||!a.owner().equals(identity.owner())||
                    !a.permissions().equals(PosixFilePermissions.fromString("rw-------"))||
                    a.size()<CompilerArtifactReceipt.HEADER||a.size()>CompilerArtifactReceipt.HEADER+CompilerArtifactReceipt.MAX_ARTIFACT)
                    throw new IOException("E_CACHE_PATH: bounded private regular file required");
                return a;
            }catch(NoSuchFileException e){return null;}
        }
        byte[] read(Path name)throws IOException{
            var a=attributes(name);if(a==null)return null;
            try(var channel=stream.newByteChannel(name,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){
                var buffer=ByteBuffer.allocate((int)a.size());
                while(buffer.hasRemaining()){if(channel.read(buffer)<=0)throw new IOException("E_CACHE_PATH: short artifact");}
                if(channel.read(ByteBuffer.allocate(1))!=-1)throw new IOException("E_CACHE_PATH: artifact grew");
                return buffer.array();
            }
        }
        void publish(Path name,byte[] bytes)throws IOException{
            // Fixed exclusive per-slot staging; never delete a different writer's
            // staging file. Abandoned staging requires offline cleanup.
            attributes(name);Path pending=Path.of(".pending-"+name);boolean owned=false;
            try{
                try(var channel=stream.newByteChannel(pending,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
                    owned=true;var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);
                    if(!(channel instanceof FileChannel file))throw new IOException("E_CACHE_PATH: file sync unavailable");
                    file.force(true);
                }
                attributes(name);stream.move(pending,stream,name);owned=false;
            }finally{if(owned)stream.deleteFile(pending);}
        }
        public void close()throws IOException{stream.close();}
    }
    private static byte[] build(Path root,String source,boolean cold,boolean requireHit,byte[] pin)throws Exception{
        var k=key(source);var name=slot(root,source).getFileName();var s=secret(pin);
        try(var dir=Directory.open(root)){
            if(!cold){
                var envelope=dir.read(name);
                if(envelope!=null){
                    var actual=HexFormat.of().formatHex(envelope,8,40);
                    if(actual.charAt(0)!=k.charAt(0))throw new IllegalArgumentException("E_CACHE_READ");
                    var bytes=CompilerArtifactReceipt.verify(actual,envelope,s);
                    if(actual.equals(k))return bytes;
                }
            }
            if(requireHit)throw new AssertionError("restart attempted compilation");
            byte[] bytes=compile(root,source);
            dir.publish(name,CompilerArtifactReceipt.seal(k,bytes,s));return bytes;
        }finally{Arrays.fill(s,(byte)0);}
    }
    private static void execute(Path root,byte[] bytes,int expected)throws Exception{
        var dir=Files.createTempDirectory(root,"guest-");
        try{
            Files.write(dir.resolve(RealtimePrimitiveCompiler.CLASS_NAME+".class"),bytes);
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
                var method=Class.forName(RealtimePrimitiveCompiler.CLASS_NAME,true,loader).getDeclaredMethod("tick",int.class);
                method.setAccessible(true);assertEquals(expected,method.invoke(null,41));
            }
        }finally{Files.deleteIfExists(dir.resolve(RealtimePrimitiveCompiler.CLASS_NAME+".class"));Files.delete(dir);}
    }
    @Test void persistentCompilerClassfile(@TempDir Path root)throws Exception{
        byte[] pin=currentPin();
        var cold=build(root,A,true,false,pin);assertArrayEquals(cold,compile(root,A));execute(root,cold,42);
        var cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",cp,
                PersistentClassfileCacheTest.class.getName(),root.toString()).inheritIO().start();
        assertTrue(child.waitFor(90,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,child.exitValue());
        var edited=build(root,B,false,false,pin);assertArrayEquals(edited,compile(root,B));execute(root,edited,43);
        var path=slot(root,B);var original=Files.readAllBytes(path);
        assertThrows(IllegalArgumentException.class,()->build(root,BAD,true,false,pin));
        assertArrayEquals(original,Files.readAllBytes(path));assertArrayEquals(edited,build(root,B,false,true,pin));
        assertThrows(IllegalArgumentException.class,()->build(root,B,false,true,new byte[32]));
        var saved=path.resolveSibling("saved");Files.move(path,saved);Files.createSymbolicLink(path,saved);
        try{assertThrows(IOException.class,()->build(root,B,false,true,pin));}finally{Files.delete(path);Files.move(saved,path);}
        var real=Files.createDirectory(root.resolve("real"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var alias=Files.createSymbolicLink(root.resolve("alias"),real);
        try{assertThrows(IOException.class,()->Directory.open(alias));}finally{Files.delete(alias);Files.delete(real);}
        var pending=path.resolveSibling(".pending-"+path.getFileName());
        Files.writeString(pending,"occupied",StandardOpenOption.CREATE_NEW);
        try{assertThrows(FileAlreadyExistsException.class,()->build(root,B,true,false,pin));assertEquals("occupied",Files.readString(pending));}
        finally{Files.delete(pending);}
        assertArrayEquals(edited,build(root,B,false,true,pin));
        System.out.println("JAVA_SECURE_DIRECTORY|symlink|ancestor-denied|staging-contention");
        original[80]^=1;Files.write(path,original);
        assertThrows(IllegalArgumentException.class,()->build(root,B,false,true,pin));
        System.out.println("PERSISTENT_JAVA_CLASSFILE|cold=42|warm-child=42|edit=43|rollback|corruption-denied");
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);execute(root,build(root,A,false,true,currentPin()),42);
        System.out.println("PERSISTENT_JAVA_CHILD|cache-hit-no-compile|guest=42");
    }
}
