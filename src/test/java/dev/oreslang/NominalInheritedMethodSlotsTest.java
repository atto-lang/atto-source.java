package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.NominalInheritedMethodSlots;
import dev.oreslang.types.NominalMethodSlots.Domain;
import dev.oreslang.types.NominalMethodSlots.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class NominalInheritedMethodSlotsTest {
    private static final String ENUMS = """
            define enum Color as
                Red
                Blue
            end
            define enum Mode as
                Fast
                Slow
            end
            """;

    private static Map<Slot, NominalInheritedMethodSlots.Target> prove(
            String classes, String root, Domain domain, String method, int arity) {
        Ast.Program program = Parser.parse(ENUMS + classes);
        return NominalInheritedMethodSlots.prove(program, root, domain, method, arity);
    }

    private static IllegalArgumentException rejectProof(
            String classes, String root, Domain domain, String method, int arity) {
        // Parse OUTSIDE assertThrows: malformed fixtures must fail the test,
        // not masquerade as successful negative compiler regression coverage.
        Ast.Program parsed = Parser.parse(ENUMS + classes);
        return assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.prove(
                        parsed, root, domain, method, arity));
    }

    @Test
    void inheritedDistinctEnumMethodPreservesBothNominalSlots() {
        var slots = prove("""
                define class Base as
                    pub pick(Color x): int { return 10; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 20; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1);
        assertEquals(2, slots.size());
        assertEquals(2, slots.values().stream().map(t -> t.owner().name()).distinct().count());
        assertEquals(2, slots.keySet().stream()
                .map(s -> s.linkSymbol("root.Child")).distinct().count());
    }

    @Test
    void overridingOneEnumSlotCannotShadowOtherOverloads() {
        var slots = prove("""
                define class Base as
                    pub pick(Color x): int { return 10; }
                    pub pick(Mode x): int { return 20; }
                end
                define class Child extends Base as
                    pub pick(Color x): int { return 30; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1);
        assertEquals(2, slots.size());
        assertEquals("Child", slots.entrySet().stream()
                .filter(e -> e.getKey().positions().getFirst().id().endsWith("::Color"))
                .findFirst().orElseThrow().getValue().owner().name());
        assertEquals("Base", slots.entrySet().stream()
                .filter(e -> e.getKey().positions().getFirst().id().endsWith("::Mode"))
                .findFirst().orElseThrow().getValue().owner().name());
        assertThrows(UnsupportedOperationException.class, slots::clear);
    }

    @Test
    void staticAndInstanceFamiliesAreIndependent() {
        String source = """
                define class Base as
                    pub pick(Color x): int { return 10; }
                    pub static fnc pick(Color x): int { return 11; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 20; }
                    pub static fnc pick(Mode x): int { return 21; }
                end
                """;
        var instance = prove(source, "Child", Domain.INSTANCE, "pick", 1);
        var statics = prove(source, "Child", Domain.STATIC, "pick", 1);
        assertEquals(2, instance.size());
        assertEquals(2, statics.size());
        assertNotEquals(instance.keySet(), statics.keySet());
    }

    @Test
    void incompatibleReturnEffectVisibilityAndAbstractOverridesFailClosed() {
        for (String replacement : List.of(
                "pub pick(Color x): string { return \"bad\"; }",
                "private pick(Color x): int { return 2; }",
                "pub async pick(Color x): int { return 2; }",
                "pub abstract pick(Color x): int;")) {
            String source = """
                    define abstract class Base as
                        pub pick(Color x): int { return 1; }
                    end
                    define abstract class Child extends Base as
                    """ + replacement + "\nend\n";
            assertTrue(rejectProof(source, "Child", Domain.INSTANCE, "pick", 1)
                    .getMessage().contains("override"));
        }
    }

    @Test
    void privateAncestorCannotLeakIntoInheritedNominalVtable() {
        String classes = """
                define class Base as
                    private pick(Color x): int { return 1; }
                end
                define class Child extends Base as
                end
                """;
        IllegalArgumentException denied = rejectProof(
                classes, "Child", Domain.INSTANCE, "pick", 1);
        assertTrue(denied.getMessage().contains("private ancestor method"),
                denied.getMessage());
    }

    @Test
    void diamondRequiresExplicitCompatibleOverrideForSameSlot() {
        String parents = """
                define class Left as
                    pub pick(Color x): int { return 1; }
                end
                define class Right as
                    pub pick(Color x): int { return 2; }
                end
                """;
        assertTrue(rejectProof(parents + """
                        define class Child extends Left, Right as
                        end
                        """, "Child", Domain.INSTANCE, "pick", 1)
                .getMessage().contains("ambiguous inherited nominal diamond"));
        var resolved = prove(parents + """
                define class Child extends Left, Right as
                    pub pick(Color x): int { return 3; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1);
        assertEquals(1, resolved.size());
        assertEquals("Child", resolved.values().iterator().next().owner().name());
    }

    @Test
    void trueDiamondOfSameDeclarationIsNotAmbiguous() {
        var resolved = prove("""
                define class Base as
                    pub pick(Color x): int { return 1; }
                end
                define class Left extends Base as
                end
                define class Right extends Base as
                end
                define class Diamond extends Left, Right as
                end
                """, "Diamond", Domain.INSTANCE, "pick", 1);
        assertEquals(1, resolved.size());
        assertEquals("Base", resolved.values().iterator().next().owner().name());
    }

    @Test
    void inheritedPrimitiveOrUnknownCannotBecomeImplicitDynamicDispatch() {
        assertTrue(rejectProof("""
                define class Base as
                    pub pick(int x): int { return 1; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 2; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1)
                .getMessage().contains("not pairwise distinct"));
        assertTrue(rejectProof("""
                define class Base as
                    pub pick(Color x): int { return 1; }
                end
                define class Child extends Base as
                    pub pick(Any x): int { return 2; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1)
                .getMessage().contains("not an eligible primitive"));
    }

    @Test
    void reusedClassAstCannotClaimTwoModuleOwnerIdentities() {
        Ast.Program parsed = Parser.parse(ENUMS + """
                define class Picker as
                    pub pick(Color x): int { return 1; }
                end
                """);
        Ast.ClassDecl shared = parsed.modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast).findFirst().orElseThrow();
        Ast.Program forged = new Ast.Program(List.of(
                new Ast.ModuleDecl("alpha", List.of(shared)),
                new Ast.ModuleDecl("beta", List.of(shared))));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.prove(
                        forged, "alpha.Picker", Domain.INSTANCE, "pick", 1));
        assertTrue(denied.getMessage().contains("reused under distinct module identities"),
                denied.getMessage());
    }

    @Test
    void unresolvedParentsAndGenericOwnersDoNotInventNominalIdentities() {
        assertTrue(rejectProof("""
                define class Child extends Missing as
                    pub pick(Color x): int { return 1; }
                end
                """, "Child", Domain.INSTANCE, "pick", 1)
                .getMessage().contains("cannot import or resolve class"));
        assertTrue(rejectProof("""
                define class Base<T> as
                    pub pick(Color x): int { return 1; }
                end
                define class Child extends Base<int> as
                end
                """, "Child", Domain.INSTANCE, "pick", 1)
                .getMessage().contains("unsubstituted generic parent"));
    }

    @Test
    void syntheticAnnotationChangesCannotBypassInheritedEffectContract() {
        Ast.Program parsed = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color x): int { return 1; }
                end
                define class Child extends Base as
                    pub pick(Color x): int { return 2; }
                end
                """);
        Ast.ModuleDecl root = parsed.modules().getFirst();
        java.util.ArrayList<Ast.Decl> changed = new java.util.ArrayList<>();
        for (Ast.Decl declaration : root.declarations()) {
            if (!(declaration instanceof Ast.ClassDecl cls)
                    || !cls.name().equals("Child")) {
                changed.add(declaration);
                continue;
            }
            Ast.MethodDecl old = cls.methods().getFirst();
            Ast.MethodDecl annotated = new Ast.MethodDecl(
                    old.name(), old.visibility(), old.isStatic(), old.isAbstract(),
                    old.async(), old.suspends(), old.structural(),
                    old.explicitReceiverType(), old.genericParameters(),
                    old.parameters(), old.returnType(),
                    List.of(new Ast.Annotation("requires_capability",
                            List.of(Ast.TypeRef.simple("Network")))),
                    old.body());
            changed.add(new Ast.ClassDecl(
                    cls.name(), cls.visibility(), cls.isAbstract(), cls.actorKind(),
                    cls.genericParameters(), cls.parents(), cls.interfaces(),
                    cls.fields(), cls.constructor(), List.of(annotated)));
        }
        Ast.Program mutated = new Ast.Program(List.of(
                new Ast.ModuleDecl(root.name(), root.annotations(),
                        root.contracts(), changed)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.prove(
                        mutated, "Child", Domain.INSTANCE, "pick", 1));
        assertTrue(error.getMessage().contains("override contract"), error.getMessage());
    }

    @Test
    void proofDoesNotEnableUnsafeExecutableMethodDispatch() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color x): int { return 1; }
                    pub pick(Mode x): int { return 2; }
                end
                """);
        assertEquals(2, NominalInheritedMethodSlots.prove(
                program, "Base", Domain.INSTANCE, "pick", 1).size());
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(program));
    }

    @Test
    void staticProofSelectsExactInheritedDeclarationWithoutRuntimeTypeRanking() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color x): int { return 11; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 22; }
                end
                """);
        Map<String, String> enumIds =
                dev.oreslang.types.NominalEnumOverloads.eligibleEnumSymbols(program);
        var color = NominalInheritedMethodSlots.selectStatic(
                program, "Child", Domain.INSTANCE, "pick", 1,
                Map.of(0, enumIds.get("Color")));
        var mode = NominalInheritedMethodSlots.selectStatic(
                program, "Child", Domain.INSTANCE, "pick", 1,
                Map.of(0, enumIds.get("Mode")));
        assertEquals("Base", color.owner().name());
        assertEquals("Child", mode.owner().name());
        assertThrows(IllegalArgumentException.class, () ->
                NominalInheritedMethodSlots.selectStatic(
                        program, "Child", Domain.INSTANCE, "pick", 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                NominalInheritedMethodSlots.selectStatic(
                        program, "Child", Domain.INSTANCE, "pick", 1,
                        Map.of(0, "forged::Unknown")));
        assertThrows(IllegalArgumentException.class, () ->
                NominalInheritedMethodSlots.selectStatic(
                        program, "Child", Domain.INSTANCE, "pick", 1,
                        Map.of(0, enumIds.get("Color"), 1, enumIds.get("Mode"))));
    }

    @Test
    void interfaceBearingOwnersAreNotMisreportedAsFullyProved() {
        String source = """
                pub interface Lookup {
                    fnc pick(Color value) => int;
                }
                define class Base implements Lookup as
                    pub pick(Color x): int { return 1; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 2; }
                end
                """;
        IllegalArgumentException rejected = rejectProof(
                source, "Child", Domain.INSTANCE, "pick", 1);
        assertTrue(rejected.getMessage().contains("interface contracts"),
                rejected::getMessage);
    }

    @Test
    void inheritedFieldAndMethodNameConflictFailsBeforeSlotPublication() {
        String source = """
                define class Base as
                    pub val pick: int = 10;
                end
                define class Child extends Base as
                    pub pick(Color x): int { return 20; }
                end
                """;
        IllegalArgumentException rejected = rejectProof(
                source, "Child", Domain.INSTANCE, "pick", 1);
        assertTrue(rejected.getMessage().contains("field and nominal method"),
                rejected::getMessage);
    }

    @Test
    void concreteClassCannotClaimUnimplementedAbstractSlot() {
        String source = """
                define abstract class Base as
                    pub abstract pick(Color x): int;
                end
                define class Child extends Base as
                end
                """;
        IllegalArgumentException rejected = rejectProof(
                source, "Child", Domain.INSTANCE, "pick", 1);
        assertTrue(rejected.getMessage().contains("unresolved abstract"),
                rejected::getMessage);
    }

    @Test
    void broadShallowInheritanceIsBoundedWithoutStackOverflow() {
        // Build typed AST directly to avoid relying on a giant source parser
        // fixture: this checks the proof itself, not the parser's limits.
        java.util.List<Ast.Decl> declarations = new java.util.ArrayList<>();
        java.util.List<Ast.TypeRef> parentTypes = new java.util.ArrayList<>();
        for (int i = 0; i < 1025; i++) {
            String name = "Base" + i;
            declarations.add(new Ast.ClassDecl(name, false, Ast.ActorKind.NONE,
                    List.of(), List.of(), List.of(), List.of(), List.of()));
            parentTypes.add(Ast.TypeRef.simple(name));
        }
        declarations.add(new Ast.ClassDecl("Root", false, Ast.ActorKind.NONE,
                List.of(), parentTypes, List.of(), List.of(), List.of()));
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("root", declarations)));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.prove(
                        program, "Root", Domain.INSTANCE, "pick", 0));
        assertTrue(rejected.getMessage().contains("class-count bound"),
                rejected::getMessage);
    }

    @Test
    void forgedStructuralMethodCannotEnterNominalProof() {
        Ast.Program parsed = Parser.parse(ENUMS + """
                define class Root as
                    pub pick(Color x): int { return 1; }
                end
                """);
        Ast.ModuleDecl root = parsed.modules().stream()
                .filter(module -> module.declarations().stream()
                        .anyMatch(Ast.ClassDecl.class::isInstance))
                .findFirst().orElseThrow();
        Ast.ClassDecl original = root.declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast).findFirst().orElseThrow();
        Ast.MethodDecl method = original.methods().getFirst();
        Ast.MethodDecl structural = new Ast.MethodDecl(
                method.name(), method.visibility(), method.isStatic(),
                method.isAbstract(), method.async(), method.suspends(),
                true, method.explicitReceiverType(),
                method.genericParameters(), method.parameters(),
                method.returnType(), method.annotations(), method.body());
        Ast.ClassDecl forged = new Ast.ClassDecl(
                original.name(), original.visibility(), original.isAbstract(),
                original.actorKind(), original.genericParameters(),
                original.parents(), original.interfaces(), original.fields(),
                original.constructor(), List.of(structural));
        java.util.List<Ast.ModuleDecl> modules = new java.util.ArrayList<>();
        for (Ast.ModuleDecl module : parsed.modules()) {
            java.util.List<Ast.Decl> declarations = new java.util.ArrayList<>();
            for (Ast.Decl decl : module.declarations()) {
                declarations.add(decl == original ? forged : decl);
            }
            modules.add(new Ast.ModuleDecl(module.name(),
                    module.annotations(), module.contracts(), declarations));
        }
        Ast.Program mutated = new Ast.Program(parsed.namespace(),
                parsed.imports(), modules);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.prove(
                        mutated, "Root", Domain.INSTANCE, "pick", 1));
        assertTrue(rejected.getMessage().contains("structural methods"),
                rejected::getMessage);
    }

    @Test
    void resolvedEnumTypesSelectInheritedTargetWithoutCallerSuppliedSymbolIds() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color x): int { return 11; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 22; }
                end
                """);
        var registry = dev.oreslang.types.TaggedMessageContracts.forTypes(program);
        var color = NominalInheritedMethodSlots.selectResolved(
                program, "Child", Domain.INSTANCE, "pick",
                List.of(new dev.oreslang.types.Types.Named("Color", List.of())), registry);
        var mode = NominalInheritedMethodSlots.selectResolved(
                program, "Child", Domain.INSTANCE, "pick",
                List.of(new dev.oreslang.types.Types.Named("Mode", List.of())), registry);
        assertEquals("Base", color.owner().name());
        assertEquals("Child", mode.owner().name());
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.selectResolved(
                        program, "Child", Domain.INSTANCE, "pick",
                        List.of(dev.oreslang.types.Types.Unknown.INSTANCE), registry));
        assertTrue(unknown.getMessage().contains("cannot prove nominal enum identity"),
                unknown::getMessage);
        assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.selectResolved(
                        program, "Child", Domain.INSTANCE, "pick",
                        List.of(new dev.oreslang.types.Types.Named("Impostor", List.of())),
                        registry));
    }

    @Test
    void hugeSingleClassMethodInventoryIsRejectedBeforeSignatureScan() {
        var method = new Ast.MethodDecl(
                "pick", Ast.Visibility.PUBLIC, false, false, false, false,
                false, null, List.of(), List.of(),
                Ast.TypeRef.simple("int"), List.of(), List.of());
        var klass = new Ast.ClassDecl(
                "Excessive", Ast.Visibility.PRIVATE, false,
                Ast.ActorKind.NONE, List.of(), List.of(), List.of(),
                List.of(), null, java.util.Collections.nCopies(
                        dev.oreslang.types.NominalMethodSlots.MAX_LOCAL_METHOD_DECLARATIONS + 1,
                        method));
        var program = new Ast.Program(List.of(
                new Ast.ModuleDecl("root", List.of(klass))));
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.prove(
                        program, "Excessive", Domain.INSTANCE, "pick", 0));
        assertTrue(rejected.getMessage().contains("method declaration limit"),
                rejected::getMessage);
    }

    @Test
    void singleCandidateDoesNotTurnUnknownTypeIntoNominalProof() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Only as
                    pub pick(Color x): int { return 1; }
                end
                """);
        var registry = dev.oreslang.types.TaggedMessageContracts.forTypes(program);
        // The arity-only checker may handle the single declaration, but
        // the nominal-overload selection helper must not claim type proof.
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.selectResolved(
                        program, "Only", Domain.INSTANCE, "pick",
                        List.of(dev.oreslang.types.Types.Unknown.INSTANCE), registry));
        assertTrue(rejected.getMessage().contains("requires multiple candidates"),
                rejected::getMessage);
    }

    @Test
    void typedSelectionValidatesNondiscriminatorScalarArguments() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color value, int count): int { return 11; }
                end
                define class Child extends Base as
                    pub pick(Mode value, int count): int { return 22; }
                end
                """);
        var registry = dev.oreslang.types.TaggedMessageContracts.forTypes(program);
        var selected = NominalInheritedMethodSlots.selectResolved(
                program, "Child", Domain.INSTANCE, "pick",
                List.of(new dev.oreslang.types.Types.Named("Color", List.of()),
                        dev.oreslang.types.Types.Primitive.INT), registry);
        assertEquals("Base", selected.owner().name());
        IllegalArgumentException badScalar = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.selectResolved(
                        program, "Child", Domain.INSTANCE, "pick",
                        List.of(new dev.oreslang.types.Types.Named("Color", List.of()),
                                dev.oreslang.types.Types.Primitive.STRING), registry));
        assertTrue(badScalar.getMessage().contains("primitive parameter"),
                badScalar::getMessage);
        assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.selectResolved(
                        program, "Child", Domain.INSTANCE, "pick",
                        List.of(new dev.oreslang.types.Types.Named("Color", List.of()),
                                dev.oreslang.types.Types.Unknown.INSTANCE), registry));
    }

    @Test
    void typedSelectionValidatesNondiscriminatorEnumArguments() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color first, Color second): int { return 11; }
                end
                define class Child extends Base as
                    pub pick(Color first, Mode second): int { return 22; }
                end
                """);
        var registry = dev.oreslang.types.TaggedMessageContracts.forTypes(program);
        assertEquals("Child", NominalInheritedMethodSlots.selectResolved(
                program, "Child", Domain.INSTANCE, "pick",
                List.of(new dev.oreslang.types.Types.Named("Color", List.of()),
                        new dev.oreslang.types.Types.Named("Mode", List.of())),
                registry).owner().name());
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.selectResolved(
                        program, "Child", Domain.INSTANCE, "pick",
                        List.of(dev.oreslang.types.Types.Unknown.INSTANCE,
                                new dev.oreslang.types.Types.Named("Mode", List.of())),
                        registry));
        assertTrue(mismatch.getMessage().contains("selected nominal enum type"),
                mismatch::getMessage);
    }

    @Test
    void externalRegistryCannotForgeAnEnumCaseForTypedSelection() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color x): int { return 11; }
                end
                define class Child extends Base as
                    pub pick(Mode x): int { return 22; }
                end
                """);
        var fake = new dev.oreslang.types.TaggedMessageContracts.Registry(
                Map.of("Color", new dev.oreslang.types.TaggedMessageContracts.EnumType(
                        "Color", List.of(new dev.oreslang.types.TaggedMessageContracts.Case(
                                "Color", "Invented", 999, null, null)))),
                Map.of());
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.selectResolved(
                        program, "Child", Domain.INSTANCE, "pick",
                        List.of(new dev.oreslang.types.Types.Named(
                                "Color.Invented", List.of())), fake));
        assertTrue(rejected.getMessage().contains("cannot prove nominal enum identity"),
                rejected::getMessage);
    }

    @Test
    void boundInstanceEnumSlotOverridesOnlyMatchingInheritedMethod() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color value): int { return 11; }
                    pub pick(Mode value): int { return 22; }
                end
                define class Child extends Base as
                    pub pick(Color value): int { return 33; }
                end
                define class Grandchild extends Child as
                    pub pick(Mode value): int { return 44; }
                end
                """);
        var color = NominalInheritedMethodSlots.bindInstance(
                program, "Base", "pick",
                List.of(new dev.oreslang.types.Types.Named("Color", List.of())));
        var mode = NominalInheritedMethodSlots.bindInstance(
                program, "Base", "pick",
                List.of(new dev.oreslang.types.Types.Named("Mode", List.of())));
        assertNotEquals(color.slot(), mode.slot());
        assertEquals("Base", color.declaredTarget().owner().name());
        assertEquals("Base", mode.declaredTarget().owner().name());
        assertEquals("Base", NominalInheritedMethodSlots.resolveVirtual(
                program, color, "Base").owner().name());
        assertEquals("Child", NominalInheritedMethodSlots.resolveVirtual(
                program, color, "Child").owner().name());
        assertEquals("Base", NominalInheritedMethodSlots.resolveVirtual(
                program, mode, "Child").owner().name());
        assertEquals("Child", NominalInheritedMethodSlots.resolveVirtual(
                program, color, "Grandchild").owner().name());
        assertEquals("Grandchild", NominalInheritedMethodSlots.resolveVirtual(
                program, mode, "Grandchild").owner().name());
    }

    @Test
    void boundVirtualMethodRejectsUnrelatedReceiverAndCrossProgramForgery() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color value): int { return 1; }
                    pub pick(Mode value): int { return 2; }
                end
                define class Child extends Base as
                    pub pick(Color value): int { return 3; }
                end
                define class Other as
                    pub pick(Color value): int { return 4; }
                end
                """);
        var bound = NominalInheritedMethodSlots.bindInstance(
                program, "Base", "pick",
                List.of(new dev.oreslang.types.Types.Named("Color", List.of())));
        var otherProgram = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color value): int { return 1; }
                    pub pick(Mode value): int { return 2; }
                end
                define class Child extends Base as
                    pub pick(Color value): int { return 3; }
                end
                """);
        var unrelated = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.resolveVirtual(
                        program, bound, "Other"));
        assertTrue(unrelated.getMessage().contains("not a subtype"), unrelated::getMessage);
        var foreign = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.resolveVirtual(
                        otherProgram, bound, "Child"));
        assertTrue(foreign.getMessage().contains("outside compiler program"), foreign::getMessage);
    }

    @Test
    void forgedCallSiteBindingCannotChangeSelectedNominalSlot() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color value): int { return 1; }
                    pub pick(Mode value): int { return 2; }
                end
                define class Child extends Base as
                    pub pick(Color value): int { return 3; }
                end
                """);
        var bound = NominalInheritedMethodSlots.bindInstance(
                program, "Base", "pick",
                List.of(new dev.oreslang.types.Types.Named("Color", List.of())));
        var otherTarget = NominalInheritedMethodSlots.selectResolved(
                program, "Base", Domain.INSTANCE, "pick",
                List.of(new dev.oreslang.types.Types.Named("Mode", List.of())),
                dev.oreslang.types.TaggedMessageContracts.forTypes(program));
        var forged = new NominalInheritedMethodSlots.InstanceBinding(
                bound.staticReceiver(), bound.slot(), otherTarget);
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.resolveVirtual(
                        program, forged, "Child"));
        assertTrue(rejected.getMessage().contains("forged or stale"), rejected::getMessage);
        assertThrows(IllegalArgumentException.class,
                () -> new NominalInheritedMethodSlots.InstanceBinding(
                        bound.staticReceiver(),
                        new Slot(Domain.STATIC, bound.slot().name(), bound.slot().positions()),
                        bound.declaredTarget()));
    }

    @Test
    void diamondResolutionKeepsInheritedNominalBinding() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color value): int { return 1; }
                    pub pick(Mode value): int { return 2; }
                end
                define class Left extends Base as
                end
                define class Right extends Base as
                end
                define class Diamond extends Left, Right as
                    pub pick(Mode value): int { return 3; }
                end
                """);
        var color = NominalInheritedMethodSlots.bindInstance(
                program, "Base", "pick",
                List.of(new dev.oreslang.types.Types.Named("Color", List.of())));
        var mode = NominalInheritedMethodSlots.bindInstance(
                program, "Base", "pick",
                List.of(new dev.oreslang.types.Types.Named("Mode", List.of())));
        assertEquals("Base", NominalInheritedMethodSlots.resolveVirtual(
                program, color, "Diamond").owner().name());
        assertEquals("Diamond", NominalInheritedMethodSlots.resolveVirtual(
                program, mode, "Diamond").owner().name());
    }

    @Test
    void virtualBindingCannotConvertStaticOverloadsIntoInstanceMethods() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub static fnc pick(Color value): int { return 1; }
                    pub static fnc pick(Mode value): int { return 2; }
                end
                """);
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> NominalInheritedMethodSlots.bindInstance(
                        program, "Base", "pick",
                        List.of(new dev.oreslang.types.Types.Named("Color", List.of()))));
        assertTrue(rejected.getMessage().contains("no inherited nominal method candidates"),
                rejected::getMessage);
    }

    @Test
    void virtualProofDoesNotPrematurelyAdmitExecutableSameArityMethods() {
        Ast.Program program = Parser.parse(ENUMS + """
                define class Base as
                    pub pick(Color value): int { return 1; }
                    pub pick(Mode value): int { return 2; }
                end
                define class Child extends Base as
                    pub pick(Color value): int { return 3; }
                end
                """);
        var bound = NominalInheritedMethodSlots.bindInstance(
                program, "Base", "pick",
                List.of(new dev.oreslang.types.Types.Named("Color", List.of())));
        assertEquals("Child", NominalInheritedMethodSlots.resolveVirtual(
                program, bound, "Child").owner().name());
        // The compiler's production admission path is still arity-only.
        // Remove this assertion only with a tested runtime and LLVM slot ABI.
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(program));
    }
}
