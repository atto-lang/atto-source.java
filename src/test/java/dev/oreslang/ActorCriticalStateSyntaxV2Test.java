package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
}
