package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.EnumCaseOverloadPolicy.Domain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Narrow, compiler-side source AST -> exact declaration/ABI binding bridge.
 *
 * Staged NONEXECUTABLE slice: this proves local, closed-world call targets
 * only for a synchronous, parameterless main containing immutable, explicitly
 * typed nullary enum constants and direct calls. TypeChecker, ownership,
 * runtime and LLVM may NOT treat this as guest execution authorization.
 *
 * No caller-supplied type refs are accepted as evidence for a call. The
 * argument proof originates from the exact parsed AST nodes and a local,
 * collision-free enum declaration registry. This is still NOT authenticated
 * cross-module/import/flow-sensitive typechecking.
 */
public final class EnumCaseSourceCallPlan {
    private final Ast.Program source;
    private final EnumCaseSourceBindings.Proof declarations;
    private final List<BoundCall> orderedCalls;
    private final Map<Ast.CallExpr, BoundCall> byIdentity;

    public static final class BoundCall {
        private final Ast.CallExpr sourceCall;
        private final Ast.FunctionDecl exactFunction;
        private final String exactSlot;
        private final List<Ast.TypeRef> staticArgumentTypes;

        private BoundCall(Ast.CallExpr sourceCall,
                          EnumCaseSourceBindings.Candidate selected,
                          List<Ast.TypeRef> staticArgumentTypes) {
            this.sourceCall = sourceCall;
            if (selected.function() == null || selected.method() != null) {
                throw new IllegalArgumentException("direct AOT slice rejects virtual methods");
            }
            this.exactFunction = selected.function();
            if (exactFunction.actorKind() != Ast.ActorKind.NONE
                    || exactFunction.async() || exactFunction.suspends()
                    || exactFunction.generator() || exactFunction.trapped()
                    || exactFunction.structural() || exactFunction.nonLexical()
                    || !exactFunction.genericParameters().isEmpty()
                    || !exactFunction.genericSpecs().isEmpty()
                    || !exactFunction.annotations().isEmpty()) {
                throw new IllegalArgumentException("AOT direct-call slice requires an ordinary synchronous function");
            }
            this.exactSlot = selected.signature().symbolId();
            this.staticArgumentTypes = List.copyOf(staticArgumentTypes);
        }
        public String exactSlot() { return exactSlot; }
        public Ast.FunctionDecl exactFunction() { return exactFunction; }
        public List<Ast.TypeRef> staticArgumentTypes() { return staticArgumentTypes; }
    }

    private EnumCaseSourceCallPlan(Ast.Program source,
                                   EnumCaseSourceBindings.Proof declarations,
                                   List<BoundCall> calls) {
        this.source = source;
        this.declarations = declarations;
        this.orderedCalls = List.copyOf(calls);
        IdentityHashMap<Ast.CallExpr, BoundCall> index = new IdentityHashMap<>();
        for (BoundCall site : calls) {
            if (index.put(site.sourceCall, site) != null) {
                throw new IllegalArgumentException("duplicate source call identity");
            }
        }
        this.byIdentity = Collections.unmodifiableMap(index);
    }

    public static EnumCaseSourceCallPlan prepare(Ast.Program program) {
        Objects.requireNonNull(program, "source program");
        // compileLocalProof disallows imports and certifies local enum/case
        // declaration families. It does not enable runtime enum execution.
        EnumCaseSourceBindings.Proof proof = EnumCaseSourceBindings.compileLocalProof(program);
        if (program.modules().size() != 1) {
            throw new IllegalArgumentException("AOT literal slice requires one module");
        }
        Ast.ModuleDecl module = program.modules().get(0);
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw new IllegalArgumentException("AOT literal module contracts/annotations unsupported");
        }
        String scope = program.namespace() == null ? "local_unit" : program.namespace();
        String owner = scope + "." + module.name();
        List<Ast.FunctionDecl> entrypoints = module.declarations().stream()
                .filter(d -> d instanceof Ast.FunctionDecl f && f.name().equals("main"))
                .map(d -> (Ast.FunctionDecl) d).toList();
        if (entrypoints.size() != 1) {
            throw new IllegalArgumentException("AOT literal slice requires one main");
        }
        Ast.FunctionDecl main = entrypoints.get(0);
        if (!main.parameters().isEmpty() || !main.genericParameters().isEmpty()
                || main.async() || main.suspends() || main.generator()
                || main.actorKind() != Ast.ActorKind.NONE
                || main.trapped() || main.structural() || main.nonLexical()
                || !main.genericSpecs().isEmpty() || !main.annotations().isEmpty()) {
            throw new IllegalArgumentException("unsupported main entrypoint effects/parameters");
        }
        if (main.returnType() == null || !main.returnType().name().equals("int")
                || !main.returnType().arguments().isEmpty() || main.returnType().inferArguments()) {
            throw new IllegalArgumentException("AOT literal main must return int");
        }
        Map<String, Ast.TypeRef> localTypes = new LinkedHashMap<>();
        List<BoundCall> sites = new ArrayList<>();
        boolean returned = false;
        for (Ast.Stmt stmt : main.body()) {
            if (returned) {
                throw new IllegalArgumentException("source after AOT main return");
            }
            if (stmt instanceof Ast.BindingStmt binding) {
                if (!sites.isEmpty()) {
                    throw new IllegalArgumentException("AOT const declarations must precede calls");
                }
                if (binding.kind() != Ast.BindingKind.CONST
                        || binding.mutableReferent() || binding.inferredTypeName() != null
                        || binding.declaredType() == null || binding.initializer() == null
                        || localTypes.containsKey(binding.name())
                        || proof.families().keySet().stream().anyMatch(k -> k.name().equals(binding.name()))
                        || proof.nullaryTagLayouts().keySet().stream().anyMatch(
                                id -> id.equals(owner + "." + binding.name()))) {
                    throw new IllegalArgumentException("unsupported/shadowed AOT local");
                }
                // Only proven nullary case initializers and their parent enum
                // or exact case-refinement annotations may enter the ledger.
                Ast.TypeRef actual = caseLiteralType(binding.initializer(), owner, proof);
                String declared = checkedSimpleType(binding.declaredType());
                String actualName = actual.name();
                int dot = actualName.indexOf('.');
                if (!declared.equals(actualName)
                        && !declared.equals(actualName.substring(0, dot))) {
                    throw new IllegalArgumentException("local annotation is not a subtype target");
                }
                localTypes.put(binding.name(), binding.declaredType());
            } else if (stmt instanceof Ast.ExprStmt expression) {
                if (!(expression.expression() instanceof Ast.CallExpr call)) {
                    throw new IllegalArgumentException("AOT literal slice allows direct calls only");
                }
                sites.add(bind(call, owner, proof, localTypes));
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                if (!(ret.value() instanceof Ast.LiteralExpr literal)
                        || !(literal.value() instanceof Long || literal.value() instanceof Integer)) {
                    throw new IllegalArgumentException("AOT literal slice returns an int literal");
                }
                returned = true;
            } else {
                throw new IllegalArgumentException("unsupported statements in AOT literal slice");
            }
        }
        if (!returned || sites.isEmpty()) {
            throw new IllegalArgumentException("AOT literal slice requires calls and return");
        }
        return new EnumCaseSourceCallPlan(program, proof, sites);
    }

    private static BoundCall bind(Ast.CallExpr call, String owner,
                                  EnumCaseSourceBindings.Proof proof,
                                  Map<String, Ast.TypeRef> locals) {
        if (!(call.callee() instanceof Ast.NameExpr name)
                || call.typeArgumentsPresent() || call.arguments().size() > 64
                || locals.containsKey(name.name())) {
            throw new IllegalArgumentException("unresolved or non-direct AOT call");
        }
        EnumCaseSourceBindings.Key family =
                new EnumCaseSourceBindings.Key(Domain.MODULE, owner,
                        name.name(), call.arguments().size());
        List<Ast.TypeRef> staticTypes = new ArrayList<>();
        for (Ast.Expr argument : call.arguments()) {
            if (argument instanceof Ast.NameExpr identifier
                    && locals.containsKey(identifier.name())) {
                staticTypes.add(locals.get(identifier.name()));
            } else if (argument instanceof Ast.MemberExpr) {
                staticTypes.add(caseLiteralType(argument, owner, proof));
            } else if (argument instanceof Ast.LiteralExpr literal) {
                Object value = literal.value();
                if (value instanceof Long || value instanceof Integer) {
                    staticTypes.add(Ast.TypeRef.simple("int"));
                } else if (value instanceof String) {
                    staticTypes.add(Ast.TypeRef.simple("string"));
                } else if (value instanceof Boolean) {
                    staticTypes.add(Ast.TypeRef.simple("bool"));
                } else if (value instanceof Double) {
                    staticTypes.add(Ast.TypeRef.simple("float"));
                } else {
                    throw new IllegalArgumentException("unproven scalar argument type");
                }
            } else {
                throw new IllegalArgumentException("AOT call argument lacks static proof");
            }
        }
        return new BoundCall(call, proof.selectStagedTypeRefs(family, staticTypes), staticTypes);
    }

    private static Ast.TypeRef caseLiteralType(
            Ast.Expr expression, String owner, EnumCaseSourceBindings.Proof proof) {
        if (!(expression instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr en)) {
            throw new IllegalArgumentException("not a literal enum case constructor");
        }
        EnumTag32Plan layout = proof.nullaryTagLayouts().get(owner + "." + en.name());
        if (layout == null) {
            throw new IllegalArgumentException("unlinked/non-nullary enum case");
        }
        // This validates the actual declared case and the statically known
        // wire tag, but never selects overloads by looking at that tag.
        layout.constantTag(member.member());
        return Ast.TypeRef.simple(en.name() + "." + member.member());
    }

    private static String checkedSimpleType(Ast.TypeRef type) {
        if (type.inferArguments() || !type.arguments().isEmpty()) {
            throw new IllegalArgumentException("generic/structural const type unsupported");
        }
        String name = type.name();
        if (name == null || name.isBlank() || name.indexOf('.', name.indexOf('.') + 1) >= 0) {
            throw new IllegalArgumentException("unsupported const type identity");
        }
        return name;
    }

    public List<BoundCall> calls() { return orderedCalls; }
    public int familyCount() { return declarations.families().size(); }

    /** Must pass the SAME program and AST call object; record equality is unsafe. */
    public BoundCall requireExactCall(Ast.Program original, Ast.CallExpr call) {
        if (original != source) {
            throw new IllegalArgumentException("stale compiler image");
        }
        BoundCall result = byIdentity.get(call);
        if (result == null) {
            throw new IllegalArgumentException("unbound or cloned call AST");
        }
        return result;
    }
}
