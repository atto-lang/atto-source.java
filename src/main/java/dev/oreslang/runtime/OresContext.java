package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;

public final class OresContext implements AutoCloseable {
    private static final ContextReference<OresContext> REFERENCE = ContextReference.create(OresLanguage.class);

    private final OresLanguage language;
    private final TruffleLanguage.Env env;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ActorRuntime actors = new ActorRuntime();
    private final UUID contextId = UUID.randomUUID();

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
    }

    public static OresContext get(Node node) {
        return REFERENCE.get(node);
    }

    public OresLanguage language() { return language; }
    public TruffleLanguage.Env env() { return env; }
    public BufferedReader input() { return input; }
    public PrintWriter output() { return output; }
    public ActorRuntime actors() { return actors; }
    public UUID contextId() { return contextId; }

    public Map<String, Object> processDescriptor() {
        return Map.of(
                "context_id", contextId.toString(),
                "runtime", "graalvm-truffle",
                "language", "oreslang");
    }

    @Override
    public void close() {
        actors.close();
        output.flush();
    }
}
