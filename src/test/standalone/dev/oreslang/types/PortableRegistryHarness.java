package dev.oreslang.types;

import dev.oreslang.types.PortableCodecRegistry.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

public final class PortableRegistryHarness {
    static int count;
    static void check(boolean good, String test) {
        if (!good) throw new AssertionError(test);
        count++;
    }
    static void reject(Runnable f, String test) {
        try { f.run(); } catch (IllegalArgumentException expected) { count++; return; }
        throw new AssertionError(test + " did not reject");
    }
    static Shape scalar() {return new Scalar(ScalarKind.I64);}
    static Field f(String n, int tag, Shape value){return new Field(n, tag, value);}
    static Descriptor register(Registry r, Format fmt, Purpose purpose, String type, String schema, int v, Shape shape) {
        return r.registerCompilerDescriptor(fmt, purpose, type, schema, v, shape);
    }
    public static void main(String[] args) throws Exception {
        Registry r = new Registry();
        Descriptor a = register(r, Format.JSON, Purpose.NETWORK_DATA, "app.User", "app.UserWire", 1,
            new ObjectShape(List.of(f("name",0,new Scalar(ScalarKind.UTF8)), f("age",0,scalar()))));
        Descriptor b = register(r, Format.JSON, Purpose.NETWORK_DATA, "app.User", "app.UserWire", 1,
            new ObjectShape(List.of(f("age",0,scalar()), f("name",0,new Scalar(ScalarKind.UTF8)))));
        check(a == b, "same descriptor interned by type and schema");
        check(a.fingerprintHex().length() == 64, "SHA256 hex");
        check(r.requirePinned(Format.JSON, Purpose.NETWORK_DATA, "app.User",1) == a, "pinned lookup");
        check(r.size() == 1, "size one");
        reject(()->register(r,Format.JSON,Purpose.NETWORK_DATA,"app.User","app.Changed",1,scalar()),"type schema collision");
        reject(()->register(r,Format.JSON,Purpose.NETWORK_DATA,"app.Other","app.UserWire",1,scalar()),"schema type collision");
        reject(()->register(r,Format.JSON,Purpose.NETWORK_DATA,"app.User","app.UserWire",1,scalar()),"shape drift collision");
        check(r.size()==1, "collision has no partial publish");
        reject(()->r.requirePinned(Format.JSON,Purpose.NETWORK_DATA,"app.Other",1),"absent type pin");
        reject(()->register(new Registry(),Format.JSON,Purpose.NETWORK_DATA,"Unqualified","app.Schema",1,scalar()),"unqualified identity");
        reject(()->register(new Registry(),Format.JSON,Purpose.NETWORK_DATA,"app.Record","app.Schema",0,scalar()),"zero version");
        reject(()->register(new Registry(),Format.JSON,Purpose.NETWORK_DATA,"app.Record","app.Schema",1,
                new ObjectShape(List.of(f("a",0,scalar()),f("a",0,new Scalar(ScalarKind.UTF8))))),"duplicate key");
        reject(()->register(new Registry(),Format.JSON,Purpose.NETWORK_DATA,"app.Record","app.Schema",1,
                new Scalar(ScalarKind.BYTES)),"unimplemented JSON BYTES");
        reject(()->register(new Registry(),Format.MESSAGEPACK_V2,Purpose.ACTOR_DATA,"app.Record","app.Schema",1,
                new ObjectShape(List.of(f("field",0,scalar())))),"unimplemented actor record");
        reject(()->register(new Registry(),Format.JSON,Purpose.ACTOR_DATA,"app.Record","app.Schema",1,scalar()),"actor JSON unsupported");
        reject(()->register(new Registry(),Format.PROTOBUF,Purpose.ACTOR_DATA,"app.Record","app.Schema",1,scalar()),"actor protobuf unsupported");
        reject(()->register(new Registry(),Format.PROTOBUF,Purpose.NETWORK_DATA,"app.Record","app.Schema",1,scalar()),"protobuf top-level record");
        reject(()->register(new Registry(),Format.PROTOBUF,Purpose.NETWORK_DATA,"app.Record","app.Schema",1,
                new ObjectShape(List.of(f("field",19000,scalar())))),"reserved protobuf tag");
        reject(()->register(new Registry(),Format.PROTOBUF,Purpose.NETWORK_DATA,"app.Record","app.Schema",1,
                new ObjectShape(List.of(f("a",1,scalar()),f("b",1,scalar())))),"duplicate protobuf tag");
        Shape lists = new Sequence(SequenceKind.LIST,new Sequence(SequenceKind.LIST,scalar(),-1),-1);
        reject(()->register(new Registry(),Format.PROTOBUF,Purpose.NETWORK_DATA,"app.Grid","app.GridSchema",1,
            new ObjectShape(List.of(f("rows",1,lists)))),"protobuf repeated of repeated");
        Shape row = new ObjectShape(List.of(f("value",1,scalar())));
        Descriptor rows=register(new Registry(),Format.PROTOBUF,Purpose.NETWORK_DATA,"app.Grid","app.GridSchema",1,
            new ObjectShape(List.of(f("rows",1,new Sequence(SequenceKind.LIST,row,-1)))));
        check(rows.canonicalShape().contains("R("),"nested protobuf record admitted");
        Descriptor actor = register(new Registry(),Format.MESSAGEPACK_V2,Purpose.ACTOR_DATA,
            "app.Number","app.NumberSchema",1,scalar());
        Descriptor net = register(new Registry(),Format.MESSAGEPACK_V2,Purpose.NETWORK_DATA,
            "app.Number","app.NumberSchema",1,scalar());
        check(!actor.fingerprintHex().equals(net.fingerprintHex()),"actor purpose separated from network");
        Shape tooDeep = scalar();
        for(int i=0;i<66;i++)tooDeep=new Sequence(SequenceKind.LIST,tooDeep,-1);
        Shape rejectDepth=tooDeep;
        reject(()->register(new Registry(),Format.JSON,Purpose.NETWORK_DATA,"app.Deep","app.DeepSchema",1,rejectDepth),"nesting depth budget");
        reject(()->new Field("a".repeat(129),0,scalar()),"field name overlong");
        Field stableField=f("safe",0,scalar());
        List<Field> hostileRecordFields=new java.util.AbstractList<>() {
          int sizes;
          @Override public int size(){return ++sizes==1?1:1_000_000;}
          @Override public Field get(int index) {
            if(index!=0)throw new AssertionError("record snapshot exceeded checked size");
            return stableField;
          }
          @Override public Object[] toArray(){
            throw new AssertionError("hostile record toArray must not run");
          }
          @Override public <E> E[] toArray(E[] target){
            throw new AssertionError("hostile record typed toArray must not run");
          }
        };
        ObjectShape safeSnapshot=new ObjectShape(hostileRecordFields);
        check(safeSnapshot.fields().equals(List.of(stableField)),
              "hostile list cannot escape bounded record snapshot");
        reject(()->new ObjectShape(new java.util.AbstractList<>() {
          @Override public int size(){return 2;}
          @Override public Field get(int index) {
            if(index==0)return stableField;
            throw new IndexOutOfBoundsException("record source changed");
          }
        }),"record shrink while copying denied");
        reject(()->new ObjectShape(new java.util.AbstractList<>() {
          @Override public int size(){return -1;}
          @Override public Field get(int index) {
            throw new AssertionError("negative-size list must never be traversed");
          }
        }),"negative reported record size denied");
        ExecutorService ex=Executors.newFixedThreadPool(8);
        try {
          Registry concurrent=new Registry();
          List<Future<Descriptor>> pending=new ArrayList<>();
          for(int i=0;i<64;i++){
            pending.add(ex.submit(()->register(concurrent,Format.JSON,Purpose.NETWORK_DATA,
                    "app.Concurrent","app.ConcurrentSchema",1,scalar())));
          }
          Descriptor first=pending.get(0).get();
          for(Future<Descriptor> future:pending)check(future.get()==first,"concurrent canonical descriptor");
          check(concurrent.size()==1,"concurrent table coherent");
        } finally {ex.shutdownNow();}
        System.out.println("PASS " + count + " registry executable assertions (standalone JDK " + Runtime.version().feature() + ")");
    }
}
