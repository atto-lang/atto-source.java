package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Bounded registered CBOR positional host decoder. No nominal guest ADT conversion. */
final class IpcCborDataPlanV1 implements IpcPinnedActorIngressV1.Decoder<Object> {
    enum Kind { BOOL,I64,F64,TEXT,LIST,RECORD }
    record Field(int slot,String name,String target) { }
    record Node(String id,Kind kind,List<Field> fields) {
        Node {
            if(fields==null)throw new IllegalArgumentException("descriptor fields");
            int count=fields.size();
            if(count<0||count>1024)throw new IllegalArgumentException("descriptor field count");
            var snapshot=new ArrayList<Field>(count);
            for(int i=0;i<count;i++)snapshot.add(java.util.Objects.requireNonNull(fields.get(i)));
            fields=List.copyOf(snapshot);
        }
    }
    record Limits(int wire,long logical,long work,int nodes,int elements,int depth) {
        Limits {
            if(wire<1||wire>1024*1024||logical<1||logical>8L*1024*1024
                    ||work<1||work>4L*1024*1024||nodes<1||nodes>100000
                    ||elements<0||elements>100000||depth<0||depth>64)
                throw new IllegalArgumentException("CBOR limits");
        }
    }
    private final Map<String,Node> nodes;
    private final String root;
    private final Limits limits;
    private final IpcSchemaBoundDataV1.Binding binding;

    IpcCborDataPlanV1(String schema,long version,String root,List<Node> declarations,Limits limits) {
        if(!token(root)||declarations==null||version<=0||version>0xffffffffL)
            throw new IllegalArgumentException("descriptor header");
        int count=declarations.size();
        if(count<1||count>256)throw new IllegalArgumentException("descriptor node count");
        var table=new TreeMap<String,Node>();
        int total=0;
        for(int index=0;index<count;index++){
            Node n=declarations.get(index);
            if(n==null||!token(n.id())||n.kind()==null||table.containsKey(n.id()))
                throw new IllegalArgumentException("descriptor node");
            total+=n.fields().size();if(total>4096)throw new IllegalArgumentException("descriptor field total");
            var fields=new ArrayList<>(n.fields());
            for(Field f:fields)if(f==null||!token(f.name())||!token(f.target())||f.slot()<0)
                throw new IllegalArgumentException("descriptor field");
            fields.sort(Comparator.comparingInt(Field::slot));
            var names=new HashSet<String>();
            for(int i=0;i<fields.size();i++)if(fields.get(i).slot()!=i||!names.add(fields.get(i).name()))
                throw new IllegalArgumentException("descriptor slot/name");
            if(n.kind()==Kind.LIST){
                if(fields.size()!=1||!fields.getFirst().name().equals("element"))
                    throw new IllegalArgumentException("list descriptor");
            }else if(n.kind()!=Kind.RECORD&&!fields.isEmpty())throw new IllegalArgumentException("scalar fields");
            table.put(n.id(),new Node(n.id(),n.kind(),fields));
        }
        if(!table.containsKey(root))throw new IllegalArgumentException("descriptor root");
        for(Node n:table.values())for(Field f:n.fields())
            if(!table.containsKey(f.target()))throw new IllegalArgumentException("dangling descriptor");
        var seen=new HashSet<String>();var work=new ArrayDeque<String>();work.add(root);
        while(!work.isEmpty()){
            String id=work.removeLast();if(!seen.add(id))continue;
            for(Field f:table.get(id).fields())work.add(f.target());
        }
        if(seen.size()!=table.size())throw new IllegalArgumentException("unreachable descriptor");
        var canonical=new StringBuilder("ATTO-SHAPE-1\ncbor\n");
        canonical.append(version).append('\n').append(root).append('\n').append(table.size()).append('\n');
        for(Node n:table.values()){
            canonical.append(n.id()).append('\n').append(n.kind().name().toLowerCase(Locale.ROOT))
                    .append('\n').append(n.fields().size()).append('\n');
            for(Field f:n.fields())canonical.append(f.slot()).append('\n').append(f.name()).append('\n').append(f.target()).append('\n');
        }
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.US_ASCII));
            binding=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.CBOR,version,schema,digest);
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        this.nodes=Map.copyOf(table);this.root=root;this.limits=java.util.Objects.requireNonNull(limits);
    }
    private static boolean token(String s){
        return s!=null&&!s.isEmpty()&&s.length()<=128&&s.chars().allMatch(c->
                c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'||c=='.'||c==':'||c=='-');
    }
    IpcSchemaBoundDataV1.Binding binding(){return binding;}
    @Override public void requireBinding(IpcSchemaBoundDataV1.Binding actual){
        if(!binding.sameBinding(actual))throw new IllegalArgumentException("CBOR plan binding mismatch");
    }
    @Override public long preflight(ByteBuffer body)throws IOException{return parse(body,false,limits.logical()).logical;}
    @Override public Object decode(ByteBuffer body,long admittedBytes)throws IOException{
        if(admittedBytes<=0||admittedBytes>limits.logical())throw new IOException("invalid admitted CBOR bytes");
        Reader r=parse(body,true,admittedBytes);
        if(r.logical!=admittedBytes)throw new IOException("CBOR preflight/materialization disagreement");
        return r.value;
    }
    private Reader parse(ByteBuffer body,boolean materialize,long cap)throws IOException{
        if(body==null||!body.isReadOnly()||body.remaining()>limits.wire())throw new IOException("CBOR wire cap/ownership");
        Reader r=new Reader(body.slice().asReadOnlyBuffer(),materialize,cap);
        r.value=r.item(nodes.get(root),0);
        if(r.in.hasRemaining())throw new IOException("trailing CBOR");
        return r;
    }
    private final class Reader {
        final ByteBuffer in;final boolean materialize;final long cap;
        long logical,work;int nodeCount,elementCount;Object value;
        Reader(ByteBuffer in,boolean materialize,long cap){this.in=in;this.materialize=materialize;this.cap=cap;}
        void logical(long n)throws IOException{if(n>cap-logical)throw new IOException("CBOR logical budget");logical+=n;}
        void work(long n)throws IOException{if(n>limits.work()-work)throw new IOException("CBOR work budget");work+=n;}
        int octet()throws IOException{
            if(!in.hasRemaining())throw new IOException("truncated CBOR");
            work(1);return in.get()&255;
        }
        long bits(int n)throws IOException{long v=0;for(int i=0;i<n;i++)v=(v<<8)|octet();return v;}
        long arg(int ai)throws IOException{
            if(ai<=23)return ai;
            int n=switch(ai){case 24->1;case 25->2;case 26->4;case 27->8;default->throw new IOException("indefinite CBOR");};
            long v=bits(n),min=switch(n){case 1->24;case 2->256;case 4->65536;default->4294967296L;};
            if(v<0||v<min)throw new IOException("noncanonical or out-of-range CBOR");
            return v;
        }
        int length(int ai)throws IOException{
            long n=arg(ai);if(n>in.remaining()||n>Integer.MAX_VALUE)throw new IOException("CBOR length");
            return (int)n;
        }
        Object item(Node shape,int depth)throws IOException{
            if(depth>limits.depth()||nodeCount>=limits.nodes())throw new IOException("CBOR node/depth budget");
            nodeCount++;logical(128);work(1);
            int head=octet(),major=head>>>5,ai=head&31;
            return switch(shape.kind()){
                case BOOL->{
                    if(head!=0xf4&&head!=0xf5)throw new IOException("CBOR bool shape");
                    yield materialize?Boolean.valueOf(head==0xf5):null;
                }
                case I64->{
                    if(major!=0&&major!=1)throw new IOException("CBOR int shape");
                    long n=arg(ai);yield materialize?Long.valueOf(major==0?n:-1-n):null;
                }
                case F64->{
                    if(head!=0xfb)throw new IOException("CBOR binary64 shape");
                    double d=Double.longBitsToDouble(bits(8));
                    if(!Double.isFinite(d))throw new IOException("CBOR nonfinite");
                    yield materialize?Double.valueOf(d):null;
                }
                case TEXT->{
                    if(major!=3)throw new IOException("CBOR text shape");
                    int n=length(ai);logical(16L*n);work(2L*n);int start=in.position();utf8(n);
                    if(!materialize)yield null;
                    byte[] bytes=new byte[n];
                    ByteBuffer copy=in.duplicate();copy.position(start);copy.get(bytes);
                    yield new String(bytes,StandardCharsets.UTF_8);
                }
                case LIST,RECORD->{
                    if(major!=4)throw new IOException("CBOR array shape");
                    int n=length(ai);
                    if(shape.kind()==Kind.RECORD&&n!=shape.fields().size())throw new IOException("CBOR record arity");
                    if(n>limits.elements()-elementCount||n>limits.nodes()-nodeCount)
                        throw new IOException("CBOR child budget");
                    elementCount+=n;logical(32L*n);
                    // Reject impossible minimum before creating backing storage.
                    if(128L*n>cap-logical||2L*n>limits.work()-work)throw new IOException("CBOR minimum child budget");
                    List<Object> values=materialize?new ArrayList<>(n):null;
                    for(int i=0;i<n;i++){
                        Field f=shape.fields().get(shape.kind()==Kind.LIST?0:i);
                        Object child=item(nodes.get(f.target()),depth+1);
                        if(materialize)values.add(child);
                    }
                    yield materialize?List.copyOf(values):null;
                }
            };
        }
        void utf8(int n)throws IOException{
            int end=in.position()+n;
            while(in.position()<end){
                int b=octet();if(b<=127)continue;
                int more,lo=0x80,hi=0xbf;
                if(b>=0xc2&&b<=0xdf)more=1;
                else if(b>=0xe0&&b<=0xef){more=2;if(b==0xe0)lo=0xa0;if(b==0xed)hi=0x9f;}
                else if(b>=0xf0&&b<=0xf4){more=3;if(b==0xf0)lo=0x90;if(b==0xf4)hi=0x8f;}
                else throw new IOException("invalid CBOR UTF-8");
                if(more>end-in.position())throw new IOException("truncated CBOR UTF-8");
                int next=octet();if(next<lo||next>hi)throw new IOException("invalid CBOR UTF-8");
                for(int i=1;i<more;i++){next=octet();if(next<0x80||next>0xbf)throw new IOException("invalid CBOR UTF-8");}
            }
        }
    }
}
