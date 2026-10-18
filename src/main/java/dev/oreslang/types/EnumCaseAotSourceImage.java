package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Staged source-identity-linked, closed-world eos2 AOT object manifest.
 *
 * Does NOT compile Oreslang guest bodies or authorize enum execution.
 * A real backend must populate body hashes from trusted compiled objects,
 * not guest-supplied strings. The linker must independently verify that
 * object/code hashes actually match emitted machine code.
 */
public final class EnumCaseAotSourceImage {
    private final EnumCaseSourceCallPlan source;
    private final Map<String, String> exactCompiledBodies;

    private EnumCaseAotSourceImage(
            EnumCaseSourceCallPlan plan, Map<String, String> bodies) {
        source = Objects.requireNonNull(plan, "compiler source-call plan");
        Objects.requireNonNull(bodies, "compiled body digests");
        Set<String> expected = source.directModuleSymbols();
        if (expected.isEmpty() || !expected.equals(bodies.keySet())) {
            throw new IllegalArgumentException("AOT image has missing or extra eos2 definition");
        }
        Map<String, String> checked = Map.copyOf(bodies);
        for (String digest : checked.values()) {
            if (!canonicalSha256(digest)) {
                throw new IllegalArgumentException("AOT body digest requires 64 lowercase hex digits");
            }
        }
        exactCompiledBodies = checked;
    }

    public static EnumCaseAotSourceImage seal(
            EnumCaseSourceCallPlan plan, Map<String, String> compiledBodyDigests) {
        return new EnumCaseAotSourceImage(plan, compiledBodyDigests);
    }

    /** Opaque compiler relocation, bound to exactly one image and call-node identity. */
    public static final class DirectCall {
        private final EnumCaseAotSourceImage image;
        private final Ast.CallExpr exactSourceCall;
        private final String symbol;
        private final String compiledBodyDigest;

        private DirectCall(EnumCaseAotSourceImage image, Ast.CallExpr node,
                           String symbol, String bodyDigest) {
            this.image = image;
            this.exactSourceCall = node;
            this.symbol = symbol;
            this.compiledBodyDigest = bodyDigest;
        }
        public String exactSymbol() { return symbol; }
        public String bodyDigest() { return compiledBodyDigest; }
    }

    public DirectCall relocate(Ast.Program program, Ast.CallExpr originalCall) {
        EnumCaseSourceCallPlan.BoundCall bound =
                source.requireExactCall(program, originalCall);
        String symbol = bound.exactSlot();
        String implementation = exactCompiledBodies.get(symbol);
        if (implementation == null) {
            throw new IllegalArgumentException("static eos2 call has no compiled definition");
        }
        return new DirectCall(this, originalCall, symbol, implementation);
    }

    public void verify(DirectCall relocation, Ast.Program original, Ast.CallExpr call) {
        if (relocation == null || relocation.image != this
                || relocation.exactSourceCall != call) {
            throw new IllegalArgumentException("stale or forged AOT source relocation");
        }
        EnumCaseSourceCallPlan.BoundCall selected = source.requireExactCall(original, call);
        if (!selected.exactSlot().equals(relocation.symbol)
                || !relocation.compiledBodyDigest.equals(
                        exactCompiledBodies.get(relocation.symbol))) {
            throw new IllegalArgumentException("AOT body or selected target has changed");
        }
    }

    public int definitionCount() { return exactCompiledBodies.size(); }

    private static boolean canonicalSha256(String digest) {
        if (digest == null || digest.length() != 64) return false;
        for (int i = 0; i < digest.length(); i++) {
            char ch = digest.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
