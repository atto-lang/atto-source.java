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
    public record Build(byte[] bytes,boolean reused){}
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
        return compileKey(key(source,entry,arity,budget),source,null,null,entry,arity,budget,ledger,pin,master,cold);
    }

    /** Trusted host approved closed i32 provider inventory. No implicit
     * narrowing of Atto int, general namespace linking or typed AST storage.
     * Host must serialize authority epoch rotation and protect filesystem.
     */
    public record ProviderInput(String path,String namespace,String symbol,int arity,String source){}
    private static boolean identifier(String s){return s!=null&&s.matches("[A-Za-z_][A-Za-z0-9_]{0,127}");}
    private static IllegalArgumentException denied(String detail){return new IllegalArgumentException("E_NAMESPACE_INVENTORY: "+detail);}
    public Build compileBoundProvider(List<ProviderInput> inventory,String expectedManifest,String expectedSources,
            String namespace,String symbol,int arity,RealtimePrimitiveCompiler.Budget budget,
            byte[] ledger,byte[] pin,byte[] master,boolean cold)throws IOException{
        return compileProvider(inventory,expectedManifest,expectedSources,namespace,symbol,arity,budget,ledger,pin,master,cold,false);
    }

    /** Signed-64 checked provider boundary, with explicit int declarations.
     * Overflow raises ArithmeticException. This is not the unrestricted
     * Truffle int runtime or a qualified realtime profile. */
    public Build compileCheckedI64Provider(List<ProviderInput> inventory,String expectedManifest,String expectedSources,
            String namespace,String symbol,int arity,RealtimePrimitiveCompiler.Budget budget,
            byte[] ledger,byte[] pin,byte[] master,boolean cold)throws IOException{
        return compileProvider(inventory,expectedManifest,expectedSources,namespace,symbol,arity,budget,ledger,pin,master,cold,true);
    }
    private Build compileProvider(List<ProviderInput> inventory,String expectedManifest,String expectedSources,
            String namespace,String symbol,int arity,RealtimePrimitiveCompiler.Budget budget,
            byte[] ledger,byte[] pin,byte[] master,boolean cold,boolean wide)throws IOException{
        byte[] authority=CompilerCacheLedger.admit(scope,ledger,pin,master);
        Arrays.fill(authority,(byte)0);
        if(inventory==null||inventory.isEmpty()||inventory.size()>128||budget==null)throw denied("member budget");
        var ordered=new TreeMap<String,ProviderInput>();var names=new HashSet<String>();
        for(var p:List.copyOf(inventory)){
            if(p.path()==null||p.path().length()>256||!p.path().matches("[A-Za-z_][A-Za-z0-9_]*(/[A-Za-z_][A-Za-z0-9_]*)*\\.atto")||
                !identifier(p.namespace())||!identifier(p.symbol())||p.arity()<0||p.arity()>4||
                p.source()==null||p.source().length()>4096||!StandardCharsets.UTF_8.newEncoder().canEncode(p.source())||
                p.source().getBytes(StandardCharsets.UTF_8).length>4096||p.source().indexOf(0)>=0||
                ordered.putIfAbsent(p.path(),p)!=null||!names.add(p.namespace()+"."+p.symbol()))
                throw denied("invalid or duplicate member");
        }
        String profile=wide?"checked-i64":"i32";
        var manifest=new StringBuilder("atto.java."+profile+"-provider-inventory.v1\n");
        var sources=new StringBuilder("atto.java."+profile+"-provider-sources.v1\n");
        for(var p:ordered.values()){
            manifest.append(p.path()).append('\n').append(p.namespace()).append('\n').append(p.symbol()).append('\n').append(p.arity()).append('\n');
            sources.append(p.path()).append('\n').append(digest(p.source())).append('\n');
        }
        String manifestHash=digest(manifest.toString());
        if(!manifestHash.equals(expectedManifest)||!digest(sources.toString()).equals(expectedSources))
            throw new IllegalArgumentException("E_NAMESPACE_PIN: inventory mismatch");
        ProviderInput selected=null;dev.oreslang.ast.Ast.Program checked=null;
        CheckedI64ProviderClassfile widePlan=null;String type=wide?"int":"i32";
        for(var p:ordered.values()){
            // Exact header and a single explicitly typed public provider.
            // No substitution of i32 for int; original body is type checked.
            String prefix="namespace "+p.namespace()+";\n";
            if(!p.source().startsWith(prefix))throw denied("explicit namespace required");
            String body=p.source().substring(prefix.length());
            var pattern=java.util.regex.Pattern.compile("pub fnc "+java.util.regex.Pattern.quote(p.symbol())+
                "\\(([^\\n()]*)\\): "+type+" \\{ return ([A-Za-z_0-9+*() -]{1,1024}); \\}\\n");
            var match=pattern.matcher(body);
            if(!match.matches())throw denied("closed "+type+" declaration required");
            var params=match.group(1).isEmpty()?new String[0]:match.group(1).split(", ",-1);
            if(params.length!=p.arity()||Arrays.stream(params).anyMatch(x->!x.matches(type+" [A-Za-z_][A-Za-z0-9_]{0,127}")))
                throw denied("exact "+type+" parameters");
            var program=OresCompiler.parseAndTypeCheck(body);
            var candidate=wide?CheckedI64ProviderClassfile.admit(program,p.symbol(),p.arity(),budget):null;
            if(!wide)RealtimePrimitiveCompiler.analyze(program,p.symbol(),p.arity(),budget);
            if(p.namespace().equals(namespace)&&p.symbol().equals(symbol)){
                if(p.arity()!=arity)throw new IllegalArgumentException("E_NAMESPACE_ARITY: incompatible selected call");
                selected=p;checked=program;widePlan=candidate;
            }
        }
        if(selected==null)throw new IllegalArgumentException("E_NAMESPACE_SYMBOL: selected provider absent");
        String key=CompilerCacheKey.encode(new CompilerCacheKey.Input("jvm-"+profile+"-provider-bound-input-v2",scope,"source-unit",selected.path(),
            compiler,toolchain,digest("jvm-classfile8"),digest("closed-"+profile+"-provider-v1:"+budget.maxSourceSteps()+":"+budget.maxCallDepth()),
            digest(selected.source()),digest(namespace+"\n"+symbol+"\n"+arity+"\n"+selected.path()),null,
            List.of(new CompilerCacheKey.Dependency("provider-inventory",manifestHash))));
        return compileKey(key,null,checked,widePlan,symbol,arity,budget,ledger,pin,master,cold);
    }
    private Build compileKey(String key,String source,dev.oreslang.ast.Ast.Program checked,CheckedI64ProviderClassfile widePlan,String entry,int arity,
            RealtimePrimitiveCompiler.Budget budget,byte[] ledger,byte[] pin,byte[] master,boolean cold)throws IOException{
        Path name=Path.of("slot-"+key.charAt(0));
        byte[] secret=CompilerCacheLedger.admit(scope,ledger,pin,master);
        try(var dir=Directory.open(root)){
            if(!identity.equals(dir.identity.fileKey()))throw new IOException("E_CACHE_PATH: root changed");
            if(!cold){
                byte[] envelope=dir.read(name);
                if(envelope!=null){
                    String actual=HexFormat.of().formatHex(envelope,8,40);
                    if(actual.charAt(0)!=key.charAt(0))throw new IllegalArgumentException("E_CACHE_READ: wrong slot");
                    byte[] bytes=CompilerArtifactReceipt.verify(actual,envelope,secret);
                    if(actual.equals(key))return new Build(bytes,true);
                }
            }
            if(widePlan!=null){
                byte[] bytes=widePlan.emitClassfile();
                dir.publish(name,CompilerArtifactReceipt.seal(key,bytes,secret));return new Build(bytes,false);
            }
            // Scratch is host-private and the compiler's own closed-world
            // checker and post-lowering classfile audit remain authoritative.
            Path work=Files.createTempDirectory(root,"compiler-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try{
                var artifact=checked==null?OresCompiler.compileRealtimePrimitive(source,entry,arity,budget,work):RealtimePrimitiveCompiler.compile(checked,entry,arity,budget,work);
                byte[] bytes;
                try(var input=Files.newInputStream(artifact.classFile(),LinkOption.NOFOLLOW_LINKS)){
                    bytes=input.readNBytes(CompilerArtifactReceipt.MAX_ARTIFACT+1);
                }
                if(bytes.length>CompilerArtifactReceipt.MAX_ARTIFACT)throw new IOException("E_CACHE_READ: classfile budget");
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
