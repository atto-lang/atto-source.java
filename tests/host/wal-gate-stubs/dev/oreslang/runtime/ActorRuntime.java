package dev.oreslang.runtime;
import java.util.Optional;
final class ActorRuntime {
    enum ActorKind { PRIVATE, SHARED, UNTRUSTED }
    record ActorId(String value) { }
    static final class ActorRef<T> {
        private final ActorId id;
        private final long gen;
        private volatile boolean alive=true;
        ActorRef(ActorId id,long gen){this.id=id;this.gen=gen;}
        ActorId id(){return id;}
        long generation(){return gen;}
        boolean isAlive(){return alive;}
        void retire(){alive=false;}
    }
    private record Turn(ActorRuntime runtime, ActorRef<?> actor, Object domain, ActorKind kind) { }
    private static final ThreadLocal<Turn> turn = new ThreadLocal<>();
    static void enter(ActorRuntime runtime, ActorRef<?> actor, Object domain, ActorKind kind) {
        turn.set(new Turn(runtime,actor,domain,kind));
    }
    static void leave(){turn.remove();}
    static boolean inActorExecution(){return turn.get()!=null;}
    static boolean isActorCarrierThread(){return inActorExecution();}
    static ActorRuntime currentActorRuntime(){return turn.get()==null?null:turn.get().runtime();}
    static Optional<ActorId> currentActorId(){return turn.get()==null?Optional.empty():Optional.of(turn.get().actor().id());}
    static ActorKind currentActorKind(){return turn.get()==null?null:turn.get().kind();}
    static Object currentActorExecutionDomain(){return turn.get()==null?null:turn.get().domain();}
}
