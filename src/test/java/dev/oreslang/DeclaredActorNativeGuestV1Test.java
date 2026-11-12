package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An actual guest-execution gate for the first manifest-linked stateless
 * actor. A passing parser/binder/source test is NOT a substitute for this.
 */
final class DeclaredActorNativeGuestV1Test {
    @TempDir Path root;

    private static final String NS = "namespace counter;\n";
    private static final String CONTRACT = NS
            + "define pub contract Echo for actor as mtd echo(int n): int; end";
    private static final String ACTOR = NS
            + "declare pub actor Counter implements Echo;";
    private static final String METHOD = NS
            + "pub mtd (Counter c) echo(int n): int { return n; }";
    private static final String START = NS
            + "mtd (Counter c) on_start(): void { self.send(1); return; }";
    private static final String RECEIVE = NS
            + "mtd (Counter c) receive(ActorMail<int> mail): void { "
            + "self.send(mail.value); self.end(); return; }";
    private static final String ENTRY = NS + """
            pub async routine main(): void {
              val worker = spawn Counter();
              await worker.get_ready();
              worker.send(7);
              val startup = (await worker.outputs.next()).value.unwrap();
              val reply = (await worker.outputs.next()).value.unwrap();
              stdio.stdout.write(startup.value);
              stdio.stdout.write("|");
              stdio.stdout.write(reply.value);
              await worker.get_done();
              return;
            }
            """;

    private static final String MANIFEST = """
            schema_version = "1"
            [project]
            name = "demo"
            version = "0.1.0"
            root = "."
            [source]
            roots = ["src"]
            """;

    private void write(String path, String source) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private PackageNamespaceBuild build() throws Exception {
        Path fixture = Path.of("contracts", "declared-actor-native-guest-v1");
        for (String path : new String[] {
                ".atto.pkg.toml",
                "src/counter/contract.atto",
                "src/counter/actor.atto",
                "src/counter/method.atto",
                "src/counter/start.atto",
                "src/counter/receive.atto",
                "src/counter/main.atto" }) {
            write(path, Files.readString(fixture.resolve(path)));
        }
        return new PackageNamespaceBuild(
                OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of()));
    }

    @Test
    void realAttoGuestRunsPrivateStartupAndMailboxReceive() throws Exception {
        var packageBuild = build();
        Ast.Program verified = packageBuild.compileStatelessActorGuestV1();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String sourceText = "manifest-pinned native Atto stateless actor guest";
        String codeUnit = "declared-actor-native-v1.atto";
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnit)
                .mimeType(OresLanguage.MIME_TYPE).build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
            try (var lease = OresLanguage.openPrecheckedProgram(
                    codeUnit, sourceText, verified)) {
                context.eval(source);
            }
        }
        assertEquals("1|7", out.toString(StandardCharsets.UTF_8),
                "Atto on_start and receive must execute in the real actor scheduler");
    }

    @Test
    void twoNativeActorsPreservePerMailboxOrderingAndIndependentCompletion()
            throws Exception {
        PackageNamespaceBuild packageBuild = build();
        write("src/counter/receive.atto", NS + """
                mtd (Counter c) receive(ActorMail<int> mail): void {
                  if mail.value == 0; do
                    self.end();
                    return;
                  fi
                  self.send(mail.value);
                  return;
                }
                """);
        write("src/counter/main.atto", NS + """
                pub async routine main(): void {
                  val left = spawn Counter();
                  val right = spawn Counter();
                  await left.get_ready();
                  await right.get_ready();
                  left.send(7);
                  right.send(9);
                  left.send(8);
                  right.send(6);
                  left.send(0);
                  right.send(0);

                  val l0 = (await left.outputs.next()).value.unwrap();
                  val l1 = (await left.outputs.next()).value.unwrap();
                  val l2 = (await left.outputs.next()).value.unwrap();
                  val r0 = (await right.outputs.next()).value.unwrap();
                  val r1 = (await right.outputs.next()).value.unwrap();
                  val r2 = (await right.outputs.next()).value.unwrap();
                  stdio.stdout.write(l0.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(l1.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(l2.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(r0.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(r1.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(r2.value);
                  await left.get_done();
                  await right.get_done();
                  return;
                }
                """);
        Ast.Program verified = packageBuild.compileStatelessActorGuestV1();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String sourceText = "manifest-pinned two independent Atto actor instances";
        String codeUnit = "declared-two-actors.atto";
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnit)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            try (var lease = OresLanguage.openPrecheckedProgram(
                    codeUnit, sourceText, verified)) {
                context.eval(source);
            }
        }
        assertEquals("1|7|8|1|9|6", out.toString(StandardCharsets.UTF_8),
                "each real actor must maintain its own FIFO mailbox and output order");
    }

    @Test
    void allCanonicalUseNamespaceHeadersExecuteTheManifestLinkedAttoActor() throws Exception {
        PackageNamespaceBuild packageBuild = build();
        for (String file : new String[] {
                "contract.atto", "actor.atto", "method.atto", "start.atto",
                "receive.atto", "main.atto" }) {
            Path source = root.resolve("src/counter/" + file);
            String contents = Files.readString(source);
            assertTrue(contents.startsWith("namespace counter;")
                    || contents.startsWith("use namespace counter;"), file);
            Files.writeString(source,
                    contents.replace("namespace counter;", "use namespace counter;"));
        }
        Ast.Program verified = packageBuild.compileStatelessActorGuestV1();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String sourceText = "manifest-owned all-use-namespace actor guest";
        String codeUnit = "declared-actor-use-namespace-v1.atto";
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnit)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            try (var lease = OresLanguage.openPrecheckedProgram(
                    codeUnit, sourceText, verified)) {
                context.eval(source);
            }
        }
        assertEquals("1|7", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void attachedStateCannotBeRecastAsLegacyActorFields() throws Exception {
        PackageNamespaceBuild packageBuild = build();
        write("src/counter/state.atto", NS
                + "define state on actor Counter as { let int total : 0; }");
        var error = assertThrows(IllegalArgumentException.class,
                packageBuild::compileStatelessActorGuestV1);
        assertTrue(error.getMessage().contains("sealed state/critical loans"),
                error.getMessage());
    }

    @Test
    void missingReceiveFailsBeforeGuestAdmission() throws Exception {
        PackageNamespaceBuild packageBuild = build();
        Files.delete(root.resolve("src/counter/receive.atto"));
        var error = assertThrows(IllegalArgumentException.class,
                packageBuild::compileStatelessActorGuestV1);
        assertTrue(error.getMessage().contains("exactly one private receive"),
                error.getMessage());
    }

    @Test
    void actorContractMismatchFailsBeforeActorLowering() throws Exception {
        PackageNamespaceBuild packageBuild = build();
        write("src/counter/method.atto", NS
                + "pub mtd (Counter c) echo(bool n): int { return 0; }");
        var error = assertThrows(IllegalArgumentException.class,
                packageBuild::compileStatelessActorGuestV1);
        assertTrue(error.getMessage().contains("signature mismatch"),
                error.getMessage());
    }

    @Test
    void rawCallerSelectedMapsStillCannotGenerateDeclaredActors() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("src/counter/actor.atto", ACTOR);
        raw.put("src/counter/method.atto", METHOD);
        var error = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(raw));
        assertTrue(error.getMessage().contains("DECLARED_ACTOR_MANIFEST_GATE_REQUIRED"),
                error.getMessage());
    }

    @Test
    void uncheckedReceiverAliasCannotBecomeActorContext() throws Exception {
        PackageNamespaceBuild packageBuild = build();
        write("src/counter/receive.atto", NS
                + "mtd (Counter c) receive(ActorMail<int> mail): void { "
                + "c.end(); return; }");
        assertThrows(IllegalArgumentException.class,
                packageBuild::compileStatelessActorGuestV1,
                "external receiver aliases have no authority until checked binding lowering");
    }

    @Test
    void actorStateIntrinsicRemainsBlockedUntilRealCriticalLoanMir() throws Exception {
        PackageNamespaceBuild packageBuild = build();
        write("src/counter/receive.atto", NS
                + "mtd (Counter c) receive(ActorMail<int> mail): void { "
                + "self.handle_state(critical-write |s| -> { s.total += mail.value; }); "
                + "self.end(); return; }");
        assertThrows(IllegalArgumentException.class,
                packageBuild::compileStatelessActorGuestV1,
                "unsupported critical-write cannot silently compile as a normal method");
    }
}
