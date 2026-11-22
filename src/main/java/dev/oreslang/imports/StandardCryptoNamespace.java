package dev.oreslang.imports;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.parser.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checked source-first implementation of the built-in std.crypto policy
 * namespace. The trusted Maven resource pipeline packages the independent
 * .atto library files at dev/oreslang/std/std/*.atto, including for Native Image.
 *
 * This is not an arbitrary filesystem/package importer and does not grant
 * cryptographic host capabilities. The only bundled source units are the
 * two exact paths enumerated below; user paths can never select or replace them.
 */
public final class StandardCryptoNamespace {
    private static final List<String> RESOURCE_FILES = List.of(
            "/dev/oreslang/std/std/crypto.atto",
            "/dev/oreslang/std/std/crypto_envelope.atto");
    private static final int MAX_SOURCE_BYTES = 64 * 1024;
    private static final String REQUIRED_NAMESPACE = "crypto";

    /** Host primitives stay distinct from source policy to prevent shadowing. */
    private static final java.util.Set<String> HOST_MEMBERS = java.util.Set.of(
            "sha256", "hmac_sha256", "pbkdf2_hmac_sha256",
            "base64_encode", "base64_decode", "secure_random_bytes",
            "constant_time_equals", "aead_new_key", "aead_seal",
            "aead_open", "aead_close");

    public record Signature(List<Ast.TypeRef> arguments, Ast.TypeRef result) {
        public Signature {
            arguments = List.copyOf(arguments);
        }
    }

    private record Bundle(Ast.Program checkedProgram, Map<String, Signature> signatures) {
        private Bundle {
            signatures = Map.copyOf(signatures);
        }
    }

    private static volatile Bundle verifiedBundle;

    private StandardCryptoNamespace() { }

    public static Ast.Program checkedProgram() {
        return bundle().checkedProgram();
    }

    /** Null means unknown, not an unchecked dynamic callable. */
    public static Signature signature(String member) {
        return bundle().signatures().get(member);
    }

    public static boolean hasPolicyMember(String member) {
        return signature(member) != null;
    }

    private static Bundle bundle() {
        Bundle found = verifiedBundle;
        if (found != null) return found;
        synchronized (StandardCryptoNamespace.class) {
            found = verifiedBundle;
            if (found == null) verifiedBundle = found = compileBundle();
        }
        return found;
    }

    private static Bundle compileBundle() {
        var all = new ArrayList<Ast.Decl>();
        var signatures = new HashMap<String, Signature>();
        for (String resource : RESOURCE_FILES) {
            String source = readBundledSource(resource);
            Ast.Program unit = Parser.parseStrictAtto(source);
            if (!REQUIRED_NAMESPACE.equals(unit.namespace())
                    || !unit.imports().isEmpty() || !unit.exports().isEmpty()
                    || unit.modules().size() != 1
                    || !Parser.ROOT_MODULE.equals(unit.modules().getFirst().name())) {
                throw new IllegalStateException(
                        "invalid trusted std.crypto namespace resource " + resource);
            }
            for (Ast.Decl decl : unit.modules().getFirst().declarations()) {
                if (!(decl instanceof Ast.FunctionDecl function)
                        || function.kind() != Ast.CallableKind.FNC
                        || function.visibility() != Ast.Visibility.PUBLIC
                        || function.async() || function.suspends() || function.generator()
                        || function.actorKind() != Ast.ActorKind.NONE
                        || function.trapped() || !function.genericParameters().isEmpty()
                        || !function.genericSpecs().isEmpty()
                        || !function.effects().isEmpty()
                        || HOST_MEMBERS.contains(function.name())) {
                    throw new IllegalStateException(
                            "non-policy declaration in trusted std.crypto resource " + resource);
                }
                var types = new ArrayList<Ast.TypeRef>();
                for (Ast.Param param : function.parameters()) {
                    if (param.structural() || param.mutable() || !isInteger(param.type())) {
                        throw new IllegalStateException(
                                "std.crypto source policy expects primitive int-only inputs: "
                                        + function.name());
                    }
                    types.add(param.type());
                }
                if (!isInteger(function.returnType()) && !isBoolean(function.returnType())) {
                    throw new IllegalStateException(
                            "std.crypto source policy requires int/bool result: " + function.name());
                }
                if (signatures.putIfAbsent(function.name(),
                        new Signature(types, function.returnType())) != null) {
                    throw new IllegalStateException(
                            "duplicate std.crypto policy function across source files: " + function.name());
                }
                all.add(function);
            }
        }
        if (signatures.isEmpty()) {
            throw new IllegalStateException("missing std.crypto source policy functions");
        }
        checkPurePolicyBodies(all, signatures);
        Ast.Program merged = new Ast.Program(
                REQUIRED_NAMESPACE,
                List.of(),
                List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE, all)),
                List.of());
        // Compile the merged units with the STRICT Atto profile, never the
        // permissive legacy default. Preserve source-task ownership proofs.
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithDiagnostics(merged, true);
        OwnershipChecker.check(
                checked.program(), checked.shortInferredAliases());
        return new Bundle(checked.program(), signatures);
    }

    /**
     * Host-independent policy must be a finite, side-effect-free call graph
     * of integer/boolean expressions. Prevent a privileged builtin or a
     * future source edit from slipping I/O, FFI, actor operations, allocation,
     * mutation, loops, or scheduling into this internally invoked code.
     */
    private static void checkPurePolicyBodies(
            List<Ast.Decl> declarations, Map<String, Signature> signatures) {
        var edges = new HashMap<String, java.util.Set<String>>();
        for (Ast.Decl declaration : declarations) {
            Ast.FunctionDecl function = (Ast.FunctionDecl) declaration;
            var names = new java.util.HashSet<String>();
            for (Ast.Param parameter : function.parameters()) {
                if (!names.add(parameter.name())) {
                    throw new IllegalStateException("duplicate crypto policy parameter");
                }
            }
            var calls = new java.util.HashSet<String>();
            for (Ast.Stmt statement : function.body()) {
                checkPureStatement(statement, names, calls, signatures, 0);
            }
            edges.put(function.name(), calls);
        }
        var completed = new java.util.HashSet<String>();
        var active = new java.util.HashSet<String>();
        for (String name : edges.keySet()) {
            verifyAcyclic(name, edges, active, completed);
        }
    }

    private static void verifyAcyclic(
            String member, Map<String, java.util.Set<String>> edges,
            java.util.Set<String> active, java.util.Set<String> completed) {
        if (completed.contains(member)) return;
        if (!active.add(member)) {
            throw new IllegalStateException("recursive std.crypto policy callable: " + member);
        }
        for (String dependency : edges.getOrDefault(member, java.util.Set.of())) {
            verifyAcyclic(dependency, edges, active, completed);
        }
        active.remove(member);
        completed.add(member);
    }

    private static void checkPureStatement(
            Ast.Stmt statement, java.util.Set<String> names,
            java.util.Set<String> calls, Map<String, Signature> signatures,
            int depth) {
        if (depth > 256) throw new IllegalStateException("std.crypto policy too deeply nested");
        if (statement instanceof Ast.ReturnStmt returned) {
            checkPureExpr(returned.value(), names, calls, signatures, depth + 1);
            return;
        }
        if (statement instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                checkPureExpr(branch.condition(), names, calls, signatures, depth + 1);
                for (Ast.Stmt child : branch.body()) {
                    checkPureStatement(child, names, calls, signatures, depth + 1);
                }
            }
            for (Ast.Stmt child : conditional.elseBody()) {
                checkPureStatement(child, names, calls, signatures, depth + 1);
            }
            return;
        }
        if (statement instanceof Ast.BlockStmt block) {
            for (Ast.Stmt child : block.body()) {
                checkPureStatement(child, names, calls, signatures, depth + 1);
            }
            return;
        }
        throw new IllegalStateException(
                "effectful or mutable statement in std.crypto source policy: "
                        + statement.getClass().getSimpleName());
    }

    private static void checkPureExpr(
            Ast.Expr expression, java.util.Set<String> names,
            java.util.Set<String> calls, Map<String, Signature> signatures,
            int depth) {
        if (depth > 256) throw new IllegalStateException("std.crypto policy expression too deep");
        if (expression instanceof Ast.LiteralExpr) return;
        if (expression instanceof Ast.NameExpr name) {
            if (!names.contains(name.name())) {
                throw new IllegalStateException(
                        "std.crypto policy attempted to read nonparameter " + name.name());
            }
            return;
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            checkPureExpr(unary.operand(), names, calls, signatures, depth + 1);
            return;
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            checkPureExpr(binary.left(), names, calls, signatures, depth + 1);
            checkPureExpr(binary.right(), names, calls, signatures, depth + 1);
            return;
        }
        if (expression instanceof Ast.CallExpr call
                && !call.typeArgumentsPresent()
                && call.callee() instanceof Ast.NameExpr target
                && signatures.containsKey(target.name())
                && call.arguments().size() == signatures.get(target.name()).arguments().size()) {
            calls.add(target.name());
            for (Ast.Expr arg : call.arguments()) {
                checkPureExpr(arg, names, calls, signatures, depth + 1);
            }
            return;
        }
        throw new IllegalStateException(
                "effectful or dynamic expression in std.crypto source policy: "
                        + expression.getClass().getSimpleName());
    }

    private static boolean isInteger(Ast.TypeRef type) {
        return type != null && "int".equals(type.name())
                && type.arguments().isEmpty() && !type.inferArguments();
    }

    private static boolean isBoolean(Ast.TypeRef type) {
        return type != null && "bool".equals(type.name())
                && type.arguments().isEmpty() && !type.inferArguments();
    }

    private static String readBundledSource(String resource) {
        try (InputStream input = StandardCryptoNamespace.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "std.crypto .atto source missing from classpath/Native Image: " + resource);
            }
            byte[] data = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (data.length > MAX_SOURCE_BYTES || input.read() != -1) {
                throw new IllegalStateException("oversized bundled std.crypto source: " + resource);
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot load bundled std.crypto source: " + resource, failure);
        }
    }
}
