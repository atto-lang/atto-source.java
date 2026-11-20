package atto.compiler.pipe;

import java.util.ArrayList;
import java.util.List;
import static atto.compiler.pipe.AttoDirectedPipePlan.*;

/** JVM-only typed compiler planning witness. No compiled .atto guest transport. */
public final class AttoDirectedPipePlanProbe {
    private static int checks;
    private static void ok(boolean good, String why) {
        if (!good) throw new AssertionError(why);
        checks++;
    }
    private static void reject(String expected, Runnable f) {
        try { f.run(); } catch (IllegalArgumentException ex) {
            ok(ex.getMessage().contains(expected), "wrong diagnostic " + ex);
            return;
        }
        throw new AssertionError("accepted bad directed pipeline: " + expected);
    }
    private static final String TENANT = "tenant_a";
    private static Stage producer() { return Stage.actor("producer", TENANT, "int", "int"); }
    private static Stage consumer() { return Stage.actor("consumer", TENANT, "int", null); }

    public static void main(String[] ignored) {
        Plan f = plan(List.of(producer(), consumer()), Direction.PIPE);
        Plan r = plan(List.of(consumer(), producer()), Direction.RPIPE);
        ok(f.dataEdges().get(0).source().equals("producer"), "forward DATA source");
        ok(r.dataEdges().get(0).source().equals("producer"), "reverse-written DATA source");
        ok(f.dataEdges().get(0).sink().equals(r.dataEdges().get(0).sink()), "same target");
        ok(f.dataEdges().get(0).itemType().equals(r.dataEdges().get(0).itemType()), "same type");
        ok(r.flowIndices().equals(List.of(1, 0)), "written order retained separately");
        ok(r.writtenStages().get(0).id().equals("consumer"), "no side-effect reordering");
        Plan mixed = plan(List.of(producer(), Stage.channel("buffer", TENANT,"int"),
            Stage.duplex("stream",TENANT,"int"), consumer()), Direction.PIPE);
        Plan mirror = plan(List.of(consumer(), Stage.duplex("stream",TENANT,"int"),
            Stage.channel("buffer",TENANT,"int"), producer()), Direction.RPIPE);
        ok(mixed.dataEdges().stream().map(Edge::source).toList()
           .equals(mirror.dataEdges().stream().map(Edge::source).toList()), "four-stage equivalence");
        ok(mixed.dataEdges().size() == 3, "three actual DATA edges");
        ok(mixed.dataEdges().stream().allMatch(x->x.itemType().equals("int")), "typed transport");
        Plan readable = plan(List.of(Stage.readable("reader",TENANT,"int"), consumer()), Direction.PIPE);
        ok(readable.dataEdges().size() == 1, "source-only readable");
        Plan terminal = plan(List.of(producer(), Stage.sink("sink",TENANT,"int")),Direction.PIPE);
        ok(terminal.dataEdges().size() == 1, "terminal sink");
        reject("E_PIPE_MISSING_EMITS",()->plan(List.of(
            Stage.actor("p",TENANT,"int",null),consumer()),Direction.PIPE));
        reject("E_PIPE_MISSING_RECEIVES",()->plan(List.of(
            producer(),Stage.readable("r",TENANT,"int")),Direction.PIPE));
        reject("E_PIPE_MISSING_EMITS",()->plan(List.of(consumer(),producer()),Direction.PIPE));
        reject("E_PIPE_TYPE_MISMATCH",()->plan(List.of(
            producer(),Stage.sink("sink",TENANT,"bool")),Direction.PIPE));
        reject("E_PIPE_TENANT_BOUNDARY",()->plan(List.of(producer(),
            Stage.actor("foreign","tenant_b","int",null)),Direction.PIPE));
        reject("E_PIPE_DUPLICATE_STAGE",()->plan(List.of(producer(),producer()),Direction.PIPE));
        reject("E_PIPE_READONLY_PORT",()->new Stage("s",TENANT,Endpoint.STREAM_READABLE,"int","int"));
        reject("E_PIPE_WRITEONLY_PORT",()->new Stage("s",TENANT,Endpoint.STREAM_SINK,null,"int"));
        reject("E_PIPE_CHANNEL_PORTS",()->new Stage("c",TENANT,Endpoint.CHANNEL,"int","bool"));
        reject("E_PIPE_TYPE_LABEL",()->Stage.actor("s",TENANT,"int\n","int"));
        reject("E_PIPE_ID",()->Stage.actor("bad\u202eid",TENANT,"int","int"));
        reject("E_PIPE_STAGE_LIMIT",()->plan(List.of(producer()),Direction.PIPE));
        List<Stage> many = new ArrayList<>();
        for (int i = 0; i < 17; i++)
            many.add(Stage.actor("a"+i,TENANT,i==0?null:"int",i==16?null:"int"));
        reject("E_PIPE_STAGE_LIMIT",()->plan(many,Direction.PIPE));
        reject("E_PIPE_DUPLICATE_EDGE",()->check_unicast_attachments(List.of(f,r)));
        reject("E_PIPE_OUTPUT_OCCUPIED",()->check_unicast_attachments(List.of(f,
            plan(List.of(producer(), Stage.sink("other",TENANT,"int")),Direction.PIPE))));
        check_unicast_attachments(List.of(f,plan(List.of(
            Stage.actor("other_producer",TENANT,"int","int"),
            Stage.sink("other_sink",TENANT,"int")), Direction.PIPE)));
        ok(true, "disjoint attachments allowed");
        System.out.println("ATTO_DIRECTED_PIPE_PREHIR_PASS checks=" + checks);
    }
}
