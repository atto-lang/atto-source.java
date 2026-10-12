package dev.oreslang.runtime;
import java.util.*;
/** Bounded protocol explorer, not a proof of implementation refinement. */
public final class ActorCleanupPublicationModel {
  record S(int p, int epoch, boolean canceled, boolean revoked,
           int left, int done, boolean failed, boolean published, boolean next) {}
  record Step(S state, String action) {}
  record Node(S state, String trace) {}
  static List<Step> next(S s, boolean unsafe) {
    List<Step> out = new ArrayList<>();
    if (s.p == 0) {
      out.add(new Step(new S(1,s.epoch,s.canceled,s.revoked,s.left,s.done,s.failed,s.published,s.next),"AWAIT"));
      out.add(new Step(new S(2,s.epoch,s.canceled,s.revoked,s.left,s.done,s.failed,s.published,s.next),"BEGIN_UNWIND"));
    }
    if (s.p == 1 && !s.canceled && !s.revoked)
      out.add(new Step(new S(0,s.epoch,false,false,s.left,s.done,s.failed,s.published,s.next),"RESUME"));
    if (s.p != 3 && !s.canceled)
      out.add(new Step(new S(2,s.epoch,true,true,s.left,s.done,s.failed,s.published,s.next),"CANCEL"));
    if (s.p == 2 && s.left > 0) {
      out.add(new Step(new S(2,s.epoch,s.canceled,s.revoked,s.left-1,s.done+1,s.failed,s.published,s.next),"DEFER"));
      out.add(new Step(new S(2,s.epoch,s.canceled,s.revoked,s.left-1,s.done+1,true,s.published,s.next),"DEFER_FAIL"));
    }
    if (s.p == 2 && (s.left == 0 || unsafe))
      out.add(new Step(new S(3,s.epoch,s.canceled,true,s.left,s.done,s.failed,
        unsafe || (!s.canceled && !s.revoked && !s.failed),s.next),"PUBLISH_OR_DISCARD"));
    if (s.p == 3 && s.left == 0 && !s.next)
      out.add(new Step(new S(3,s.epoch,s.canceled,true,s.left,s.done,s.failed,s.published,true),"NEXT_RECEIVE"));
    if (s.p == 3 && s.epoch == 1)
      out.add(new Step(new S(3,2,s.canceled,true,s.left,s.done,s.failed,s.published,s.next),"RESTART"));
    return out;
  }
  record Result(int states, int transitions, String witness) {}
  static Result explore(boolean unsafe) {
    S first = new S(0,1,false,false,2,0,false,false,false);
    Set<S> visited = new HashSet<>(); visited.add(first);
    ArrayDeque<Node> queue = new ArrayDeque<>(); queue.add(new Node(first,"INIT"));
    int transitions = 0;
    while (!queue.isEmpty()) {
      Node node = queue.removeFirst(); S s = node.state;
      if (s.done + s.left != 2 || s.published && (s.left > 0 || s.canceled || s.failed)
          || s.next && s.left > 0 || s.epoch == 2 && !s.revoked)
        return new Result(visited.size(),transitions,node.trace);
      for (Step step : next(s,unsafe)) {
        transitions++;
        if (visited.add(step.state)) queue.addLast(new Node(step.state,node.trace+" -> "+step.action));
      }
    }
    return new Result(visited.size(),transitions,null);
  }
  public static void main(String[] args) {
    Result safe = explore(false), mutant = explore(true);
    if (safe.witness != null || safe.states < 15 || mutant.witness == null)
      throw new AssertionError("Model validation failed: safe="+safe+" mutant="+mutant);
    System.out.println("PASS safe="+safe.states+" states / "+safe.transitions+" transitions; unsafe witness="+mutant.witness);
  }
}
