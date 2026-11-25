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

/** Bounded registered Protobuf wire-kind host decoder. No nominal guest ADT conversion. */
final class IpcProtobufDataPlanV1 implements IpcPinnedActorIngressV1.Decoder<Object> {
    enum Kind { VARINT,FIXED32,FIXED64,BYTES,MESSAGE,PACKED }
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
                throw new IllegalArgumentException("Protobuf limits");
        }
    }
    private final Map<String,Node> nodes;
    private final String root;
    private final Limits limits;
    private final IpcSchemaBoundDataV1.Binding binding;

    IpcProtobufDataPlanV1(String schema,long version,String root,List<Node> declarations,Limits limits) {
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
            int last=-1;
            for(Field f:fields){
                if(f.slot()==last||!names.add(f.name()))throw new IllegalArgumentException("descriptor slot/name");
                last=f.slot();
                if(n.kind()==Kind.MESSAGE&&!legalTag(f.slot()))throw new IllegalArgumentException("descriptor tag");
            }
            if(n.kind()==Kind.PACKED){
                if(fields.size()!=1||fields.getFirst().slot()!=0||!fields.getFirst().name().equals("element"))
                    throw new IllegalArgumentException("packed descriptor");
            }else if(n.kind()!=Kind.MESSAGE&&!fields.isEmpty())throw new IllegalArgumentException("scalar fields");
            table.put(n.id(),new Node(n.id(),n.kind(),fields));
        }
        if(!table.containsKey(root)||table.get(root).kind()!=Kind.MESSAGE)throw new IllegalArgumentException("message root required");
        for(Node n:table.values())for(Field f:n.fields()){
            Node target=table.get(f.target());
            if(target==null)throw new IllegalArgumentException("dangling descriptor");
            if(n.kind()==Kind.PACKED&&!scalar(target.kind()))throw new IllegalArgumentException("packed scalar required");
        }
        var seen=new HashSet<String>();var work=new ArrayDeque<String>();work.add(root);
        while(!work.isEmpty()){
            String id=work.removeLast();if(!seen.add(id))continue;
            for(Field f:table.get(id).fields())work.add(f.target());
        }
        if(seen.size()!=table.size())throw new IllegalArgumentException("unreachable descriptor");
        var canonical=new StringBuilder("ATTO-SHAPE-1\nprotobuf\n");
        canonical.append(version).append('\n').append(root).append('\n').append(table.size()).append('\n');
        for(Node n:table.values()){
            canonical.append(n.id()).append('\n').append(n.kind().name().toLowerCase(Locale.ROOT))
                    .append('\n').append(n.fields().size()).append('\n');
            for(Field f:n.fields())canonical.append(f.slot()).append('\n').append(f.name()).append('\n').append(f.target()).append('\n');
        }
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.US_ASCII));
            binding=new IpcSchemaBoundDataV1.Binding(IpcSchemaBoundDataV1.Codec.PROTOBUF,version,schema,digest);
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        this.nodes=Map.copyOf(table);this.root=root;this.limits=java.util.Objects.requireNonNull(limits);
    }
    private static boolean token(String s){
        return s!=null&&!s.isEmpty()&&s.length()<=128&&s.chars().allMatch(c->
                c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'||c=='.'||c==':'||c=='-');
    }
    IpcSchemaBoundDataV1.Binding binding(){return binding;}
    @Override public void requireBinding(IpcSchemaBoundDataV1.Binding actual){
        if(!binding.sameBinding(actual))throw new IllegalArgumentException("Protobuf plan binding mismatch");
    }
    @Override public long preflight(ByteBuffer body)throws IOException{return parse(body,false,limits.logical()).logical;}
    @Override public Object decode(ByteBuffer body,long admittedBytes)throws IOException{
        if(admittedBytes<=0||admittedBytes>limits.logical())throw new IOException("invalid admitted Protobuf bytes");
        Reader r=parse(body,true,admittedBytes);
        if(r.logical!=admittedBytes)throw new IOException("Protobuf preflight/materialization disagreement");
        return r.value;
    }
    private Reader parse(ByteBuffer body,boolean materialize,long cap)throws IOException{
        if(body==null||!body.isReadOnly()||body.remaining()>limits.wire())throw new IOException("Protobuf wire cap/ownership");
        Reader r=new Reader(body.slice().asReadOnlyBuffer(),materialize,cap);
        r.value=r.message(nodes.get(root),body.remaining(),0);
        if(r.in.hasRemaining())throw new IOException("trailing Protobuf");
        return r;
    }
    private static boolean legalTag(long n){return n>0&&n<=536870911L&&(n<19000||n>19999);}
    private static boolean scalar(Kind k){return k==Kind.VARINT||k==Kind.FIXED32||k==Kind.FIXED64;}
    private static int wire(Kind k){return switch(k){case VARINT->0;case FIXED64->1;case FIXED32->5;default->2;};}
    private final class Reader {
        final ByteBuffer in;final boolean materialize;final long cap;
        long logical,work;int nodeCount,elementCount;Object value;
        Reader(ByteBuffer in,boolean materialize,long cap){this.in=in;this.materialize=materialize;this.cap=cap;}
        void logical(long n)throws IOException{if(n>cap-logical)throw new IOException("Protobuf logical budget");logical+=n;}
        void work(long n)throws IOException{if(n>limits.work()-work)throw new IOException("Protobuf work budget");work+=n;}
        void field()throws IOException{
            if(elementCount>=limits.elements())throw new IOException("Protobuf field budget");
            elementCount++;logical(128);work(1);
        }
        int octet(int end)throws IOException{
            if(in.position()>=end)throw new IOException("truncated Protobuf");
            work(1);return in.get()&255;
        }
        long varint(int end)throws IOException{
            long v=0;
            for(int i=0;i<10;i++){
                int b=octet(end);
                if(i==9&&b>1)throw new IOException("Protobuf varint overflow");
                v|=(long)(b&127)<<(7*i);
                if(b<128)return v;
            }
            throw new IOException("Protobuf varint overflow");
        }
        int length(int end)throws IOException{
            long n=varint(end);
            if(n<0||n>end-in.position())throw new IOException("Protobuf length");
            return (int)n;
        }
        Field lookup(Node shape,long tag)throws IOException{
            int lo=0,hi=shape.fields().size();
            while(lo<hi){
                work(1);int mid=(lo+hi)>>>1;
                Field f=shape.fields().get(mid);
                if(f.slot()==tag)return f;
                if(f.slot()<tag)lo=mid+1;else hi=mid;
            }
            throw new IOException("unknown Protobuf field");
        }
        Object scalarValue(Kind kind,int end)throws IOException{
            if(kind==Kind.VARINT){
                long v=varint(end);
                return materialize?Long.valueOf(v):null; // raw u64 bits
            }
            int width=kind==Kind.FIXED32?4:8;
            long bits=0;
            for(int i=0;i<width;i++)bits|=(long)octet(end)<<(8*i);
            return materialize?Long.valueOf(bits):null;
        }
        Object message(Node shape,int end,int depth)throws IOException{
            if(depth>limits.depth()||nodeCount>=limits.nodes())throw new IOException("Protobuf message/depth budget");
            nodeCount++;logical(128);work(1);
            List<Object> values=materialize?new ArrayList<>():null;
            while(in.position()<end){
                field();long key=varint(end),tag=key>>>3;
                if(!legalTag(tag))throw new IOException("invalid Protobuf tag");
                Field f=lookup(shape,tag);Node target=nodes.get(f.target());
                if((key&7)!=wire(target.kind()))throw new IOException("Protobuf wire mismatch");
                Object decoded;
                if(scalar(target.kind()))decoded=scalarValue(target.kind(),end);
                else{
                    int n=length(end),stop=in.position()+n;
                    if(target.kind()==Kind.MESSAGE)decoded=message(target,stop,depth+1);
                    else if(target.kind()==Kind.PACKED){
                        Kind k=nodes.get(target.fields().getFirst().target()).kind();
                        int width=k==Kind.FIXED32?4:k==Kind.FIXED64?8:1;
                        if(n%width!=0)throw new IOException("misaligned packed Protobuf");
                        if(width>1&&(n/width>limits.elements()-elementCount||128L*(n/width)>cap-logical))
                            throw new IOException("packed minimum budget");
                        List<Object> packed=materialize?new ArrayList<>():null;
                        while(in.position()<stop){
                            field();Object v=scalarValue(k,stop);
                            if(materialize)packed.add(v);
                        }
                        decoded=materialize?List.copyOf(packed):null;
                    }else{
                        logical(16L*n);work(2L*n);
                        if(materialize){byte[] bytes=new byte[n];in.get(bytes);decoded=bytes;}
                        else {in.position(stop);decoded=null;}
                    }
                    if(in.position()!=stop)throw new IOException("Protobuf segment boundary");
                }
                if(materialize)values.add(List.of(Long.valueOf(tag),decoded));
            }
            if(in.position()!=end)throw new IOException("Protobuf message boundary");
            return materialize?List.copyOf(values):null;
        }
    }
}
