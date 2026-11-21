package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ConstructorFlowState;
import dev.oreslang.types.ConstructorSemanticIr;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ConstructorSemanticIrTest {

    @Test
    void constructorKeyUsesCallerVisibleArityOnly() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("""
                define class Foo as
                  constructor(self, int value) { return; }
                end
                """).modules().getFirst().declarations().getFirst();

        Ast.ConstructorDecl source = klass.constructor();
        ConstructorSemanticIr.ConstructorDecl semantic =
                ConstructorSemanticIr.ConstructorDecl.of(
                        "Foo",
                        ConstructorSemanticIr.ReceiverBinding.EXPLICIT_SELF,
                        source);

        assertEquals(new ConstructorSemanticIr.ConstructorKey("Foo", 1), semantic.key());
        assertEquals(1, semantic.userParameters().size());
        assertEquals("value", semantic.userParameters().getFirst().name());
    }

    @Test
    void constructorDelegationCannotCrossOwners() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("""
                define class Foo as
                  constructor(int value) { return; }
                end
                """).modules().getFirst().declarations().getFirst();

        ConstructorSemanticIr.ConstructorDecl semantic =
                ConstructorSemanticIr.ConstructorDecl.of(
                        "Foo",
                        ConstructorSemanticIr.ReceiverBinding.IMPLICIT,
                        klass.constructor());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> semantic.withDelegation(
                        new ConstructorSemanticIr.ConstructorKey("Bar", 1)));
        assertTrue(failure.getMessage().contains("stay within owner"));
    }

    @Test
    void constructionStateJoinPreservesRegionAndFieldIntervals() {
        ConstructorSemanticIr.ConstructionRegionId region =
                new ConstructorSemanticIr.ConstructionRegionId("Foo", 1, 0);
        ConstructorFlowState blank = ConstructorFlowState.blank(List.of("x"));
        ConstructorSemanticIr.ConstructionState entry =
                new ConstructorSemanticIr.ConstructionState(
                        region,
                        ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                        blank,
                        List.of());

        ConstructorSemanticIr.ConstructionState written = entry.withFields(blank.write("x"));
        ConstructorSemanticIr.ConstructionState joined = entry.join(written);

        assertEquals(
                new ConstructorFlowState.AssignmentCount(0, 1),
                joined.fields().count("x"));
        assertFalse(joined.publishable());
    }

    @Test
    void constructionStateCannotJoinDifferentRegionsOrMoveBackward() {
        ConstructorFlowState fields = ConstructorFlowState.blank(List.of("x"));
        ConstructorSemanticIr.ConstructionState first =
                new ConstructorSemanticIr.ConstructionState(
                        new ConstructorSemanticIr.ConstructionRegionId("Foo", 0, 0),
                        ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                        fields,
                        List.of());
        ConstructorSemanticIr.ConstructionState second =
                new ConstructorSemanticIr.ConstructionState(
                        new ConstructorSemanticIr.ConstructionRegionId("Foo", 0, 1),
                        ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                        fields,
                        List.of());

        assertThrows(IllegalArgumentException.class, () -> first.join(second));
        ConstructorSemanticIr.ConstructionState initialized =
                first.transitionTo(ConstructorSemanticIr.ConstructionPhase.INITIALIZED);
        assertThrows(
                IllegalArgumentException.class,
                () -> initialized.transitionTo(ConstructorSemanticIr.ConstructionPhase.INITIALIZING));
    }

    @Test
    void onlyConstructedAndCompleteStateIsPublishable() {
        ConstructorFlowState complete = ConstructorFlowState.blank(List.of("x")).write("x");
        ConstructorSemanticIr.ConstructionState state =
                new ConstructorSemanticIr.ConstructionState(
                        new ConstructorSemanticIr.ConstructionRegionId("Foo", 0, 0),
                        ConstructorSemanticIr.ConstructionPhase.INITIALIZED,
                        complete,
                        List.of());

        assertFalse(state.publishable());
        assertTrue(state.transitionTo(ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED).publishable());
    }

    @Test
    void callableIrErasesOnlySignatureNotSemanticMetadata() {
        ConstructorSemanticIr.CallableType callable =
                new ConstructorSemanticIr.CallableType(
                        List.of(Types.Primitive.INT),
                        Types.Primitive.STRING,
                        Set.of(ConstructorSemanticIr.CallableEffect.READS_CONSTRUCTION),
                        Set.of(ConstructorSemanticIr.CallableCapability.CONSTRUCTION_SCOPED));

        assertEquals(Types.AnyCallable.INSTANCE, callable.erasedCallableTop());
        assertTrue(callable.effects().contains(
                ConstructorSemanticIr.CallableEffect.READS_CONSTRUCTION));
        assertTrue(callable.captureCapabilities().contains(
                ConstructorSemanticIr.CallableCapability.CONSTRUCTION_SCOPED));
    }

    @Test
    void recursiveSelfCellIsRuntimeFixpointNotExternalOwnershipOrActorData() {
        ConstructorSemanticIr.CaptureBinding recursive =
                new ConstructorSemanticIr.CaptureBinding(
                        "root::fact", ConstructorSemanticIr.CaptureOrigin.RECURSIVE_SELF, false);
        assertFalse(recursive.participatesInExternalOwnership());
        assertFalse(recursive.actorDataAdmissible());

        ConstructorSemanticIr.CaptureBinding outer =
                new ConstructorSemanticIr.CaptureBinding(
                        "root::bias", ConstructorSemanticIr.CaptureOrigin.EXTERNAL_BINDING, true);
        assertTrue(outer.participatesInExternalOwnership());
        assertFalse(outer.actorDataAdmissible());

        assertThrows(IllegalArgumentException.class, () ->
                new ConstructorSemanticIr.CaptureBinding(
                        "root::fact", ConstructorSemanticIr.CaptureOrigin.RECURSIVE_SELF, true));
    }

    @Test
    void serializableCaptureMetadataNeverMakesExecutableCallablePortableData() {
        ConstructorSemanticIr.CallableType callable =
                new ConstructorSemanticIr.CallableType(
                        List.of(Types.Primitive.INT),
                        Types.Primitive.STRING,
                        Set.of(),
                        Set.of(ConstructorSemanticIr.CallableCapability.CAPTURES_SERIALIZABLE_DATA));

        assertTrue(callable.captureCapabilities().contains(
                ConstructorSemanticIr.CallableCapability.CAPTURES_SERIALIZABLE_DATA));
        assertEquals(Types.AnyCallable.INSTANCE, callable.erasedCallableTop());
        assertFalse(callable.serializableExecutableValue());
        assertFalse(callable.actorDataAdmissible());
    }

    @Test
    void recursiveAliasRequiresRepresentationBarrierAndRegularGenericRecursion() {
        Ast.TypeRef body = Ast.TypeRef.simple("Callback");

        ConstructorSemanticIr.RecursiveAliasType valid =
                new ConstructorSemanticIr.RecursiveAliasType(
                        "root::Callback",
                        "Callback",
                        List.of(),
                        body,
                        true,
                        true);
        assertDoesNotThrow(valid::requireWellFormed);

        ConstructorSemanticIr.RecursiveAliasType inlineCycle =
                new ConstructorSemanticIr.RecursiveAliasType(
                        "root::Node",
                        "Node",
                        List.of(),
                        body,
                        false,
                        true);
        assertThrows(IllegalArgumentException.class, inlineCycle::requireWellFormed);

        ConstructorSemanticIr.RecursiveAliasType expandingGeneric =
                new ConstructorSemanticIr.RecursiveAliasType(
                        "root::Strange",
                        "Strange",
                        List.of("T"),
                        body,
                        true,
                        false);
        assertThrows(IllegalArgumentException.class, expandingGeneric::requireWellFormed);
    }
    @Test
    void mutableFieldMayBeReassignedBeforePublishingConstructedInstance() {
        ConstructorFlowState complete = ConstructorFlowState.blank(List.of("fixed", "mutable"))
                .write("fixed").write("mutable").write("mutable");
        assertFalse(complete.completeExactlyOnce());
        assertTrue(complete.completeWithFixedFields(Set.of("fixed")));

        ConstructorSemanticIr.ConstructionState state =
                new ConstructorSemanticIr.ConstructionState(
                        new ConstructorSemanticIr.ConstructionRegionId("Pair", 0, 0),
                        ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                        complete,
                        List.of(),
                        Set.of("fixed"));
        assertFalse(state.publishable());
        assertTrue(state.transitionTo(ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED).publishable());
        assertFalse(new ConstructorSemanticIr.ConstructionState(
                state.region(), ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED,
                complete, List.of()).publishable());
    }

    @Test
    void missingMutableFieldPreventsPublicationEvenWhenFixedFieldIsComplete() {
        ConstructorFlowState incomplete = ConstructorFlowState.blank(List.of("fixed", "mutable"))
                .write("fixed");
        assertFalse(incomplete.completeWithFixedFields(Set.of("fixed")));
        ConstructorSemanticIr.ConstructionState state =
                new ConstructorSemanticIr.ConstructionState(
                        new ConstructorSemanticIr.ConstructionRegionId("Pair", 0, 0),
                        ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED,
                        incomplete,
                        List.of(),
                        Set.of("fixed"));
        assertFalse(state.publishable());
    }

    @Test
    void differingFixedFieldContractsCannotJoinOrReferenceUnknownFields() {
        ConstructorFlowState state = ConstructorFlowState.blank(List.of("x")).write("x");
        ConstructorSemanticIr.ConstructionState baseline =
                new ConstructorSemanticIr.ConstructionState(
                        new ConstructorSemanticIr.ConstructionRegionId("Box", 0, 0),
                        ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                        state, List.of());
        ConstructorSemanticIr.ConstructionState allMutable =
                new ConstructorSemanticIr.ConstructionState(
                        baseline.region(), baseline.phase(), baseline.fields(),
                        baseline.pendingCleanups(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> baseline.join(allMutable));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructorSemanticIr.ConstructionState(
                        baseline.region(), baseline.phase(), baseline.fields(),
                        baseline.pendingCleanups(), Set.of("notDeclared")));
        assertThrows(IllegalArgumentException.class,
                () -> state.completeWithFixedFields(Set.of("notDeclared")));
    }

    @Test
    void constructorCannotPublishWithPendingCleanupOrIncompleteFields() {
        ConstructorFlowState complete = ConstructorFlowState.blank(List.of("x")).write("x");
        ConstructorSemanticIr.ConstructionState entry =
                new ConstructorSemanticIr.ConstructionState(
                        new ConstructorSemanticIr.ConstructionRegionId("Example", 0, 0),
                        ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                        complete, List.of());
        var pending = entry.withCleanup(
                new ConstructorSemanticIr.CleanupEdge(
                        ConstructorSemanticIr.CleanupKind.DEFER, "release"));
        assertThrows(IllegalArgumentException.class,
                () -> pending.transitionTo(ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED));
        var constructed = entry.transitionTo(ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED);
        assertTrue(constructed.publishable());
        assertThrows(IllegalArgumentException.class,
                () -> constructed.withCleanup(
                        new ConstructorSemanticIr.CleanupEdge(
                                ConstructorSemanticIr.CleanupKind.FIELD_DROP, "x")));
        assertThrows(IllegalArgumentException.class,
                () -> constructed.withFields(complete));
        var missing = entry.withFields(ConstructorFlowState.blank(List.of("x")));
        assertThrows(IllegalArgumentException.class,
                () -> missing.transitionTo(ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED));
    }

    @Test
    void constructorFieldSetCannotExpandOrShrinkDuringAnalysis() {
        ConstructorSemanticIr.ConstructionState entry =
                new ConstructorSemanticIr.ConstructionState(
                        new ConstructorSemanticIr.ConstructionRegionId("Example", 0, 0),
                        ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                        ConstructorFlowState.blank(List.of("x")), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> entry.withFields(ConstructorFlowState.blank(List.of("x", "y"))));
        assertThrows(IllegalArgumentException.class,
                () -> entry.withFields(ConstructorFlowState.blank(List.of())));
    }

    @Test
    void registeredConstructorCleanupMustCompleteInLifoOrder() {
        ConstructorSemanticIr.ConstructionRegionId region =
                new ConstructorSemanticIr.ConstructionRegionId("Example", 0, 0);
        ConstructorSemanticIr.ConstructionState state =
                new ConstructorSemanticIr.ConstructionState(
                        region, ConstructorSemanticIr.ConstructionPhase.INITIALIZING,
                        ConstructorFlowState.blank(List.of("x")).write("x"),
                        List.of());
        var first = new ConstructorSemanticIr.CleanupEdge(
                ConstructorSemanticIr.CleanupKind.DEFER, "first");
        var second = new ConstructorSemanticIr.CleanupEdge(
                ConstructorSemanticIr.CleanupKind.FINALLY, "second");
        var pending = state.withCleanup(first).withCleanup(second);
        assertThrows(IllegalArgumentException.class, () -> pending.completeCleanup(first));
        assertThrows(IllegalArgumentException.class,
                () -> pending.transitionTo(ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED));
        var discharged = pending.completeCleanup(second).completeCleanup(first);
        assertTrue(discharged.pendingCleanups().isEmpty());
        assertTrue(discharged.transitionTo(ConstructorSemanticIr.ConstructionPhase.CONSTRUCTED)
                .publishable());
        assertThrows(IllegalArgumentException.class, () -> state.completeCleanup(first));
    }


    @Test
    void capturePlanUsesResolvedIdentityNotSourceSpelling() {
        var outer = new ConstructorSemanticIr.ResolvedCaptureBinding(
                new ConstructorSemanticIr.CaptureBindingId("root::fn#outer:x"),
                "x",
                Types.Primitive.INT,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.LEXICAL_SLOT_ALIAS,
                Set.of());
        var inner = new ConstructorSemanticIr.ResolvedCaptureBinding(
                new ConstructorSemanticIr.CaptureBindingId("root::fn#block1:x"),
                "x",
                Types.Primitive.INT,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.LEXICAL_SLOT_ALIAS,
                Set.of());
        var plan = new ConstructorSemanticIr.CapturePlan(List.of(outer, inner));
        assertEquals(2, plan.bindings().size(),
                "shadowed names are distinct semantic bindings");
    }

    @Test
    void duplicateSemanticCaptureIdentityIsRejected() {
        var id = new ConstructorSemanticIr.CaptureBindingId("root::fn#slot");
        var first = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "value", Types.Primitive.INT,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.DETACHED_VALUE_SNAPSHOT,
                Set.of());
        var duplicate = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "value", Types.Primitive.INT,
                ConstructorSemanticIr.CaptureAccess.WRITE,
                ConstructorSemanticIr.CaptureTransfer.DETACHED_OWNERSHIP_MOVE,
                Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructorSemanticIr.CapturePlan(List.of(first, duplicate)));
    }

    @Test
    void capturePlanMergePromotesWriteMoveAndUnionsCapabilities() {
        var id = new ConstructorSemanticIr.CaptureBindingId("root::ctor#self");
        var read = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "self", Types.Unknown.INSTANCE,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.DETACHED_VALUE_SNAPSHOT,
                Set.of(ConstructorSemanticIr.CallableCapability.CONSTRUCTION_SCOPED));
        var write = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "self", Types.Unknown.INSTANCE,
                ConstructorSemanticIr.CaptureAccess.WRITE,
                ConstructorSemanticIr.CaptureTransfer.DETACHED_OWNERSHIP_MOVE,
                Set.of(ConstructorSemanticIr.CallableCapability.BORROWED));

        var merged = new ConstructorSemanticIr.CapturePlan(List.of(read))
                .merge(new ConstructorSemanticIr.CapturePlan(List.of(write)));
        assertEquals(1, merged.bindings().size());
        assertEquals(ConstructorSemanticIr.CaptureAccess.WRITE,
                merged.bindings().getFirst().access());
        assertEquals(ConstructorSemanticIr.CaptureTransfer.DETACHED_OWNERSHIP_MOVE,
                merged.bindings().getFirst().transfer());
        assertTrue(merged.capturesBorrow());
        assertTrue(merged.capturesConstructionState());
        assertFalse(merged.actorDataAdmissible());
    }

    @Test
    void incompatibleLexicalAndDetachedTransferModesCannotMerge() {
        var id = new ConstructorSemanticIr.CaptureBindingId("root::fn#slot");
        var lexical = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "value", Types.Primitive.INT,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.LEXICAL_SLOT_ALIAS,
                Set.of());
        var detached = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "value", Types.Primitive.INT,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.DETACHED_VALUE_SNAPSHOT,
                Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructorSemanticIr.CapturePlan(List.of(lexical))
                        .merge(new ConstructorSemanticIr.CapturePlan(List.of(detached))));
    }

    @Test
    void inconsistentCaptureIdentityCannotBeMergedAcrossDifferentTypesOrNames() {
        var id = new ConstructorSemanticIr.CaptureBindingId("root::fn#0");
        var first = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "first", Types.Primitive.INT,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.LEXICAL_SLOT_ALIAS,
                Set.of());
        var wrongName = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "second", Types.Primitive.INT,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.LEXICAL_SLOT_ALIAS,
                Set.of());
        var wrongType = new ConstructorSemanticIr.ResolvedCaptureBinding(
                id, "first", Types.Primitive.STRING,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.LEXICAL_SLOT_ALIAS,
                Set.of());

        assertThrows(IllegalArgumentException.class,
                () -> new ConstructorSemanticIr.CapturePlan(List.of(first))
                        .merge(new ConstructorSemanticIr.CapturePlan(List.of(wrongName))));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructorSemanticIr.CapturePlan(List.of(first))
                        .merge(new ConstructorSemanticIr.CapturePlan(List.of(wrongType))));
    }

    @Test
    void serializableCapturesNeverAuthorizeExecutableOrAnyCallableTransport() {
        var capture = new ConstructorSemanticIr.ResolvedCaptureBinding(
                new ConstructorSemanticIr.CaptureBindingId("root::fn#payload"),
                "payload",
                Types.Primitive.STRING,
                ConstructorSemanticIr.CaptureAccess.READ,
                ConstructorSemanticIr.CaptureTransfer.DETACHED_VALUE_SNAPSHOT,
                Set.of(ConstructorSemanticIr.CallableCapability.CAPTURES_SERIALIZABLE_DATA));
        var plan = new ConstructorSemanticIr.CapturePlan(List.of(capture));
        assertFalse(plan.serializableExecutableValue());
        assertFalse(plan.actorDataAdmissible());

        var callable = new ConstructorSemanticIr.CallableType(
                List.of(),
                Types.Primitive.VOID,
                Set.of(),
                Set.of(ConstructorSemanticIr.CallableCapability.CAPTURES_SERIALIZABLE_DATA));
        assertEquals(Types.AnyCallable.INSTANCE, callable.erasedCallableTop());
        assertFalse(callable.serializableExecutableValue());
        assertFalse(callable.actorDataAdmissible());
    }

}
