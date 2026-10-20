package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.V3DefaultArgValsPolicy;
import dev.oreslang.types.V3ClosedOptionCallLowering;
import dev.oreslang.types.V3ScalarDefaultLiteralLowering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in, deliberately MINIMAL executable scalar-default source normalizer.
 *
 * Only one unimported module, direct non-generic synchronous fnc declarations
 * and one-return-statement pure bodies are admitted. Supplied arguments must
 * be primitive scalar literal AST nodes. Both per-formal @Default and
 * @DefaultArgVals are compiled into full-arity positional literals before
 * the real TypeChecker, OwnershipChecker and interpreter see the AST.
 *
 * A closed-world Option<primitive> subset is admitted using checked
 * synthesized Some/None intrinsics. No user effects, constructors, user
 * methods, actors, closures, imports, overloads, calls inside supplied
 * argument expressions or variable scopes can cross this boundary.
 * This does NOT enable the general v3 syntax or reinterpret legacy modules.
 */
public final class V3ScalarDefaultProgramNormalizer {
    private V3ScalarDefaultProgramNormalizer() { }

    public static Ast.Program parseNormalizeAndAnalyze(String source) {
        Objects.requireNonNull(source, "source");
        Ast.Program parsed = Parser.parseStagedV3(source);
        Ast.Program normalized = normalize(parsed);
        // Re-run the authoritative compiler admission on the *entire*
        // normalized program, never on a fragment or unchecked preview.
        return OresCompiler.analyze(normalized);
    }

    public static Ast.Program normalize(Ast.Program original) {
        Objects.requireNonNull(original, "original program");
        if (!original.imports().isEmpty() || original.modules().size() != 1
                || original.namespace() != null) {
            throw new IllegalArgumentException(
                    "scalar-default mode requires exactly one unimported, unnamespaced module");
        }
        Ast.ModuleDecl module = original.modules().getFirst();
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            throw new IllegalArgumentException(
                    "scalar-default mode does not admit module annotations or contracts");
        }
        if (module.declarations().size() > 128) {
            throw new IllegalArgumentException("scalar-default mode declaration budget exceeded");
        }
        Map<String, Ast.FunctionDecl> byName = new HashMap<>();
        for (Ast.Decl declaration : module.declarations()) {
            if (!(declaration instanceof Ast.FunctionDecl fn)
                    || fn.kind() != Ast.CallableKind.FNC || fn.async()
                    || fn.suspends() || fn.generator() || fn.trapped()
                    || fn.nonLexical() || fn.structural()
                    || fn.actorKind() != Ast.ActorKind.NONE
                    || !fn.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "scalar-default mode allows only plain synchronous non-generic functions");
            }
            if (fn.name().equals("Some") || fn.name().equals("None")
                    || fn.name().equals("Option")) {
                throw new IllegalArgumentException(
                        "closed Option runtime intrinsics cannot be shadowed");
            }
            if (byName.putIfAbsent(fn.name(), fn) != null) {
                throw new IllegalArgumentException(
                        "scalar-default mode cannot resolve overloaded function '" + fn.name() + "'");
            }
            for (Ast.Annotation annotation : fn.annotations()) {
                if (!annotation.name().equals("DefaultArgVals")) {
                    throw new IllegalArgumentException(
                            "scalar-default mode cannot erase unknown callable annotations");
                }
            }
            V3DefaultArgValsPolicy.validateDeclaration(fn.parameters(), fn.annotations());
            if (fn.body().size() != 1
                    || !(fn.body().getFirst() instanceof Ast.ReturnStmt)) {
                throw new IllegalArgumentException(
                        "scalar-default mode requires exactly one pure return statement");
            }
            if (!scalarOrVoid(fn.returnType())) {
                throw new IllegalArgumentException("scalar-default mode does not support complex return types");
            }
            for (Ast.Param param : fn.parameters()) {
                if (!scalarOrSingleOption(param.type()) || param.mutable()
                        || param.structural() || param.optional()
                        || param.name().equals("Some") || param.name().equals("None")
                        || param.name().equals("Option")) {
                    throw new IllegalArgumentException(
                            "closed-default mode requires immutable scalar/Option<scalar> formals");
                }
            }
        }
        List<Ast.Decl> normalized = new ArrayList<>(module.declarations().size());
        for (Ast.Decl declaration : module.declarations()) {
            Ast.FunctionDecl fn = (Ast.FunctionDecl) declaration;
            Map<String, Ast.TypeRef> parameters = new HashMap<>();
            List<Ast.Param> fullArity = new ArrayList<>(fn.parameters().size());
            for (Ast.Param parameter : fn.parameters()) {
                if (parameters.putIfAbsent(
                        parameter.name(), parameter.type().stagedV3PreviewType()) != null) {
                    throw new IllegalArgumentException("duplicate scalar-default formal name");
                }
                fullArity.add(new Ast.Param(
                        parameter.type().stagedV3PreviewType(), parameter.name()));
            }
            Ast.ReturnStmt stmt = (Ast.ReturnStmt) fn.body().getFirst();
            Ast.Expr rewritten = rewrite(stmt.value(), byName, parameters, 0);
            normalized.add(new Ast.FunctionDecl(
                    fn.name(), fn.kind(), fn.visibility(), fn.async(), fn.suspends(),
                    fn.generator(), fn.structural(), fn.nonLexical(), fn.trapped(),
                    fn.actorKind(), fn.genericParameters(), fullArity,
                    fn.returnType(), List.of(), List.of(new Ast.ReturnStmt(rewritten)),
                    fn.genericSpecs()));
        }
        return new Ast.Program(List.of(new Ast.ModuleDecl(module.name(), normalized)));
    }

    private static boolean scalar(Ast.TypeRef type) {
        return type != null && type.arguments().isEmpty() && !type.inferArguments()
                && !type.containsStagedV3Option() && !type.isBorrow()
                && (type.name().equals("int") || type.name().equals("bool")
                    || type.name().equals("string"));
    }

    private static boolean scalarOrSingleOption(Ast.TypeRef type) {
        if (scalar(type)) return true;
        // Staged T? sugar is converted to an explicit Option<T> type
        // ONLY inside this local, closed, independently re-checked scope.
        Ast.TypeRef checked = type.stagedV3PreviewType();
        return checked.name().equals("Option")
                && checked.arguments().size() == 1
                && !checked.inferArguments()
                && !checked.isBorrow()
                && scalar(checked.arguments().getFirst());
    }

    private static boolean scalarOrVoid(Ast.TypeRef type) {
        return scalar(type) || (type != null && type.name().equals("void")
                && type.arguments().isEmpty() && !type.inferArguments());
    }

    private static Ast.Expr rewrite(
            Ast.Expr expr, Map<String, Ast.FunctionDecl> functions,
            Map<String, Ast.TypeRef> localParameters, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("scalar-default body expression depth budget exceeded");
        }
        if (expr == null) return null; // only valid for void return
        if (expr instanceof Ast.LiteralExpr literal) {
            if (!(literal.value() instanceof Long
                    || literal.value() instanceof Boolean
                    || literal.value() instanceof String)) {
                throw new IllegalArgumentException("scalar-default mode requires immutable scalar literals");
            }
            return literal;
        }
        if (expr instanceof Ast.NameExpr name) {
            if (!localParameters.containsKey(name.name())) {
                throw new IllegalArgumentException(
                        "scalar-default mode cannot read globals or capture '" + name.name() + "'");
            }
            return name;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return new Ast.BinaryExpr(
                    binary.operator(),
                    rewrite(binary.left(), functions, localParameters, depth + 1),
                    rewrite(binary.right(), functions, localParameters, depth + 1));
        }
        if (expr instanceof Ast.CallExpr intrinsics
                && !intrinsics.typeArgumentsPresent()
                && intrinsics.arguments().isEmpty()
                && intrinsics.callee() instanceof Ast.MemberExpr method
                && method.receiver() instanceof Ast.NameExpr receiver
                && localParameters.containsKey(receiver.name())
                && scalarOrSingleOption(localParameters.get(receiver.name()))
                && localParameters.get(receiver.name())
                        .stagedV3PreviewType().name().equals("Option")
                && (method.member().equals("unwrap")
                    || method.member().equals("is_some")
                    || method.member().equals("is_none"))) {
            // The only admitted method call is a zero-arg intrinsic on
            // an immutable, locally declared Option<primitive> formal.
            return intrinsics;
        }
        if (expr instanceof Ast.CallExpr ordinary
                && ordinary.callee() instanceof Ast.NameExpr direct
                && !ordinary.typeArgumentsPresent()
                && !localParameters.containsKey(direct.name())
                && functions.containsKey(direct.name())) {
            // Ordinary positional calls with zero or more closed literals
            // also need omission lowering, especially f() for optional
            // Option<T> formals. Never convert custom callees or effects.
            Ast.FunctionDecl selected = functions.get(direct.name());
            List<Ast.CallArgument> items = new ArrayList<>();
            for (Ast.Expr arg : ordinary.arguments()) {
                if (!(arg instanceof Ast.LiteralExpr)) {
                    throw new IllegalArgumentException(
                            "closed-default positional calls require literal supplied arguments");
                }
                if (items.size() >= selected.parameters().size()) {
                    throw new IllegalArgumentException(
                            "too many supplied positional literals for closed-default callable");
                }
                // A staged argument list requires an actual label or omit.
                // Map ordinary positional sources to their exact formal name
                // only after the closed-world declaration is proven unique.
                items.add(Ast.CallArgument.named(
                        selected.parameters().get(items.size()).name(), arg));
            }
            if (items.isEmpty()) {
                if (selected.parameters().isEmpty()) return ordinary;
                for (Ast.Param p : selected.parameters()) items.add(Ast.CallArgument.omit(p.name()));
            }
            return lowerClosedCall(
                    new Ast.StagedCallExpr(ordinary.callee(), List.of(), false, items),
                    selected);
        }
        if (expr instanceof Ast.StagedCallExpr call) {
            if (!(call.callee() instanceof Ast.NameExpr callee)
                    || localParameters.containsKey(callee.name())) {
                throw new IllegalArgumentException(
                        "closed-default call cannot use a shadowed or indirect callee");
            }
            Ast.FunctionDecl selected = functions.get(callee.name());
            if (selected == null) {
                throw new IllegalArgumentException(
                        "closed-default call target is not a local declaration");
            }
            return lowerClosedCall(call, selected);
        }
        throw new IllegalArgumentException(
                "scalar-default mode has no effect/ownership proof for expression kind "
                        + expr.getClass().getSimpleName());
    }
    private static Ast.CallExpr lowerClosedCall(
            Ast.StagedCallExpr call, Ast.FunctionDecl selected) {
        List<V3DefaultArgValsPolicy.FormalPreview> formals =
                V3DefaultArgValsPolicy.validateDeclaration(
                        selected.parameters(), selected.annotations());
        boolean usesOption = formals.stream().anyMatch(
                V3DefaultArgValsPolicy.FormalPreview::optionTyped);
        return usesOption
                ? V3ClosedOptionCallLowering.lower(selected, call)
                : V3ScalarDefaultLiteralLowering.lower(selected, call).fullArityCall();
    }
}
