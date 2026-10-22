package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.AggregateConstructionPlan;
import dev.oreslang.types.OwnershipChecker;

import java.util.Map;

/**
 * JDK-only executable front-end smoke: real Parser -> TypeChecker ->
 * OwnershipChecker. No GraalVM, Maven, JUnit or guest interpreter required.
 *
 * This intentionally cannot establish native-image or interpreter parity.
 */
public final class RecordFrontendSmoke {
    private RecordFrontendSmoke() { }

    public static void main(String[] args) {
        // A mutable aggregate can change underneath a spread copy; until a
        // synchronized/borrowed snapshot proof lands, the ownership pass
        // must reject mutable source bindings even if fields are scalars.
        Ast.Program mutableSpread = Parser.parse("""
                fnc blocked_mutable_source(): int {
                    val source = infer struct{x: 1};
                    const copy = record{...source};
                    return copy.x;
                }
                """);
        denied(() -> OwnershipChecker.check(mutableSpread),
                "mutable aggregate source must not bypass snapshot lease admission");

        // A later type error cannot publish an earlier, fully parsed spread
        // candidate. Previously the checker recorded candidates as it went,
        // leaving an orphan proof after a failed compilation unit.
        Ast.Program partlyTyped = Parser.parse("""
                fnc earlier(): int {
                    const base = record{x: 1};
                    const copy = record{...base, y: 2};
                    return copy.x;
                }
                fnc later(): int {
                    return "wrong return type";
                }
                """);
        Ast.ObjectExpr orphan = aggregateBinding(partlyTyped, "copy");
        denied(() -> TypeChecker.checkTypesWithDiagnostics(partlyTyped),
                "later type error must fail the full compilation unit");
        // Ownership intentionally does not prove return type assignability;
        // however an independent check must reject the missing exact-program
        // type proof rather than convert an orphan into executable authority.
        deniedState(() -> OwnershipChecker.check(partlyTyped),
                "ownership-only pass must reject a type-invalid program");
        deniedState(() -> AggregateConstructionPlan.Checked.requireAdmitted(partlyTyped, orphan),
                "standalone ownership cannot promote an orphan type candidate");

        // Type checking alone cannot authorize interpreter spread execution.
        // A structurally equal parse never inherits another AST's approval.
        String sourceWithSpread = """
                fnc prove(): int {
                    const base = record{x: 1};
                    const copy = record{...base, y: 2};
                    return copy.x;
                }
                """;
        Ast.Program firstParse = Parser.parse(sourceWithSpread);
        TypeChecker.TypeCheckResult typedSpread =
                TypeChecker.checkTypesWithDiagnostics(firstParse);
        Ast.ObjectExpr exactSpread = aggregateBinding(firstParse, "copy");
        deniedState(() -> AggregateConstructionPlan.Checked.requireAdmitted(typedSpread.program(), exactSpread),
                "type-only aggregate is not executable");
        OwnershipChecker.check(typedSpread.program(), typedSpread.shortInferredAliases());
        check(AggregateConstructionPlan.Checked.requireAdmitted(typedSpread.program(), exactSpread)
                        .resolvedFields().containsKey("y"),
                "whole-program ownership approval admits exact original AST");
        Ast.Program secondParse = Parser.parse(sourceWithSpread);
        Ast.ObjectExpr equalButForeign = aggregateBinding(secondParse, "copy");
        check(exactSpread != equalButForeign && exactSpread.equals(equalButForeign),
                "equal-shaped AST identity regression setup");
        deniedState(() -> AggregateConstructionPlan.Checked.requireAdmitted(secondParse, equalButForeign),
                "structurally identical reparse may not inherit approval");
        // A different compilation-unit wrapper can reuse the SAME inner AST
        // object. Equality and object identity of the spread are insufficient:
        // executable authority must also require an exact-program proof.
        Ast.Program graftedProgram = new Ast.Program(
                typedSpread.program().namespace(),
                typedSpread.program().imports(),
                typedSpread.program().modules());
        check(graftedProgram != typedSpread.program()
                        && aggregateBinding(graftedProgram, "copy") == exactSpread,
                "cross-unit AST graft regression setup");
        deniedState(() -> AggregateConstructionPlan.Checked.requireAdmitted(
                        graftedProgram, exactSpread),
                "approved spread AST must not carry authority to another program");
        deniedState(() -> OwnershipChecker.check(graftedProgram),
                "independent ownership pass cannot import the original program's type proof");
        TypeChecker.TypeCheckResult independentlyChecked =
                TypeChecker.checkTypesWithDiagnostics(graftedProgram);
        Ast.ObjectExpr independentlyTypedSpread =
                aggregateBinding(independentlyChecked.program(), "copy");
        OwnershipChecker.check(independentlyChecked.program(),
                independentlyChecked.shortInferredAliases());
        check(AggregateConstructionPlan.Checked.requireAdmitted(
                        independentlyChecked.program(), independentlyTypedSpread)
                        .resolvedFields().containsKey("y"),
                "new compilation unit succeeds only after its own full two-pass checks");


        // An ownership failure in a separate callable must prevent approval
        // of earlier successfully checked aggregates in the same program.
        String rejectedUnit = """
                fnc good(): int {
                    const base = record{x: 1};
                    const copy = record{...base};
                    return copy.x;
                }
                fnc bad(): int {
                    val mutable = infer struct{x: 1};
                    const unsafe = record{...mutable};
                    return unsafe.x;
                }
                """;
        Ast.Program badProgram = Parser.parse(rejectedUnit);
        TypeChecker.TypeCheckResult badTyped =
                TypeChecker.checkTypesWithDiagnostics(badProgram);
        Ast.ObjectExpr premature = aggregateBinding(badProgram, "copy");
        denied(() -> OwnershipChecker.check(
                        badTyped.program(), badTyped.shortInferredAliases()),
                "mutable source rejects the complete ownership pass");
        deniedState(() -> AggregateConstructionPlan.Checked.requireAdmitted(badTyped.program(), premature),
                "failed whole-unit ownership cannot leave prior spread admitted");

        // Closed scalar spreads now pass the actual front-end chain.
        // Non-scalar or unowned sources still fail independently at the type
        // and ownership gates. Guest execution is a separate release gate.
        acceptedThroughFullFrontend("""
                fnc copy_scalars(): int {
                    const base = infer struct{x: 1};
                    const next = infer struct{...base, y: 2};
                    const row = record{...next, x: 42};
                    return row.x;
                }
                """);
        acceptedThroughFullFrontend("""
                fnc retype_rightmost(): string {
                    const first = record{x: 1, y: true};
                    const second = record{x: "two"};
                    const result = record{...first, ...second, x: "last"};
                    return result.x;
                }
                """);
        acceptedThroughFullFrontend("""
                fnc plain_struct_to_readonly_record(): int {
                    const base = infer struct{x: 1};
                    const copy = record{...base, y: true};
                    return copy.x;
                }
                """);
        Ast.Program incorrectlyNarrowed = Parser.parse("""
                fnc wrong_return(): int {
                    const base = record{x: 1};
                    const copy = record{...base, x: "changed"};
                    return copy.x;
                }
                """);
        denied(() -> TypeChecker.check(incorrectlyNarrowed),
                "type-changing override cannot retain previous scalar type");
        Ast.Program nestedSpread = Parser.parse("""
                fnc blocked(): int {
                    const base = record{nested: record{x: 1}};
                    const next = record{...base, y: 2};
                    return next.y;
                }
                """);
        denied(() -> TypeChecker.check(nestedSpread),
                "non-scalar spread shape must fail closed");
        denied(() -> OwnershipChecker.check(nestedSpread),
                "non-scalar spread ownership must fail closed");
        accepted("""
                fnc read(): string {
                    const Person first = infer record{name: "Ada", age: 37};
                    const Person second = infer record{age: 42, name: "Grace"};
                    return second.name;
                }
                """);
        accepted("""
                fnc read(): string {
                    const Contact contact = infer<Contact> record{
                        "first name": "Linus", "work@email": "a@b"
                    };
                    const Contact other = infer record{
                        "work@email": "c@d", "first name": "Grace"
                    };
                    return other["first name"];
                }
                """);
        accepted("""
                type Person = {name: string};
                fnc read(): string {
                    const Person person = infer record{name: "Ada"};
                    return person.name;
                }
                """);
        accepted("""
                fnc read(): int {
                    const Pair first = infer<Pair> tuple("Ada", 37);
                    const Pair other = tuple("Grace", 42);
                    return other[1];
                }
                """);
        String identicalInSiblings = """
                fnc first(): string {
                    const Profile profile = infer record{name: "Ada"};
                    return profile.name;
                }
                fnc second(): string {
                    const Profile profile = infer record{name: "Ada"};
                    return profile.name;
                }
                """;
        TypeChecker.TypeCheckResult result =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse(identicalInSiblings));
        check(result.shortInferredAliases().size() == 2,
                "distinct AST declarations must have distinct alias proofs");
        denied(() -> result.shortInferredAliases().clear(),
                "type-checker alias proof view must not be writable");
        accepted(identicalInSiblings);

        String existingType = """
                type Person = {name: string};
                fnc read(): string {
                    const Person p = infer record{name: "Ada"};
                    return p.name;
                }
                """;
        check(TypeChecker.checkTypesWithDiagnostics(Parser.parse(existingType))
                        .shortInferredAliases().isEmpty(),
                "preexisting type is never implicitly redeclared");

        // Match the production linked-public-class checker handoff without
        // pulling in Maven, GraalVM, or the incremental build runtime.
        Ast.Program exportingUnit = Parser.parse("""
                pub define class ImportedOptions as
                    pub val int max_input_bytes;
                end
                """);
        Ast.ClassDecl exported = exportingUnit.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast).findFirst().orElseThrow();
        Map<String, Ast.ClassDecl> linked = Map.of("ImportedOptions", exported);
        Ast.Program consumerUnit = Parser.parse("""
                import class ImportedOptions from './options';
                fnc read(): string {
                    const LocalRow first = infer struct{name: "Ada"};
                    const LocalRow second = infer struct{name: "Grace"};
                    return second.name;
                }
                """);
        TypeChecker.TypeCheckResult linkedAnalysis =
                TypeChecker.checkTypesWithImportedClassesWithDiagnostics(
                        consumerUnit, linked, Map.of());
        check(linkedAnalysis.shortInferredAliases().size() == 1,
                "linked checker must retain short inference proof");
        OwnershipChecker.checkWithImportedClasses(
                linkedAnalysis.program(), linked, linkedAnalysis.shortInferredAliases());

        // Imported symbols are not allowed to become new inferred aliases.
        Ast.Program shadowingImport = Parser.parse("""
                import class ImportedOptions from './options';
                fnc bad(): void {
                    const ImportedOptions option = infer record{name: "Ada"};
                    return;
                }
                """);
        denied(() -> TypeChecker.checkTypesWithImportedClassesWithDiagnostics(
                        shadowingImport, linked, Map.of()),
                "imported class name must not become a short inferred alias");

        // infer chooses struct type origin; binding capability controls mutation.
        accepted("""
                fnc update(): float {
                    const Point seed = infer<Point> struct{x: 3.0, y: 4.0};
                    val Point mutable = infer struct{x: 5.0, y: 6.0};
                    mutable.x = 9.0;
                    return mutable.x;
                }
                """);
        denied(() -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    const Row row = infer<Row> record{x: 1, y: 2};
                    const Point point = infer<Point> struct{x: 1, y: 2};
                    const Row erased = point;
                    return;
                }
                """)), "record/struct type boundary must not erase");
        denied(() -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    const Point seed = infer<Point> struct{x: 1, y: 2};
                    const Point wider = infer struct{x: 1, y: 2, z: 3};
                    return;
                }
                """)), "concrete struct shapes require exact fields");

        // Dynamic fixed-product access: exact singleton keys keep the selected
        // field/slot type; truly dynamic keys use a safe union.
        accepted("""
                fnc read_record(): string {
                    const row = record{x: 3.0, y: "boo"};
                    const key = "y";
                    return row[key];
                }
                fnc read_tuple(int index): float {
                    const pair = tuple(3.0, 4.0);
                    return pair[index];
                }
                fnc update_struct(string key): float {
                    const Point seed = infer<Point> struct{x: 1.0, y: 2.0};
                    val Point point = infer struct{x: 3.0, y: 4.0};
                    point[key] = 9.0;
                    return point[key];
                }
                """);
        denied(() -> TypeChecker.check(Parser.parse("""
                fnc bad(string key): void {
                    const row = record{x: 3.0, y: "boo"};
                    const float value = row[key];
                    return;
                }
                """)), "heterogeneous dynamic record lookup must retain its union");
        denied(() -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    val pair = tuple(1, 2);
                    pair[0] = 3;
                    return;
                }
                """)), "tuple values remain immutable positional products");

        // Failure gates must reach ownership analysis as well as type checking.
        denied(() -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    const Person first = infer record{name: "Ada"};
                    const Person other = first;
                    other.name = "mutated";
                    return;
                }
                """)), "readonly cannot be escalated");
        denied(() -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    const Person first = infer record{name: "Ada", age: 37};
                    const Person wrong = infer record{name: "Grace"};
                    return;
                }
                """)), "required field mismatch");
        denied(() -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    const Person before = infer record{name: "before"};
                    const Person after = infer<Person> record{name: "after"};
                    return;
                }
                """)), "forward explicit alias collision");
        denied(() -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                    const Profile first = infer record{name: "Ada"};
                    val Profile second = first;
                    return;
                }
                """)), "readonly alias into mutable binding");

        // An attacker-constructed declaration must not use the alias-proof
        // namespace for an arbitrary type. Verify that the emitted proof
        // identities point at checked AST declarations, not reconstructed keys.
        Ast.Program checked = result.program();
        for (Map.Entry<Ast.BindingStmt, String> proof
                : result.shortInferredAliases().entrySet()) {
            check("Profile".equals(proof.getValue()), "unexpected alias type");
            boolean found = checked.modules().stream()
                    .flatMap(module -> module.declarations().stream())
                    .filter(Ast.FunctionDecl.class::isInstance)
                    .map(Ast.FunctionDecl.class::cast)
                    .flatMap(fn -> fn.body().stream())
                    .anyMatch(stmt -> stmt == proof.getKey());
            check(found, "proof must refer to an actual checked AST binding");
        }
        System.out.println("RECORD_FRONTEND_SMOKE_PASS");
    }

    private static void accepted(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    /** Scalar spread admission must run the production type + ownership handoff. */
    private static void acceptedThroughFullFrontend(String source) {
        TypeChecker.TypeCheckResult typed =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse(source));
        OwnershipChecker.check(typed.program(), typed.shortInferredAliases());
    }

    private static Ast.ObjectExpr aggregateBinding(Ast.Program program, String bindingName) {
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .flatMap(fn -> fn.body().stream())
                .filter(Ast.BindingStmt.class::isInstance)
                .map(Ast.BindingStmt.class::cast)
                .filter(binding -> binding.name().equals(bindingName))
                .map(Ast.BindingStmt::initializer)
                .filter(Ast.ObjectExpr.class::isInstance)
                .map(Ast.ObjectExpr.class::cast)
                .findFirst().orElseThrow();
    }

    private static void deniedState(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("unexpected authorization: " + label);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void denied(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("unexpectedly accepted: " + label);
    }
}
