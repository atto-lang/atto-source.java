package dev.oreslang;
import dev.oreslang.parser.FlatMachineTable;
import dev.oreslang.parser.FlatMachineTable.*;
import java.util.*;

/** Standalone host smoke: no parser, runtime or guest execution. */
public class FlatMachineTableSmoke {
 static int pass;
 static void check(boolean ok) { if(!ok) throw new AssertionError(); pass++; }
 static void rejects(Runnable r) { try { r.run(); } catch(IllegalArgumentException e) { check(e.getMessage().startsWith("E_MACHINE_TABLE:")); return; } throw new AssertionError("accepted invalid graph"); }
 static Node n(String name, boolean end, Edge... edges){return new Node(name,end,List.of(edges));}
 static Edge e(String event,int target){return new Edge(event,target);}
 public static void main(String[] args) {
  var graph=List.of(n("Alpha",true),n("Zed",false,e("AlphaEvent",0),e("Zulu",1)));
  var t=FlatMachineTable.verify(1,graph);
  check(t.initial()==1);check(t.names().equals(List.of("Alpha","Zed")));check(t.events().equals(List.of("AlphaEvent","Zulu")));
  check(t.successor(1,0).orElseThrow()==0);check(t.successor(1,1).orElseThrow()==1);check(t.successor(0,0).isEmpty());
  rejects(()->t.successor(-1,0));rejects(()->t.successor(2,0));rejects(()->t.successor(1,-1));rejects(()->t.successor(1,2));
  rejects(()->FlatMachineTable.verify(2,graph));rejects(()->FlatMachineTable.verify(0,List.of()));
  rejects(()->FlatMachineTable.verify(1,List.of(graph.get(1),graph.get(0))));
  rejects(()->FlatMachineTable.verify(1,List.of(n("Alpha",true,e("X",1)),graph.get(1))));
  rejects(()->FlatMachineTable.verify(1,List.of(graph.get(0),n("Zed",false,e("Zulu",1),e("AlphaEvent",0)))));
  rejects(()->FlatMachineTable.verify(1,List.of(graph.get(0),n("Zed",false,e("AlphaEvent",9)))));
  rejects(()->FlatMachineTable.verify(1,List.of(graph.get(0),n("Zed",false,e("Zulu",1)))));
  rejects(()->FlatMachineTable.verify(0,List.of(n("A",false,e("E",0),e("E",0)))));
  rejects(()->FlatMachineTable.verify(0,List.of(n("",true))));
  rejects(()->FlatMachineTable.verify(0,List.of(n("\ud800",true))));
  var edges=new ArrayList<Edge>();for(int i=0;i<256;i++)edges.add(e(String.format("E%03d",i),0));
  check(FlatMachineTable.verify(0,List.of(new Node("N",false,edges))).events().size()==256);
  edges.add(e("E256",0));rejects(()->FlatMachineTable.verify(0,List.of(new Node("N",false,edges))));
  var nodes=new ArrayList<Node>();for(int i=0;i<256;i++)nodes.add(n(String.format("N%03d",i),i==255,i==255?new Edge[]{}:new Edge[]{e("Next",i+1)}));
  check(FlatMachineTable.verify(0,nodes).names().size()==256);nodes.add(n("N256",true));rejects(()->FlatMachineTable.verify(0,nodes));
  System.out.println(pass+" standalone exact-blob table checks passed");
 }
}
