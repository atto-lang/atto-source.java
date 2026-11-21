package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ActorMethodIndex;
import dev.oreslang.compiler.ExternalActorCriticalStateCheck;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile-time cross-file actor sealed state loan admission only.
 * The tests do not assert a runnable actor, scheduler permit or LLVM output.
 */
final class ExternalActorCriticalStateCheckTest {
    @TempDir Path root;

    private static final String NS = "namespace foo;\n";
    private static final String ACTOR = "use namespace foo;\n"
            + "declare pub actor Counter implements CounterApi;";
    private static final String CONTRACT = NS
            + "define pub contract CounterApi for actor as "
            + "mtd increment(): void; "
            + "mtd get_count(): int; end";
    private static final String STATE = NS
            + "define state on actor Counter as { "
            + "let int count : 0; val bool ready : true; }";
    private static final String READ = NS
            + "pub mtd (Counter c) get_count(): int { "
            + "return c.handle_state(critical-read |s| -> s.count); }";
    private static final String WRITE = NS
            + "pub mtd (Counter c) increment(): void { "
            + "c.handle_state(critical-write |s| -> { "
            + "s.count = s.count + 1; }); return; }";

    private void write(String relative, String source) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, source);
    }

    private PackageNamespaceBuild setup() throws IOException {
        write(".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "state-loan"
                version = "1.0"
                root = "."
                [source]
                roots = ["src"]
                """);
        write("src/actors/counter.atto", ACTOR);
        write("src/contracts/counter.atto", CONTRACT);
        write("src/state/counter.atto", STATE);
        write("src/methods/read.atto", READ);
        write("src/methods/write.atto", WRITE);
        return new PackageNamespaceBuild(
                OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of()));
    }

    private static ActorMethodIndex.MethodKey method(String name) {
        return new ActorMethodIndex.MethodKey(
                new ActorMethodIndex.ActorKey("state-loan@1.0", "foo", "Counter"),
                name, 0);
    }

    private static void expect(IllegalArgumentException bad, String message) {
        assertTrue(bad.getMessage().contains(message), bad.getMessage());
    }

    @Test
    void parserRetainsLexicalReadWriteModesWithoutLoweringThem() {
        var read = Parser.parse(READ);
        var write = Parser.parse(WRITE);
        var r = assertInstanceOf(Ast.ExternalActorMethodDecl.class,
                read.modules().getFirst().declarations().getFirst());
        var w = assertInstanceOf(Ast.ExternalActorMethodDecl.class,
                write.modules().getFirst().declarations().getFirst());
        var readCall = assertInstanceOf(Ast.CallExpr.class,
                assertInstanceOf(Ast.ReturnStmt.class, r.body().getFirst()).value());
        var writeCall = assertInstanceOf(Ast.CallExpr.class,
                assertInstanceOf(Ast.ExprStmt.class, w.body().getFirst()).expression());
        assertEquals("actor_state_critical_read_unlowered",
                assertInstanceOf(Ast.RuntimeCallExpr.class,
                        readCall.arguments().getFirst()).operation());
        assertEquals("actor_state_critical_write_unlowered",
                assertInstanceOf(Ast.RuntimeCallExpr.class,
                        writeCall.arguments().getFirst()).operation());
    }

    @Test
    void realFiveFileNamespaceProvesSealedReadAndWriteShapes()
            throws IOException {
        var result = setup().auditCriticalStateBodies();
        assertEquals(5, result.actorAudit().sourceSha256().size());
        assertEquals(2, result.checkedLoans().size());
        var read = result.checkedLoans().get(method("get_count"));
        assertNotNull(read);
        assertEquals("src/methods/read.atto", read.methodUnit());
        assertEquals("src/state/counter.atto", read.stateUnit());
        assertEquals("count", read.field());
        assertEquals("int", read.fieldType());
        assertEquals(ExternalActorCriticalStateCheck.Kind.READ, read.kind());
        var write = result.checkedLoans().get(method("increment"));
        assertNotNull(write);
        assertEquals(ExternalActorCriticalStateCheck.Kind.WRITE_INCREMENT_UNLOWERED,
                write.kind());
        assertEquals(read.actorUnit(), write.actorUnit());
    }

    @Test
    void writeLiteralHasDistinctCheckedShape() throws IOException {
        var build = setup();
        write("src/methods/write.atto", NS
                + "pub mtd (Counter c) increment(): void { "
                + "c.handle_state(critical-write |s| -> { s.count = 4; }); "
                + "return; }");
        var found = build.auditCriticalStateBodies().checkedLoans().get(method("increment"));
        assertEquals(ExternalActorCriticalStateCheck.Kind.WRITE_LITERAL, found.kind());
    }

    @Test
    void criticalReadCannotMutateActorState() throws IOException {
        var build = setup();
        write("src/methods/read.atto", NS
                + "pub mtd (Counter c) get_count(): int { "
                + "return c.handle_state(critical-read |s| -> { s.count = 1; }); }");
        expect(assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateBodies), "READ_SHAPE_UNSUPPORTED");
    }

    @Test
    void criticalWriteCannotMutateImmutableValState() throws IOException {
        var build = setup();
        write("src/methods/write.atto", NS
                + "pub mtd (Counter c) increment(): void { "
                + "c.handle_state(critical-write |s| -> { s.ready = false; }); "
                + "return; }");
        expect(assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateBodies), "IMMUTABLE_FIELD");
    }

    @Test
    void unknownReadFieldFailsAgainstSealedLayout() throws IOException {
        var build = setup();
        write("src/methods/read.atto", READ.replace("s.count", "s.missing"));
        expect(assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateBodies), "UNKNOWN_FIELD");
    }

    @Test
    void unboundStateAliasCannotReadAnActorField() throws IOException {
        var build = setup();
        write("src/methods/read.atto", READ.replace("s.count", "other.count"));
        expect(assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateBodies), "UNKNOWN_FIELD");
    }

    @Test
    void readReturnTypeMustMatchSealedFieldType() throws IOException {
        var build = setup();
        write("src/contracts/counter.atto", CONTRACT.replace("get_count(): int",
                "get_count(): bool"));
        write("src/methods/read.atto", READ.replace("get_count(): int",
                "get_count(): bool"));
        expect(assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateBodies), "RETURN_TYPE_MISMATCH");
    }

    @Test
    void writeCannotCaptureAnotherStateFieldAsArithmeticOperand()
            throws IOException {
        var build = setup();
        write("src/methods/write.atto", WRITE.replace("s.count + 1", "s.ready + 1"));
        expect(assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateBodies), "WRITE_VALUE_UNSUPPORTED");
    }

    @Test
    void callbacksCannotAcceptTwoStateBindings() {
        expect(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(READ.replace("|s|", "|s, x|"))),
                "exactly one lexical");
    }

    @Test
    void stateLoanWithoutModeIsNotAnOrdinaryPublicMethodCall() {
        expect(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(READ.replace("critical-read ", ""))),
                "requires critical-read or critical-write");
    }

    @Test
    void stateLoanRejectsExtraCallbackArguments() {
        expect(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(READ.replace("|s| -> s.count", "|s| -> s.count, 1"))),
                "one critical callback");
    }

    @Test
    void malformedStateLoanReadWriteHyphenMustBeAdjacent() {
        expect(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(READ.replace("critical-read", "critical - read"))),
                "requires critical-read or critical-write");
    }

    @Test
    void missingStateAttachmentNeverGrantsBorrowedStateAuthority()
            throws IOException {
        var build = setup();
        Files.delete(root.resolve("src/state/counter.atto"));
        expect(assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateBodies), "MISSING_SCHEMA");
    }

    @Test
    void foreignNamespaceCannotDonateAMethodOrState() throws IOException {
        var build = setup();
        write("src/methods/read.atto", READ.replace("namespace foo;", "namespace alien;"));
        expect(assertThrows(IllegalArgumentException.class,
                build::auditCriticalStateBodies), "has no nominal actor");
    }

    @Test
    void nonExecutablePackageBuildStillBlocksAfterValidCriticalLoanAudit()
            throws IOException {
        var build = setup();
        assertEquals(2, build.auditCriticalStateBodies().checkedLoans().size());
        expect(assertThrows(IllegalArgumentException.class, build::build),
                "DECLARED_ACTOR_CODEGEN_UNSUPPORTED");
    }

    @Test
    void criticalStateAuditIsImmutableAndSourceEditInvalidatesFingerprint()
            throws IOException {
        var build = setup();
        var initial = build.auditCriticalStateBodies();
        assertThrows(UnsupportedOperationException.class,
                () -> initial.checkedLoans().clear());
        var digest = initial.actorAudit().descriptors().values().iterator()
                .next().descriptorSha256();
        write("src/state/counter.atto", STATE.replace("count : 0", "count : 5"));
        var changed = build.auditCriticalStateBodies();
        assertNotEquals(digest, changed.actorAudit().descriptors().values()
                .iterator().next().descriptorSha256());
        assertEquals(initial.checkedLoans().keySet(), changed.checkedLoans().keySet());
    }
}
