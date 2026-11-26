package dev.oreslang.compiler;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/** Experimental host-only closed primitive classfile cache. The host attests
 * compiler/JDK identity and external current pin, and owns a private root and
 * ancestors. Requires a SecureDirectoryStream provider. NIO cannot validate
 * link count, pin a regular inode with O_PATH or fsync a directory. No hostile
 * writer guarantee, default CLI activation, or native-machine-code caching.
 * The returned classfile is not a timing or deployment certificate. */
public final class PersistentPrimitiveClassfileSession {
    private final Path root;
    private final Object identity;
    private final String scope,compiler,toolchain;
    public record Build(byte[] bytes,boolean reused){
        public Build { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
    }
    public PersistentPrimitiveClassfileSession(Path root,String scope,String compiler,String toolchain)throws IOException{
        this.root=root.toAbsolutePath();this.scope=scope;this.compiler=compiler;this.toolchain=toolchain;
        try(var dir=Directory.open(this.root)){identity=dir.identity.fileKey();}
    }
    private static String digest(String text){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private String key(String source,String entry,int arity,RealtimePrimitiveCompiler.Budget budget){
        if(source==null||source.length()>65536||!StandardCharsets.UTF_8.newEncoder().canEncode(source)||
            source.getBytes(StandardCharsets.UTF_8).length>65536||source.indexOf('\0')>=0||
            entry==null||!entry.matches("[A-Za-z_][A-Za-z_0-9]{0,127}")||arity<0||arity>32||budget==null)
            throw new IllegalArgumentException("E_CACHE_SOURCE: primitive profile");
        return CompilerCacheKey.encode(new CompilerCacheKey.Input("jvm-classfile",scope,"source-unit","main.atto",
            compiler,toolchain,digest("jvm-classfile8"),digest("primitive-v1:"+entry+":"+arity+":"+budget.maxSourceSteps()+":"+budget.maxCallDepth()),
            digest(source),digest("closed module; no imports"),null,List.of()));
    }
    public Build compile(String source,String entry,int arity,RealtimePrimitiveCompiler.Budget budget,
                         byte[] ledger,byte[] pin,byte[] master,boolean cold)throws IOException{
        String key=key(source,entry,arity,budget);Path name=Path.of("slot-"+key.charAt(0));
        byte[] secret=CompilerCacheLedger.admit(scope,ledger,pin,master);
        try(var dir=Directory.open(root)){
            if(!identity.equals(dir.identity.fileKey()))throw new IOException("E_CACHE_PATH: root changed");
            if(!cold){
                byte[] envelope=dir.read(name);
                if(envelope!=null){
                    String actual=HexFormat.of().formatHex(envelope,8,40);
                    if(actual.charAt(0)!=key.charAt(0))throw new IllegalArgumentException("E_CACHE_READ: wrong slot");
                    byte[] bytes=CompilerArtifactReceipt.verify(actual,envelope,secret);
                    if(actual.equals(key)){
                        RealtimeClassfileAudit.audit(bytes);
                        return new Build(bytes,true);
                    }
                }
            }
            // Scratch is host-private and the compiler's own closed-world
            // checker and post-lowering classfile audit remain authoritative.
            Path work=Files.createTempDirectory(root,"compiler-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try{
                var artifact=OresCompiler.compileRealtimePrimitive(source,entry,arity,budget,work);
                byte[] bytes;
                try(var input=Files.newInputStream(artifact.classFile(),LinkOption.NOFOLLOW_LINKS)){
                    bytes=input.readNBytes(CompilerArtifactReceipt.MAX_ARTIFACT+1);
                }
                if(bytes.length>CompilerArtifactReceipt.MAX_ARTIFACT)throw new IOException("E_CACHE_READ: classfile budget");
                RealtimeClassfileAudit.audit(bytes);
                dir.publish(name,CompilerArtifactReceipt.seal(key,bytes,secret));return new Build(bytes,false);
            }finally{try(var paths=Files.walk(work)){for(var file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        }finally{Arrays.fill(secret,(byte)0);}
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

}
