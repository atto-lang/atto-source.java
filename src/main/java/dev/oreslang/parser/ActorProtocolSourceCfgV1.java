package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Restricted source-AST -> affine protocol CFG projection. Only real
 * production Parser AST nodes are accepted. Unsupported effects (including
 * awaits, unwinding and dynamic dispatch) must fail closed, never disappear.
 *
 * The trusted entry handle map is an EXTERNAL spawn provenance obligation.
 * A source author naming a type ProtocolHandle<T> does not establish it.
 * This is a source-only preflight API; no backend may emit guest operations
 * merely because a source projection or CFG proof succeeds.
 */
public final class ActorProtocolSourceCfgV1 {
    private ActorProtocolSourceCfgV1() { }
    public static final int MAX_BLOCKS = 256;
    public static final int MAX_BINDINGS = 128;

    public record Projection(ActorProtocolAffineCfgV1.Program cfg,
                             ActorProtocolAffineCfgV1.Proof proof) { }

    private static IllegalArgumentException error(String code, String why) {
        return new IllegalArgumentException(code + ": " + why);
    }

    /**
     * Full source-local admission: lex the actual .atto source, parse the
     * protocol prelude, build the real AST and derive nominal reply modes.
     * No external message-map or caller-provided AST can spoof this path.
     * The trusted handle map must still come from separately authenticated
     * compiler spawn/binding evidence (not a source spelling).
     */
    public static Projection verifySource(
            String source, String functionName, Map<String, String> trustedEntryHandles) {
        List<Token> tokens = new Lexer(Objects.requireNonNull(source)).scan();
        var catalog = ActorProtocolTypestateV1.parseTokens(tokens);
        Ast.Program ast = new Parser(
                ActorProtocolTypestateV1.withoutProtocolPrelude(tokens)).parseProgram();
        var nominal = ActorProtocolNominalResolverV1.resolve(catalog, ast);
        return verifyFunction(ast, functionName, nominal, trustedEntryHandles);
    }

    /**
     * Cross-check an already parsed real AST plus nominal declarations with
     * trusted entry owner names. Only a single named source function is
     * projected: callers cannot use this as proof for other declarations.
     */
    public static Projection verifyFunction(
            Ast.Program ast, String functionName,
            ActorProtocolNominalResolverV1.ResolvedDeclarations declarations,
            Map<String, String> trustedEntryHandles) {
        Objects.requireNonNull(ast);
        Objects.requireNonNull(declarations);
        Objects.requireNonNull(trustedEntryHandles);
        if (!ast.imports().isEmpty())
            throw error("E_PROTOCOL_SOURCE_UNLINKED_IMPORT",
                    "cross-file type identities require checked linker metadata");
        for (Ast.ModuleDecl module : ast.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                String declaredName = null;
                if (declaration instanceof Ast.ClassDecl c) declaredName = c.name();
                if (declaration instanceof Ast.InterfaceDecl i) declaredName = i.name();
                if (declaration instanceof Ast.TypeAliasDecl t) declaredName = t.name();
                if (declaredName != null && Set.of("ProtocolHandle",
                        "UnaryLease", "StreamLease").contains(declaredName))
                    throw error("E_PROTOCOL_SOURCE_FORGED_HANDLE_TYPE",
                            "compiler-owned source modeling type " + declaredName);
            }
        }
        Ast.FunctionDecl function = null;
        for (Ast.ModuleDecl module : ast.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl candidate
                        && candidate.name().equals(functionName)) {
                    if (function != null)
                        throw error("E_PROTOCOL_SOURCE_AMBIGUOUS", functionName);
                    function = candidate;
                }
            }
        }
        if (function == null) throw error("E_PROTOCOL_SOURCE_FUNCTION", functionName);
        Builder builder = new Builder(declarations, function, trustedEntryHandles);
        var cfg = builder.compile();
        return new Projection(cfg,
                ActorProtocolAffineCfgV1.verifyNominallyResolved(declarations, cfg));
    }

    private static final class MutableBlock {
        final int id;
        final List<ActorProtocolAffineCfgV1.Op> ops = new ArrayList<>();
        final List<Integer> successors = new ArrayList<>();
        MutableBlock(int id) { this.id = id; }
        ActorProtocolAffineCfgV1.Block freeze() {
            return new ActorProtocolAffineCfgV1.Block(id, ops, successors);
        }
    }

    private static final class Builder {
        final ActorProtocolNominalResolverV1.ResolvedDeclarations declarations;
        final Ast.FunctionDecl function;
        final Map<String, String> trusted;
        final Map<String, Integer> owners = new LinkedHashMap<>();
        final Map<String, Integer> leases = new LinkedHashMap<>();
        final Map<String, String> messages = new LinkedHashMap<>();
        final Map<String, String> ownerProtocols = new LinkedHashMap<>();
        final Map<String, Ast.TypeRef> parameterTypes = new LinkedHashMap<>();
        final List<MutableBlock> blocks = new ArrayList<>();
        final Set<String> defined = new HashSet<>();
        int placeId = 1, leaseId = 1;
        MutableBlock current;
        Builder(ActorProtocolNominalResolverV1.ResolvedDeclarations declarations,
                Ast.FunctionDecl function, Map<String, String> trusted) {
            this.declarations = declarations;
            this.function = function;
            this.trusted = Map.copyOf(trusted);
        }
        MutableBlock newBlock() {
            if (blocks.size() >= MAX_BLOCKS)
                throw error("E_PROTOCOL_SOURCE_BUDGET", "too many CFG blocks");
            MutableBlock block = new MutableBlock(blocks.size());
            blocks.add(block);
            return block;
        }
        void emit(ActorProtocolAffineCfgV1.Op op) {
            if (current == null)
                throw error("E_PROTOCOL_SOURCE_UNREACHABLE", "statement after unconditional return");
            current.ops.add(op);
        }
        void declare(String name) {
            if (!defined.add(name) || defined.size() > MAX_BINDINGS)
                throw error("E_PROTOCOL_SOURCE_BINDING", "duplicate or excessive binding " + name);
        }
        int owner(String name) {
            Integer id = owners.get(name);
            if (id == null) throw error("E_PROTOCOL_SOURCE_UNVERIFIED_OWNER", name);
            return id;
        }
        int lease(String name) {
            Integer id = leases.get(name);
            if (id == null) throw error("E_PROTOCOL_SOURCE_UNVERIFIED_LEASE", name);
            return id;
        }
        static String name(Ast.Expr expr) {
            if (expr instanceof Ast.NameExpr n) return n.name();
            throw error("E_PROTOCOL_SOURCE_EFFECT", "expected resolved local name");
        }
        static String protocolType(Ast.TypeRef ref) {
            if (ref == null || !ref.name().equals("ProtocolHandle")
                    || ref.inferArguments() || ref.arguments().size() != 1
                    || !ref.arguments().getFirst().arguments().isEmpty())
                throw error("E_PROTOCOL_SOURCE_TYPE", "expected ProtocolHandle<Protocol>");
            return ref.arguments().getFirst().name();
        }
        ActorProtocolAffineCfgV1.Program compile() {
            // This slice must not silently erase effects, dynamic dispatch
            // or hidden callable state from a seemingly ordinary function.
            if (function.generator() || function.structural() || function.trapped()
                    || function.actorKind() != Ast.ActorKind.NONE
                    || !function.genericParameters().isEmpty()
                    || !function.genericSpecs().isEmpty()
                    || !function.effects().isEmpty()) {
                throw error("E_PROTOCOL_SOURCE_EFFECT",
                        "unsupported callable shape/effects");
            }
            current = newBlock();
            for (Ast.Param param : function.parameters()) {
                declare(param.name());
                if (parameterTypes.putIfAbsent(param.name(), param.type()) != null)
                    throw error("E_PROTOCOL_SOURCE_BINDING", param.name());
                if (trusted.containsKey(param.name())) {
                    String protocol = protocolType(param.type());
                    if (!protocol.equals(trusted.get(param.name())))
                        throw error("E_PROTOCOL_SOURCE_AUTHORITY", "typed entry authority mismatch");
                    if (declarations.protocol().protocols().stream()
                            .noneMatch(p -> p.name().equals(protocol)))
                        throw error("E_PROTOCOL_SOURCE_AUTHORITY", "unknown protocol " + protocol);
                    int id = placeId++;
                    owners.put(param.name(), id);
                    ownerProtocols.put(param.name(), protocol);
                    emit(new ActorProtocolAffineCfgV1.Init(id, protocol));
                } else if (param.type() != null && param.type().name().equals("ProtocolHandle")) {
                    throw error("E_PROTOCOL_SOURCE_AUTHORITY",
                            "unproven source-declared owner " + param.name());
                } else if (param.type() != null && !param.type().inferArguments()
                        && param.type().arguments().isEmpty()) {
                    messages.put(param.name(), param.type().name());
                }
            }
            if (!owners.keySet().equals(trusted.keySet()))
                throw error("E_PROTOCOL_SOURCE_AUTHORITY", "trusted handle not in formal parameters");
            statements(function.body());
            if (current != null)
                throw error("E_PROTOCOL_SOURCE_IMPLICIT_EXIT", "all source paths require explicit return");
            return new ActorProtocolAffineCfgV1.Program(0, function.async() || function.suspends(),
                    blocks.stream().map(MutableBlock::freeze).toList());
        }
        void statements(List<Ast.Stmt> statements) {
            for (Ast.Stmt stmt : statements) statement(stmt);
        }
        void statement(Ast.Stmt stmt) {
            if (current == null)
                throw error("E_PROTOCOL_SOURCE_UNREACHABLE", "code after return");
            if (stmt instanceof Ast.BlockStmt block) {
                statements(block.body());
            } else if (stmt instanceof Ast.BindingStmt binding) {
                declare(binding.name());
                if (binding.initializer() instanceof Ast.RuntimeCallExpr rt
                        && rt.operation().equals("take") && rt.arguments().size() == 1) {
                    String from = name(rt.arguments().getFirst());
                    String p = ownerProtocols.get(from);
                    if (p == null || !p.equals(protocolType(binding.declaredType())))
                        throw error("E_PROTOCOL_SOURCE_TYPE", "move type must be identical");
                    int into = placeId++;
                    owners.put(binding.name(), into);
                    ownerProtocols.put(binding.name(), p);
                    emit(new ActorProtocolAffineCfgV1.Take(owner(from), into));
                } else if (binding.initializer() instanceof Ast.CallExpr call
                        && call.callee() instanceof Ast.MemberExpr member
                        && member.member().equals("request")
                        && member.receiver() instanceof Ast.NameExpr origin
                        && call.arguments().size() == 1 && !call.typeArgumentsPresent()) {
                    if (binding.declaredType() == null
                            || binding.declaredType().arguments().size() != 1
                            || binding.declaredType().inferArguments())
                        throw error("E_PROTOCOL_SOURCE_TYPE", "request needs explicit lease type");
                    String message = messages.get(name(call.arguments().getFirst()));
                    if (message == null)
                        throw error("E_PROTOCOL_SOURCE_TYPE", "request payload must be a typed argument");
                    var nominal = declarations.messages().get(message);
                    if (nominal == null)
                        throw error("E_PROTOCOL_UNVERIFIED_MESSAGE", message);
                    String leaseKind = nominal.mode() == ActorProtocolTypestateV1.Mode.UNARY
                            ? "UnaryLease" : "StreamLease";
                    if (!binding.declaredType().name().equals(leaseKind)
                            || !binding.declaredType().arguments().getFirst().name()
                                    .equals(nominal.responseType()))
                        throw error("E_PROTOCOL_SOURCE_TYPE", "lease type mismatches nominal response");
                    String protocol = ownerProtocols.get(origin.name());
                    if (protocol == null)
                        throw error("E_PROTOCOL_SOURCE_UNVERIFIED_OWNER", origin.name());
                    // Determine the sole source-local message payload shape.
                    String payload = null;
                    boolean first = true;
                    for (var p : declarations.protocol().protocols()) {
                        if (!p.name().equals(protocol)) continue;
                        for (var transition : p.transitions()) {
                            if (!transition.message().equals(message)) continue;
                            if (!first && !Objects.equals(payload, transition.parameterType()))
                                throw error("E_PROTOCOL_MESSAGE_PAYLOAD", message);
                            first = false;
                            payload = transition.parameterType();
                        }
                    }
                    if (first)
                        throw error("E_PROTOCOL_ILLEGAL_MESSAGE_STATE", message);
                    int id = leaseId++;
                    leases.put(binding.name(), id);
                    emit(new ActorProtocolAffineCfgV1.Begin(owner(origin.name()), id,
                            message, payload, nominal.mode(), nominal.responseType()));
                } else {
                    throw error("E_PROTOCOL_SOURCE_EFFECT", "unhandled binding initializer " + binding.name());
                }
            } else if (stmt instanceof Ast.ExprStmt expr) {
                if (expr.expression() instanceof Ast.CallExpr call
                        && call.callee() instanceof Ast.MemberExpr member
                        && member.receiver() instanceof Ast.NameExpr recv
                        && member.member().equals("cancel")
                        && call.arguments().isEmpty() && !call.typeArgumentsPresent()) {
                    emit(new ActorProtocolAffineCfgV1.CancelLease(lease(recv.name())));
                } else {
                    throw error("E_PROTOCOL_SOURCE_EFFECT", "unproven expression or await");
                }
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                if (ret.value() != null) {
                    String value = name(ret.value());
                    String actual = ownerProtocols.get(value);
                    String declared = protocolType(function.returnType());
                    if (!declared.equals(actual))
                        throw error("E_PROTOCOL_SOURCE_TYPE", "return handle mismatches protocol");
                    emit(new ActorProtocolAffineCfgV1.ReturnHandle(owner(value)));
                } else if (function.returnType() == null
                        || !function.returnType().name().equals("void")) {
                    throw error("E_PROTOCOL_SOURCE_TYPE", "nonvoid function returned void");
                }
                emit(new ActorProtocolAffineCfgV1.Return());
                current = null;
            } else if (stmt instanceof Ast.IfStmt iff) {
                if (iff.branches().size() != 1 || iff.elseBody().isEmpty())
                    throw error("E_PROTOCOL_SOURCE_BRANCH", "only full binary if/else");
                Ast.Expr guard = iff.branches().getFirst().condition();
                boolean pureBoolean = guard instanceof Ast.LiteralExpr lit
                        && lit.value() instanceof Boolean
                        || guard instanceof Ast.NameExpr n
                        && parameterTypes.get(n.name()) != null
                        && parameterTypes.get(n.name()).name().equals("bool")
                        && parameterTypes.get(n.name()).arguments().isEmpty();
                if (!pureBoolean)
                    throw error("E_PROTOCOL_SOURCE_BRANCH",
                            "condition must be a known pure bool parameter or literal");
                MutableBlock from = current;
                MutableBlock yes = newBlock(), no = newBlock();
                from.successors.add(yes.id);
                from.successors.add(no.id);
                current = yes;
                statements(iff.branches().getFirst().body());
                MutableBlock yesTail = current;
                current = no;
                statements(iff.elseBody());
                MutableBlock noTail = current;
                if (yesTail == null && noTail == null) {
                    current = null;
                } else {
                    MutableBlock join = newBlock();
                    if (yesTail != null) yesTail.successors.add(join.id);
                    if (noTail != null) noTail.successors.add(join.id);
                    current = join;
                }
            } else {
                // Includes try/catch/finally, loops, match, select, defers,
                // async tasks, thrown errors and unrecognized ownership ops.
                throw error("E_PROTOCOL_SOURCE_EFFECT",
                        "unsupported source statement: " + stmt.getClass().getSimpleName());
            }
        }
    }
}
