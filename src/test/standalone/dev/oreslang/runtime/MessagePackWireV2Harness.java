package dev.oreslang.runtime;

import dev.oreslang.runtime.MessagePackWireV2;
import dev.oreslang.runtime.MessagePackWireV2.Shape;
import java.util.*;
import java.util.HexFormat;
public final class MessagePackWireV2Harness {
  static int cases;
  static void eq(String id, Shape s, Object value, String hex) {
    byte[] b=MessagePackWireV2.encode(id,s,value);
    if (!HexFormat.of().formatHex(b).equals(hex)) throw new AssertionError(id + " encoded wrong: " + HexFormat.of().formatHex(b));
    Object decoded=MessagePackWireV2.decode(id,s,b);
    if (!decoded.equals(value)) throw new AssertionError(id + " roundtrip mismatch " + decoded);
    for(int i=0;i<b.length;i++){
      byte[] truncated=Arrays.copyOf(b,i);
      reject(()->MessagePackWireV2.decode(id,s,truncated));
    }
    byte[] trailing=Arrays.copyOf(b,b.length+1); reject(()->MessagePackWireV2.decode(id,s,trailing));
    cases++;
  }
  static void reject(Runnable r) {try{r.run();throw new AssertionError("invalid input accepted");}catch(IllegalArgumentException expected){cases++;}}
  public static void main(String[] a) {
    eq("demo.TupleBoolString.v1",Shape.tuple(Shape.bool(),Shape.string()),List.of(true,"foo"),"9402b764656d6f2e5475706c65426f6f6c537472696e672e7631c420d254b4ecd3d7964454dcdf86fd10ede585a17b3af27982a289819afdc2379ab292c3a3666f6f");
    eq("demo.FixedArrayInts.v1",Shape.fixedArray(Shape.i64(),Shape.i64(),Shape.i64()),List.of(1L,2L,3L),"9402b664656d6f2e46697865644172726179496e74732e7631c4206a46f6c65c619cc1c5354f7bd3b35ec0fcfbc9866c0760a15b860cfef79773b993010203");
    eq("demo.FixedListNested.v1",Shape.fixedList(Shape.list(Shape.string()),Shape.bool()),List.of(List.of("a","b"),false),"9402b764656d6f2e46697865644c6973744e65737465642e7631c4202007a0279d88d3eca7d21cf32b37327b996593c1353963dd78555f56f1ba6ddc9292a161a162c2");
    eq("demo.ArrayStrings.v1",Shape.array(Shape.string()),List.of("a","b"),"9402b464656d6f2e4172726179537472696e67732e7631c420139722a10b9ef4d4af0cea1bfbc50ff92a0459aabf837f63a6fec052fe5dc5c992a161a162");
    eq("demo.ListStrings.v1",Shape.list(Shape.string()),List.of("a","b"),"9402b364656d6f2e4c697374537472696e67732e7631c4204efbea59591d5c31d9f13ee93ef5c1d954e653144d380bb2d064ea4fa6d56b8c92a161a162");
    eq("demo.String.v1",Shape.string(),"foo","9402ae64656d6f2e537472696e672e7631c42021c2efdbff08dd4e1320d8abe1ba0cc6aea8e4cf104b984be7b7079968fad3cda3666f6f");
    byte[] b=MessagePackWireV2.encode("demo.String.v1",Shape.string(),"foo");
    byte[] corrupted=b.clone();corrupted[0]=0;byte[] final0=corrupted;reject(()->MessagePackWireV2.decode("demo.String.v1",Shape.string(),final0));
    corrupted=b.clone();corrupted[1]=1;byte[] final1=corrupted;reject(()->MessagePackWireV2.decode("demo.String.v1",Shape.string(),final1));
    corrupted=b.clone();corrupted[3]^=1;byte[] final2=corrupted;reject(()->MessagePackWireV2.decode("demo.String.v1",Shape.string(),final2));
    corrupted=b.clone();corrupted[25]^=1;byte[] final3=corrupted;reject(()->MessagePackWireV2.decode("demo.String.v1",Shape.string(),final3));
    reject(()->MessagePackWireV2.encode("demo.X",Shape.string(),"\ud800"));
    reject(()->MessagePackWireV2.encode("demo.X",Shape.f64(),Double.NaN));
    reject(()->MessagePackWireV2.encode("demo.X",Shape.i64(),"not a long"));
    reject(()->MessagePackWireV2.decode("demo.String.v1", Shape.string(),new byte[1048577]));
    ArrayList<Object> sender=new ArrayList<>(List.of("before","stable"));
    byte[] owned=MessagePackWireV2.encode("demo.Mutation.v1",Shape.list(Shape.string()),sender);sender.set(0,"after");
    @SuppressWarnings("unchecked") List<Object> receiver=(List<Object>)MessagePackWireV2.decode("demo.Mutation.v1",Shape.list(Shape.string()),owned);
    Arrays.fill(owned,(byte)0);receiver.set(1,"receiver");
    if(!receiver.equals(List.of("before","receiver"))||!sender.equals(List.of("after","stable")))throw new AssertionError("ownership isolation broken");
    System.out.println("PASS MessagePack v2 Java portable golden/negative assertions="+cases);
  }
}
