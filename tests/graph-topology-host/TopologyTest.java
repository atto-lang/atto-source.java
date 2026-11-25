import dev.oreslang.types.GraphTopology;
import java.util.*;
public class TopologyTest {
 static int passed;
 static GraphTopology.Node n(long v, Integer... e){return new GraphTopology.Node(1,v,List.of(e));}
 static GraphTopology.Limits lim=new GraphTopology.Limits(65536,262144,1048576);
 static List<GraphTopology.Node> c(List<GraphTopology.Node> v,int r){return GraphTopology.canonicalize(v,r,lim);}
 static void check(boolean b){if(!b)throw new AssertionError("case "+(passed+1));passed++;}
 static void deny(Runnable r){try{r.run();throw new AssertionError("expected refusal");}catch(IllegalArgumentException e){passed++;}}
 public static void main(String[] args){
 var a=List.of(n(10,1,2),n(20,3),n(30,3),n(40,0,3));
 var b=List.of(n(40,2,0),n(30,0),n(10,3,1),n(20,0));
 check(c(a,0).equals(c(b,2)));check(c(a,0).hashCode()==c(b,2).hashCode());
 check(!c(List.of(n(1,0)),0).equals(c(List.of(n(1,1),n(1,0)),0)));
 check(!c(List.of(n(1,1,1),n(2)),0).equals(c(List.of(n(1,1,2),n(2),n(2)),0)));
 check(!c(List.of(n(1,1,-1),n(2)),0).equals(c(List.of(n(1,-1,1),n(2)),0)));
 check(!c(List.of(n(1,0)),0).equals(c(List.of(new GraphTopology.Node(2,1,List.of(0))),0)));
 deny(()->c(List.of(n(1,1)),0));deny(()->c(List.of(n(1,-2)),0));deny(()->c(List.of(n(1),n(2)),0));deny(()->c(List.of(n(1)),1));
 deny(()->c(List.of(new GraphTopology.Node(0,1,List.of())),0));
 var self=List.of(n(1,0));check(GraphTopology.canonicalize(self,0,new GraphTopology.Limits(1,1,6)).equals(self));
 deny(()->GraphTopology.canonicalize(self,0,new GraphTopology.Limits(1,1,5)));
 deny(()->GraphTopology.canonicalize(self,0,new GraphTopology.Limits(1,0,6)));
 var large=new ArrayList<GraphTopology.Node>();for(int i=0;i<10000;i++)large.add(n(i,(i+1)%10000));
 check(c(large,0).equals(large));check(c(large,0).hashCode()==large.hashCode());
 // Random permutations of the same reachable cycle plus repeated edges.
 Random random=new Random(731);for(int t=0;t<100;t++){int count=2+random.nextInt(100);var x=new ArrayList<GraphTopology.Node>();var perm=new ArrayList<Integer>();for(int i=0;i<count;i++){x.add(n(i,(i+1)%count,random.nextInt(count)));perm.add(i);}Collections.shuffle(perm,random);var y=new ArrayList<GraphTopology.Node>(Collections.nCopies(count,null));for(int i=0;i<count;i++){var q=x.get(i);y.set(perm.get(i),n(q.value(),perm.get(q.edges().get(0)),perm.get(q.edges().get(1))));}check(c(x,0).equals(c(y,perm.get(0))));}
 System.out.println("PASS "+passed+" assertions (including 100 seeded permutation cases)");
 }
}
