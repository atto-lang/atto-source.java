package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresInteropRootNode;
import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.SandboxPolicy;

@TruffleLanguage.Registration(
        id = OresLanguage.ID,
        name = "Oreslang",
        version = "0.1.0",
        defaultMimeType = OresLanguage.MIME_TYPE,
        characterMimeTypes = OresLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE,
        sandbox = SandboxPolicy.UNTRUSTED,
        website = "https://github.com/ores-truffle-oreslang/oreslang-source.java")
public final class OresLanguage extends TruffleLanguage<OresContext> {
    public static final String ID = "ores";
    public static final String MIME_TYPE = "application/x-oreslang";

    @Override
    protected OresContext createContext(Env env) {
        return new OresContext(this, env);
    }

    /**
     * Guest AST execution is still single-context-thread-at-a-time.
     *
     * ActorRuntime dispatchers are a host scheduling substrate; source-level
     * actor lowering must not execute Truffle guest ASTs concurrently until the
     * language opts into and initializes Truffle multi-threading explicitly.
     * Sequential migration between carrier threads remains allowed.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded;
    }

    @Override
    protected void disposeContext(OresContext context) {
        context.close();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String text = request.getSource().getCharacters().toString();
        Ast.Program program = OresCompiler.parseAndTypeCheck(text);
        RootCallTarget evaluator = new OresEvalRootNode(this, program).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }
}
