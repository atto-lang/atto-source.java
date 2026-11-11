package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Source-derived, compile-time-only nominal identity and typed logical call
 * contract. It consumes the checked protected operations MIR; it NEVER
 * constructs a runtime ActorRef, allocates an actor, invokes a method,
 * installs a code image or claims to have fenced a generation.
 *
 * Stable selectors are versioned SHA-256 ABI identifiers, not local method
 * ordinals. A runtime/backend must independently enforce code-image,
 * generation, mailbox and CONTROL boundaries before using such selectors.
 */
public final class VirtualActorLogicalCallMirV1 {
    private VirtualActorLogicalCallMirV1() { }

    private static final String NOMINAL_DOMAIN = "atto.virtual-actor.nominal.v1";
    private static final String METHOD_DOMAIN = "atto.virtual-actor.method.v1";

    /** Declarative backend obligations, not machine instructions. */
    public enum Requirement {
        RESOLVE_LOGICAL_ADDRESS,
        VERIFY_NOMINAL_CODE_IMAGE,
        ADMIT_GENERATION_FENCED_MAIL,
        ENCODE_CHECKED_I64_ARGUMENT,
        RETURN_ASYNC_RECEIPT
    }
    private static final List<Requirement> REQUIREMENTS = List.of(
            Requirement.RESOLVE_LOGICAL_ADDRESS,
            Requirement.VERIFY_NOMINAL_CODE_IMAGE,
            Requirement.ADMIT_GENERATION_FENCED_MAIL,
            Requirement.ENCODE_CHECKED_I64_ARGUMENT,
            Requirement.RETURN_ASYNC_RECEIPT);

    public static final class MethodAbi {
        private final String name;
        private final String selector;
        private final Ast.TypeRef parameterType;
        private final Ast.TypeRef returnType;

        private MethodAbi(String name, String selector,
                          Ast.TypeRef parameterType, Ast.TypeRef returnType) {
            this.name = Objects.requireNonNull(name);
            this.selector = Objects.requireNonNull(selector);
            this.parameterType = Objects.requireNonNull(parameterType);
            this.returnType = Objects.requireNonNull(returnType);
        }

        public String name() { return name; }
        public String selector() { return selector; }
        public Ast.TypeRef parameterType() { return parameterType; }
        public Ast.TypeRef returnType() { return returnType; }
    }

    /** Pure metadata. No physical incarnation, raw pointer or live mailbox. */
    public static final class RefType {
        private final String qualifiedName;
        private final String nominalId;
        private final List<MethodAbi> methods;

        private RefType(String qualifiedName, String nominalId,
                        List<MethodAbi> methods) {
            this.qualifiedName = Objects.requireNonNull(qualifiedName);
            this.nominalId = Objects.requireNonNull(nominalId);
            this.methods = List.copyOf(methods);
        }

        public String qualifiedName() { return qualifiedName; }
        public String nominalId() { return nominalId; }
        public String spelling() { return "VirtualActorRef[" + qualifiedName + "]"; }
        public List<MethodAbi> methods() { return methods; }

        /**
         * Resolve one method using exact checked caller types. This returns
         * only compile-time requirements, NEVER a callable runtime handle.
         */
        public CallPlan checkCall(String name, List<Ast.TypeRef> argumentTypes) {
            Objects.requireNonNull(name, "method");
            List<Ast.TypeRef> args = List.copyOf(argumentTypes);
            MethodAbi slot = methods.stream()
                    .filter(method -> method.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> reject("unknown virtual method: " + name));
            if (args.size() != 1 || !slot.parameterType().equals(args.getFirst()))
                throw reject("incorrect typed virtual method arguments: " + name);
            return new CallPlan(nominalId, slot.selector(), slot.returnType(),
                    REQUIREMENTS);
        }
    }

    public static final class CallPlan {
        private final String requiredNominalId;
        private final String methodSelector;
        private final Ast.TypeRef methodReturn;
        private final List<Requirement> obligations;

        private CallPlan(String requiredNominalId, String methodSelector,
                         Ast.TypeRef methodReturn, List<Requirement> obligations) {
            this.requiredNominalId = Objects.requireNonNull(requiredNominalId);
            this.methodSelector = Objects.requireNonNull(methodSelector);
            this.methodReturn = Objects.requireNonNull(methodReturn);
            this.obligations = List.copyOf(obligations);
        }

        public String requiredNominalId() { return requiredNominalId; }
        public String methodSelector() { return methodSelector; }
        public Ast.TypeRef methodReturn() { return methodReturn; }
        public List<Requirement> obligations() { return obligations; }
    }

    public static final class ProgramPlan {
        private final VirtualActorProtectedOpsMirV1.ProgramPlan checkedOps;
        private final RefType reference;

        private ProgramPlan(VirtualActorProtectedOpsMirV1.ProgramPlan checkedOps,
                            RefType reference) {
            this.checkedOps = Objects.requireNonNull(checkedOps);
            this.reference = Objects.requireNonNull(reference);
        }

        public VirtualActorProtectedOpsMirV1.ProgramPlan checkedOps() {
            return checkedOps;
        }
        public RefType reference() { return reference; }
    }

    /**
     * Explicitly restricted, closed single-declaration compilation unit.
     * Multi-file imports and cross-module type references still need the
     * production binder and are NOT silently resolved by this proof slice.
     */
    public static ProgramPlan lower(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        if (!program.imports().isEmpty() || program.modules().size() != 1)
            throw reject("closed single-module unit required");
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()
                || module.declarations().size() != 1
                || !(module.declarations().getFirst() instanceof Ast.VirtualActorDecl actor))
            throw reject("exactly one plain virtual actor declaration required");
        return lower(program.namespace(), module.name(), actor);
    }

    /**
     * Lower a compiler-identified actor in its lexical module. A caller
     * cannot bypass the exact lexer-token and protected-ops checks: this
     * always revalidates the complete actor declaration.
     */
    public static ProgramPlan lower(String namespace, String module,
                                    Ast.VirtualActorDecl actor) {
        Objects.requireNonNull(actor, "actor");
        String normalizedNamespace = namespace == null ? "" : namespace;
        requireDottedName(normalizedNamespace, true);
        Objects.requireNonNull(module, "module");
        String moduleComponent;
        if (Parser.ROOT_MODULE.equals(module)) {
            moduleComponent = "root";
        } else {
            requireDottedName(module, false);
            moduleComponent = "module:" + module;
        }
        VirtualActorProtectedOpsMirV1.ProgramPlan checked =
                VirtualActorProtectedOpsMirV1.lower(actor);
        String actorName = checked.declaration();
        requireDottedName(actorName, false);
        String nominalId = digest(NOMINAL_DOMAIN, normalizedNamespace,
                moduleComponent, actorName);
        String qualifiedName = (normalizedNamespace.isEmpty() ? ""
                : normalizedNamespace + ".")
                + (Parser.ROOT_MODULE.equals(module) ? "" : module + ".")
                + actorName;
        List<MethodAbi> abi = new ArrayList<>();
        Set<String> selectors = new HashSet<>();
        for (VirtualActorProtectedOpsMirV1.MethodPlan method : checked.methods()) {
            var sig = method.signature();
            // V1 supports exactly one value-typed i64 message and void
            // guest method. No reference-type argument or dynamic method.
            if (sig.arity() != 1
                    || !sig.parameterType().equals(Ast.TypeRef.simple("int"))
                    || !sig.returnType().equals(Ast.TypeRef.simple("void")))
                throw reject("unsupported virtual method ABI");
            String selector = digest(METHOD_DOMAIN, nominalId, sig.method(),
                    "int", "void");
            if (!selectors.add(selector))
                throw reject("duplicate virtual method ABI selector");
            abi.add(new MethodAbi(sig.method(), selector,
                    sig.parameterType(), sig.returnType()));
        }
        abi.sort(Comparator.comparing(MethodAbi::name));
        return new ProgramPlan(checked,
                new RefType(qualifiedName, nominalId, abi));
    }

    private static void requireDottedName(String value, boolean permitEmpty) {
        if (value == null || value.length() > 512
                || (!permitEmpty && value.isEmpty()))
            throw reject("invalid qualified nominal name");
        if (value.isEmpty()) return;
        for (String segment : value.split("\\.", -1)) {
            if (!segment.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
                throw reject("invalid namespace/module segment");
        }
    }

    /** Length-framed domain separation avoids ambiguous concatenated IDs. */
    private static String digest(String... components) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String component : components) {
                byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 required for ABI", impossible);
        }
    }

    private static IllegalArgumentException reject(String message) {
        return new IllegalArgumentException(
                "VIRTUAL_ACTOR_CALL_ABI_V1_REJECTED: " + message);
    }
}
