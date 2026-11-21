package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ActorCriticalStateMirLayoutV1;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.compiler.ActorCriticalStateMirV3;
import dev.oreslang.compiler.StateRegionLoanReceipt;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Typed critical state syntax has its own AST identity; no generic
 * CallExpr may masquerade as a checked actor state loan.
 *
 * The compiler/evaluator fail-closed until the state MIR and owner
 * provenance are executable. This is NOT a guest execution proof.
 */
final class ActorCriticalStateSyntaxV2Test {
    @TempDir Path root;

    private static final String NS = "namespace counter;\n";
    private static final String STATEFUL_METHOD = NS + """
            external mtd (Counter c) receive(ActorMail<int> mail): void {
                self.handle_state(critical-write |s| -> {
                    s.total = s.total + mail.value;
                });
                val int snapshot = self.handle_state(critical-read |view| -> view.total);
                self.send(snapshot);
                return;
            }
            """;

    private static Ast.ExternalActorMethodDecl method(String source) {
        return assertInstanceOf(Ast.ExternalActorMethodDecl.class,
                Parser.parse(source).modules().getFirst()
                        .declarations().getFirst());
    }

    private static Ast.ActorStateAccessStmt scope(Ast.Stmt stmt,
                                                  Ast.CriticalStateMode mode) {
        var access = assertInstanceOf(Ast.ActorStateAccessStmt.class, stmt);
        assertEquals(mode, access.mode());
        assertFalse(access.body().isEmpty());
        return access;
    }

    @Test
    void statefulMethodKeepsDistinctWriterAndReaderScopes() {
        var body = method(STATEFUL_METHOD).body();
        assertEquals(4, body.size());
        var write = scope(body.get(0), Ast.CriticalStateMode.WRITE);
        assertEquals("s", write.viewName());
        var assignment = assertInstanceOf(Ast.ExprStmt.class,
                write.body().getFirst());
        assertInstanceOf(Ast.AssignExpr.class, assignment.expression());

        var copy = assertInstanceOf(Ast.BindingStmt.class, body.get(1));
        assertEquals(Ast.BindingKind.VAL, copy.kind());
        assertEquals("int", copy.declaredType().name());
        assertEquals("snapshot", copy.name());
        var read = assertInstanceOf(Ast.ActorStateReadExpr.class, copy.initializer());
        assertEquals("view", read.viewName());
        assertEquals("total", read.fieldName());
        assertInstanceOf(Ast.CallExpr.class,
                assertInstanceOf(Ast.ExprStmt.class, body.get(2)).expression());
        assertInstanceOf(Ast.ReturnStmt.class, body.get(3));
    }

    @Test
    void malformedOrNonadjacentCriticalModesCannotBeMinted() {
        for (var mode : new String[] {"critical-readwrite", "critical -write",
                "critical-writee", "critical-w rite", "critical - read"}) {
            var source = NS + "mtd (Counter c) receive(ActorMail<int> mail): void { "
                    + "self.handle_state(" + mode + " |s| -> { s.total = 1; }); return; }";
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), mode);
            assertFalse(rejected.getMessage().isBlank());
        }
    }

    @Test
    void escapedOrEmptyCriticalViewIsNotAValidStatement() {
        for (String body : new String[] {
                "self.handle_state(critical-read |_| -> { s.total; });",
                "self.handle_state(critical-write |self| -> { s.total = 1; });",
                "self.handle_state(critical-write |s| -> { });"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(NS + "mtd (Counter c) f(): void { "
                            + body + " return; }"), body);
        }
    }

    @Test
    void unverifiedStateIntrinsicNeverReachesExecutableTypeChecking() {
        String plainFunction = NS + """
                pub fnc state_entry(): void {
                    self.handle_state(critical-write |s| -> { s.total = 1; });
                    return;
                }
                """;
        var parsed = Parser.parse(plainFunction);
        var exception = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(exception.getMessage().contains(
                "ACTOR_CRITICAL_STATE_MIR_UNSUPPORTED"), exception.getMessage());
    }

    private PackageNamespaceBuild physicalBuild() throws Exception {
        Path fixture = Path.of("contracts", "declared-actor-stateful-counter-v2");
        for (String path : new String[] {
                ".atto.pkg.toml", "src/counter/contract.atto",
                "src/counter/actor.atto", "src/counter/state.atto",
                "src/counter/receive.atto", "src/counter/method.atto",
                "src/counter/main.atto"}) {
            Path to = root.resolve(path);
            Files.createDirectories(to.getParent());
            Files.writeString(to, Files.readString(fixture.resolve(path)));
        }
        return new PackageNamespaceBuild(
                OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of()));
    }

    @Test
    void manifestCounterHasOrderedAndBalancedGenerationFencedMir() throws Exception {
        var build = physicalBuild();
        var plan = build.auditCriticalStateMirV3();
        assertEquals(build.packageIdentity(), plan.packageIdentity());
        assertEquals("counter", plan.namespace());
        assertEquals("Counter", plan.actor());
        assertEquals("receive", plan.method());
        assertEquals("src/counter/receive.atto", plan.sourceUnit());
        assertEquals("total", plan.stateField());
        assertEquals("mail", plan.mailBinding());
        assertEquals("snapshot", plan.detachedBinding());
        assertEquals(4L, plan.stopValue());
        assertEquals("int", plan.scalarType());
        assertEquals(64, plan.sourceSha256().length());
        assertEquals(java.util.List.of(
                ActorCriticalStateMirV3.Instruction.REQUIRE_ACTOR_TURN,
                ActorCriticalStateMirV3.Instruction.ACQUIRE_WRITE,
                ActorCriticalStateMirV3.Instruction.LOAD_STATE_I64,
                ActorCriticalStateMirV3.Instruction.LOAD_MAIL_I64,
                ActorCriticalStateMirV3.Instruction.CHECKED_ADD_I64,
                ActorCriticalStateMirV3.Instruction.STORE_STATE_I64,
                ActorCriticalStateMirV3.Instruction.RELEASE_WRITE,
                ActorCriticalStateMirV3.Instruction.ACQUIRE_READ,
                ActorCriticalStateMirV3.Instruction.COPY_STATE_I64,
                ActorCriticalStateMirV3.Instruction.RELEASE_READ,
                ActorCriticalStateMirV3.Instruction.MAILBOX_SEND_COPY_I64,
                ActorCriticalStateMirV3.Instruction.CHECK_STOP,
                ActorCriticalStateMirV3.Instruction.RETURN_VOID),
                plan.instructions());
        var loans = plan.loans();
        assertEquals(4, loans.size());
        assertTrue(loans.get(0).authorizes_release(loans.get(1)));
        assertTrue(loans.get(2).authorizes_release(loans.get(3)));
        assertFalse(loans.get(0).authorizes_release(loans.get(3)));
        assertEquals(StateRegionLoanReceipt.Kind.WRITE, loans.get(0).kind());
        assertEquals(StateRegionLoanReceipt.Kind.READ, loans.get(2).kind());
        assertEquals(StateRegionLoanReceipt.Action.RELEASE, loans.get(3).action());
        assertTrue(loans.stream().allMatch(StateRegionLoanReceipt.Requirement::generation_fence));
        assertTrue(loans.stream().allMatch(StateRegionLoanReceipt.Requirement::current_logical_execution));
        assertEquals("self.total", loans.get(0).place());
        assertEquals(loans.get(0).region_binding(), loans.get(2).region_binding());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.instructions().add(ActorCriticalStateMirV3.Instruction.RETURN_VOID));
    }

    @Test
    void typedMirRejectsUnverifiedEffectsAndStopControlFlow() throws Exception {
        var build = physicalBuild();
        var receive = root.resolve("src/counter/receive.atto");
        String original = Files.readString(receive);
        String[] changes = {
            original.replace("self.send(snapshot);",
                    "self.send(snapshot); self.send(mail.value);"),
            original.replace("self.send(snapshot);",
                    "self.send(snapshot); stdio.stdout.write(5);"),
            original.replace("if mail.value == 4; do",
                    "if mail.value >= 4; do"),
            original.replace("self.end();", "self.send(99);"),
            original.replace("return;", "self.send(0); return;")
        };
        for (String candidate : changes) {
            assertNotEquals(original, candidate, "negative fixture did not mutate");
            Files.writeString(receive, candidate);
            var denied = assertThrows(IllegalArgumentException.class,
                    build::auditCriticalStateMirV3,
                    "unverified effect was silently erased from typed MIR");
            assertFalse(denied.getMessage().isBlank());
        }
    }

    @Test
    void checkedMirCannotGrantExecutionOrAdmitInLoanSend() throws Exception {
        var build = physicalBuild();
        assertNotNull(build.auditCriticalStateMirV3());
        var executable = assertThrows(IllegalArgumentException.class,
                build::compileStatelessActorGuestV1);
        assertTrue(executable.getMessage().contains("sealed state/critical loans"),
                executable.getMessage());

        var receive = root.resolve("src/counter/receive.atto");
        String original = Files.readString(receive);
        Files.writeString(receive, original.replace(
                "self.send(snapshot);",
                "self.send(s.total);"));
        var denied = assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateMirV3);
        assertFalse(denied.getMessage().isBlank());
    }

    @Test
    void physicalMultiFileStatefulActorIsManifestBoundButNotExecutable() throws Exception {
        var build = physicalBuild();
        var audit = build.auditDeclaredActors();
        assertEquals(1, audit.contractWitness().actors().size());
        assertNotNull(audit.contractWitness().actors().values().iterator().next().stateUnit());
        assertEquals(2, audit.criticalOperations().size());
        assertEquals(Ast.CriticalStateMode.WRITE, audit.criticalOperations().getFirst().mode());
        assertEquals(Ast.CriticalStateMode.READ, audit.criticalOperations().get(1).mode());
        assertEquals("total", audit.criticalOperations().getFirst().field());
        assertEquals("src/counter/receive.atto",
                audit.criticalOperations().getFirst().sourceUnit());
        var blocked = assertThrows(IllegalArgumentException.class,
                build::compileStatelessActorGuestV1);
        assertTrue(blocked.getMessage().contains("sealed state/critical loans"),
                blocked.getMessage());
    }

    @Test
    void directStateFieldAccessOutsideLoanFailsManifestPreflight() throws Exception {
        var build = physicalBuild();
        Path path = root.resolve("src/counter/receive.atto");
        Files.writeString(path, Files.readString(path).replace(
                "if mail.value == 4; do", "self.total = 99; if mail.value == 4; do"));
        var failure = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(failure.getMessage().contains("direct actor state member access"),
                failure.getMessage());
    }

    @Test
    void aStateViewCannotEscapeThroughOrdinaryMessageSend() throws Exception {
        var build = physicalBuild();
        Path path = root.resolve("src/counter/receive.atto");
        Files.writeString(path, Files.readString(path).replace(
                "if mail.value == 4; do", "self.send(s); if mail.value == 4; do"));
        var failure = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(failure.getMessage().contains("view escaped"), failure.getMessage());
    }

    @Test
    void aCriticalReaderCannotMutateState() throws Exception {
        var build = physicalBuild();
        Path path = root.resolve("src/counter/receive.atto");
        Files.writeString(path, Files.readString(path).replace(
                "val int snapshot", "let int snapshot"));
        var failure = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(failure.getMessage().contains(
                "detached critical-read requires val int Copy"),
                failure.getMessage());
    }

    @Test
    void immutableOrUndeclaredStateCannotBeWritten() throws Exception {
        var build = physicalBuild();
        Path schema = root.resolve("src/counter/state.atto");
        Files.writeString(schema, Files.readString(schema).replace(
                "let int total", "val int total"));
        var immutable = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(immutable.getMessage().contains("immutable val state"),
                immutable.getMessage());

        Files.writeString(schema, Files.readString(schema).replace(
                "val int total", "let int total"));
        Path method = root.resolve("src/counter/receive.atto");
        Files.writeString(method, Files.readString(method).replace(
                "s.total = s.total + mail.value;", "s.foreign = s.foreign + mail.value;"));
        var foreign = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(foreign.getMessage().contains("undeclared state field"),
                foreign.getMessage());
    }

    @Test
    void detachedReadCannotSendBorrowedViewOrSuspendBeforePublish() throws Exception {
        var build = physicalBuild();
        Path path = root.resolve("src/counter/receive.atto");
        String original = Files.readString(path);
        for (String replacement : new String[] {
                "self.send(s.total);",
                "self.send(mail.value);",
                "await self.send(snapshot);",
                "self.send(snapshot); self.send(snapshot);"
        }) {
            Files.writeString(path, original.replace(
                    "self.send(snapshot);", replacement));
            var failure = assertThrows(IllegalArgumentException.class,
                    build::auditDeclaredActors, replacement);
            assertFalse(failure.getMessage().isBlank(), replacement);
        }
        Files.writeString(path, original.replace(
                "-> s.total)", "-> s.foreign)"));
        var unknown = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(unknown.getMessage().contains("declared scalar int field"),
                unknown.getMessage());

        Files.writeString(path, original.replace(
                "critical-read |s| -> s.total",
                "critical-read |c| -> c.total"));
        var receiverShadow = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(receiverShadow.getMessage().contains("shadow"),
                receiverShadow.getMessage());

        Files.writeString(path, original.replace(
                "self.send(snapshot);",
                "self.send(snapshot); val int extra = "
                + "self.handle_state(critical-read |s| -> s.total); self.send(extra);"));
        var repeatedRead = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(repeatedRead.getMessage().contains("one detached Copy read"),
                repeatedRead.getMessage());
    }

    @Test
    void inLoanMailboxSendIsNoLongerAnAuthorizedReadScope() throws Exception {
        var build = physicalBuild();
        Path path = root.resolve("src/counter/receive.atto");
        String original = Files.readString(path);
        Files.writeString(path, original.replace(
                "val int snapshot = self.handle_state(critical-read |s| -> s.total);\n"
                + "  self.send(snapshot);",
                "self.handle_state(critical-read |s| -> { self.send(s.total); });"));
        var denied = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(denied.getMessage().contains(
                "critical-read mailbox send inside borrowed scope"),
                denied.getMessage());
    }

    @Test
    void detachedCriticalReadIsNotExecutableWithoutAuthenticatedMir() {
        String source = NS + """
                pub fnc state_entry(): void {
                    val int snapshot = self.handle_state(critical-read |s| -> s.total);
                    return;
                }
                """;
        var program = Parser.parse(source);
        var blocked = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(blocked.getMessage().contains("ACTOR_CRITICAL_STATE_MIR_UNSUPPORTED"),
                blocked.getMessage());
    }

    @Test
    void stateScopeRejectsEscapingReturnAndNestedCriticalLoan() throws Exception {
        var build = physicalBuild();
        Path path = root.resolve("src/counter/receive.atto");
        String original = Files.readString(path);
        Files.writeString(path, original.replace(
                "s.total = s.total + mail.value;",
                "return s.total;"));
        var escaping = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(escaping.getMessage().contains("exactly one scalar operation"),
                escaping.getMessage());

        Files.writeString(path, original.replace(
                "s.total = s.total + mail.value;",
                "self.handle_state(critical-read |n| -> { self.send(n.total); });"));
        var nested = assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors);
        assertTrue(nested.getMessage().contains("exactly one scalar operation"),
                nested.getMessage());
    }

    @Test
    void criticalStateLayoutBindsAuthenticatedSchemaToDeterministicScalarSlots() throws Exception {
        var audit = physicalBuild().auditDeclaredActors();
        assertEquals(1, audit.stagedCriticalStateMir().size());
        var plan = audit.stagedCriticalStateMir().getFirst();
        assertEquals("counter", plan.namespace());
        assertEquals("Counter", plan.actor());
        assertEquals("src/counter/state.atto", plan.stateUnit());
        assertTrue(plan.stateSourceSha256().matches("[0-9a-f]{64}"));
        assertEquals(audit.manifestSha256(), plan.manifestSha256());
        assertEquals(1, plan.slots().size());
        var slot = plan.slots().getFirst();
        assertEquals(0, slot.index());
        assertEquals("total", slot.name());
        assertEquals("int", slot.scalarType());
        assertEquals(0L, slot.literalDefault());
        assertTrue(slot.mutable());
        assertEquals(2, plan.operations().size());
        var write = plan.operations().getFirst();
        var read = plan.operations().get(1);
        assertEquals(0, write.fieldIndex());
        assertEquals(0, read.fieldIndex());
        assertEquals(audit.sourceSha256().get(write.sourceUnit()), write.methodSourceSha256());
        assertEquals(audit.sourceSha256().get(read.sourceUnit()), read.methodSourceSha256());
        assertEquals(dev.oreslang.compiler.ActorCriticalStateMirLayoutV1.Opcode.CHECKED_ADD_MAIL_I64,
                write.opcode());
        assertEquals(dev.oreslang.compiler.ActorCriticalStateMirLayoutV1.Opcode.COPY_I64_TO_ACTOR_MAILBOX,
                read.opcode());
        assertEquals(Ast.CriticalStateMode.WRITE, write.mode());
        assertEquals(Ast.CriticalStateMode.READ, read.mode());
        assertThrows(UnsupportedOperationException.class, () -> plan.slots().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.operations().clear());

        // A checked, source-bound slot inventory is NOT a state-loan capability
        // and must not bypass the still-closed stateful guest codegen gate.
        var blocked = assertThrows(IllegalArgumentException.class,
                physicalBuild()::compileStatelessActorGuestV1);
        assertTrue(blocked.getMessage().contains("sealed state/critical loans"));
    }

    @Test
    void stagedPlanRejectsNoncanonicalSlotsForgedOperationsAndMutableEscalation() throws Exception {
        var plan = physicalBuild().auditDeclaredActors().stagedCriticalStateMir().getFirst();
        var slot = plan.slots().getFirst();
        var write = plan.operations().getFirst();
        var read = plan.operations().get(1);

        var shifted = new ActorCriticalStateMirLayoutV1.Slot(
                1, slot.name(), slot.scalarType(), slot.mutable(), slot.literalDefault());
        assertThrows(IllegalArgumentException.class, () ->
                new ActorCriticalStateMirLayoutV1.Plan(plan.packageIdentity(), plan.namespace(),
                        plan.actor(), plan.stateUnit(), plan.stateSourceSha256(),
                        plan.manifestSha256(), List.of(shifted), plan.operations()));

        assertThrows(IllegalArgumentException.class, () ->
                new ActorCriticalStateMirLayoutV1.Plan(plan.packageIdentity(), plan.namespace(),
                        plan.actor(), plan.stateUnit(), plan.stateSourceSha256(),
                        plan.manifestSha256(), List.of(slot, slot), plan.operations()));

        var nonexistent = new ActorCriticalStateMirLayoutV1.Operation(
                write.sourceUnit(), write.methodSourceSha256(), write.method(),
                write.opcode(), write.mode(), 31);
        assertThrows(IllegalArgumentException.class, () ->
                new ActorCriticalStateMirLayoutV1.Plan(plan.packageIdentity(), plan.namespace(),
                        plan.actor(), plan.stateUnit(), plan.stateSourceSha256(),
                        plan.manifestSha256(), plan.slots(), List.of(nonexistent)));

        var immutable = new ActorCriticalStateMirLayoutV1.Slot(
                0, slot.name(), "int", false, slot.literalDefault());
        assertThrows(IllegalArgumentException.class, () ->
                new ActorCriticalStateMirLayoutV1.Plan(plan.packageIdentity(), plan.namespace(),
                        plan.actor(), plan.stateUnit(), plan.stateSourceSha256(),
                        plan.manifestSha256(), List.of(immutable), List.of(write)));

        var booleanSlot = new ActorCriticalStateMirLayoutV1.Slot(0, slot.name(), "bool", true, true);
        assertThrows(IllegalArgumentException.class, () ->
                new ActorCriticalStateMirLayoutV1.Plan(plan.packageIdentity(), plan.namespace(),
                        plan.actor(), plan.stateUnit(), plan.stateSourceSha256(),
                        plan.manifestSha256(), List.of(booleanSlot), List.of(read)));

        assertThrows(IllegalArgumentException.class, () ->
                new ActorCriticalStateMirLayoutV1.Operation(
                        read.sourceUnit(), "fake-digest", read.method(), read.opcode(),
                        read.mode(), read.fieldIndex()));
    }

    @Test
    void layoutRejectsForgedWitnessEvenWithPlausibleHashesAndOperations() throws Exception {
        var audit = physicalBuild().auditDeclaredActors();
        var parsed = new TreeMap<String, Ast.Program>();
        for (String sourceUnit : audit.sourceSha256().keySet()) {
            parsed.put(sourceUnit, Parser.parse(Files.readString(root.resolve(sourceUnit))));
        }
        var forged = new ActorContractWitness.Result(
                "incorrect-package-identity", audit.contractWitness().actors(),
                audit.contractWitness().boundMethods());
        var failure = assertThrows(IllegalArgumentException.class, () ->
                ActorCriticalStateMirLayoutV1.stage(parsed, forged, audit.sourceSha256(),
                        audit.manifestSha256(), audit.criticalOperations()));
        assertTrue(failure.getMessage().contains("witness"), failure.getMessage());
    }
}
