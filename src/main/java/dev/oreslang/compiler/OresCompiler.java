package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;

/** Trusted compiler front-end API for build systems and isolate admission. */
public final class OresCompiler {
    private OresCompiler() { }

    /**
     * Opt-in first protected-state MIR slice. This executes the production
     * parser first, then admits only one verified i64 state-root region.
     * It does NOT bypass or relax ordinary TypeChecker/OwnershipChecker
     * admission for general compilation and does not claim native AOT.
     */
    public static ProtectedScalarMirV1.Plan compileProtectedScalarI64(String source) {
        return ProtectedScalarMirV1.lower(Parser.parse(source));
    }

    public static Ast.Program parseAndTypeCheck(String source) {
        return analyze(Parser.parse(source));
    }

    /** Runs the complete front-end admission policy on an already parsed program. */
    public static Ast.Program analyze(Ast.Program program) {
        TypeChecker.TypeCheckResult checked = TypeChecker.checkTypesWithDiagnostics(program);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return checked.program();
    }

    /** Imported module contracts participate in type proofs, not runtime declarations. */
    public static Ast.Program analyze(
            Ast.Program program, java.util.Map<String, Ast.InterfaceDecl> importedContracts) {
        TypeChecker.TypeCheckResult checked =
                TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        program, java.util.Map.of(), java.util.Map.of(), importedContracts);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return checked.program();
    }

    /** Compiler diagnostics are returned explicitly instead of written to shared stderr. */
    public record AnalysisResult(Ast.Program program, java.util.List<String> warnings) {
        public AnalysisResult { warnings = java.util.List.copyOf(warnings); }
    }

    public static AnalysisResult parseAndTypeCheckWithDiagnostics(String source) {
        return analyzeWithDiagnostics(Parser.parse(source));
    }

    public static AnalysisResult analyzeWithDiagnostics(Ast.Program program) {
        TypeChecker.TypeCheckResult checked = TypeChecker.checkTypesWithDiagnostics(program);
        OwnershipChecker.check(checked.program(), checked.shortInferredAliases());
        return new AnalysisResult(checked.program(), checked.warnings());
    }

    /**
     * Opt-in RT primitive subset: perform the full existing type/ownership check
     * before admitting any source-level resource bounds or lowering to Java 8.
     * This is not a verified no-heap runtime or WCET proof. Consumers must run
     * the independent exact-classfile audit and target qualification gates.
     */
    public static RealtimePrimitiveCompiler.Artifact compileRealtimePrimitive(
            String source, String entry, int arity,
            RealtimePrimitiveCompiler.Budget budget, java.nio.file.Path outputDirectory)
            throws java.io.IOException {
        Ast.Program checked = parseAndTypeCheck(source);
        return RealtimePrimitiveCompiler.compile(checked, entry, arity, budget, outputDirectory);
    }

    /**
     * Opt-in compiler-only AOT metadata closure for explicit, public,
     * non-actor, non-generic source classes and closed scalar public fields.
     *
     * Runs the regular parser, type checker and ownership checker first.
     * Returns immutable descriptor bytes, NOT an AttoLang guest value,
     * executable code, GraalVM image, or type_of<T>() intrinsic.
     *
     * Unsupported type shapes, private roots and missing symbols reject.
     */
    public static byte[] compileAotReflectionTableV1(
            String source, String packageIdentityClaim,
            java.util.List<String> explicitQualifiedRoots) {
        Ast.Program checked = parseAndTypeCheck(source);
        return AotReflectionSourceRootsV1.lowerChecked(
                checked, packageIdentityClaim, explicitQualifiedRoots).bytes();
    }

    /**
     * Immutable compiler-side result of an independently parsed, closed
     * type_of<Nominal>() query. No guest runtime lookup or invocation is exposed.
     */
    public record StaticTypeOfQueryV1(
            String qualifiedType, String typeIdHex, byte[] descriptorTable) {
        public StaticTypeOfQueryV1 {
            java.util.Objects.requireNonNull(qualifiedType, "qualified type");
            java.util.Objects.requireNonNull(typeIdHex, "type ID");
            descriptorTable = java.util.Objects.requireNonNull(
                    descriptorTable, "descriptor table").clone();
        }
        @Override public byte[] descriptorTable() { return descriptorTable.clone(); }
    }

    /**
     * Opt-in static type_of<Nominal>() compiler metadata bootstrap.
     *
     * This method checks real source declarations with the production Parser,
     * TypeChecker and OwnershipChecker. The standalone query is parsed as a
     * real generic CallExpr, then bound to a single public class from that
     * checked program through AotReflectionSourceRootsV1. Its name cannot be
     * shadowed by a user-declared same-name callable.
     *
     * The caller's package identity is a CLAIM, not authenticated provenance.
     * A separate build admission gate must bind it to a reviewed manifest,
     * exact source tree and compiler artifact before any parity promotion.
     *
     * THIS DOES NOT make type_of<T>() valid inside an AttoLang guest function,
     * lower guest bytecode, or prove native execution. Those are separate gates.
     */
    public static StaticTypeOfQueryV1 compileStaticTypeOfQueryV1(
            String sourceDeclarations, String packageIdentityClaim,
            String staticQuery) {
        Ast.Program checked = parseAndTypeCheck(sourceDeclarations);
        for (Ast.ModuleDecl module : checked.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function
                        && function.name().equals("type_of")) {
                    throw new IllegalArgumentException(
                            "static type_of intrinsic conflicts with a user-declared function");
                }
            }
        }
        Ast.TypeRef nominal = Parser.parseStaticTypeOfQueryV1(staticQuery);
        String qualified = "__root__." + nominal.name();
        byte[] encoded = AotReflectionSourceRootsV1.lowerChecked(
                checked, packageIdentityClaim, java.util.List.of(qualified)).bytes();
        AotReflectionTableV1Reader.Table table = AotReflectionTableV1Reader.decode(encoded);
        if (table.types().size() != 1 ||
                !table.types().getFirst().resolvedSymbol().equals(qualified)) {
            throw new IllegalStateException("static type_of descriptor identity mismatch");
        }
        return new StaticTypeOfQueryV1(
                qualified, table.types().getFirst().typeIdHex(), encoded);
    }

    /**
     * Parse and type-check the complete source first, then perform closed-world
     * build optimization. Type errors in code that later becomes unreachable
     * are still reported; tree shaking is an optimization, not conditional
     * compilation that hides invalid source.
     */
    public static TreeShaker.Result compileForBuild(String source, BuildOptions options) {
        return TreeShaker.shake(parseAndTypeCheck(source), options);
    }

    /**
     * Performs syntax, type, and language-capability admission without
     * executing guest code.
     */
    public static Ast.Program validateForIsolate(String source, IsolatePolicy policy) {
        Ast.Program program = parseAndTypeCheck(source);
        CapabilityChecker.check(program, policy);
        return program;
    }
}
