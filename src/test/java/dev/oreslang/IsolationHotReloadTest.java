package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorEntryContract;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class IsolationHotReloadTest {
    @Test
    void executionProfilesCoverJitAotAndHybrid() {
        assertTrue(ExecutionProfile.serverJit().guestJitAllowed());
        assertFalse(ExecutionProfile.serverJit().hostAheadOfTime());

        ExecutionProfile hybrid = ExecutionProfile.serverHybrid();
        assertTrue(hybrid.guestJitAllowed());
        assertTrue(hybrid.hostAheadOfTime());

        ExecutionProfile ios = ExecutionProfile.mobileAot(ExecutionProfile.Platform.IOS);
        assertTrue(ios.hostAheadOfTime());
        assertFalse(ios.guestJitAllowed());
        assertTrue(ios.supportsSourceHotReload());

        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionProfile(ExecutionProfile.Mode.JIT, ExecutionProfile.Platform.IOS));
    }

    @Test
    void capabilityAdmissionRejectsForbiddenApiBeforeGuestExecution() {
        var program = TypeChecker.check(Parser.parse("""
                pub routine main() => void {
                  stdio.stdout.write(process.context_id);
                }
                """));

        SecurityException denied = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(denied.getMessage().contains("PROCESS_INFO"));

        assertDoesNotThrow(() -> CapabilityChecker.check(program,
                IsolatePolicy.strictFaas().withCapabilities(IsolatePolicy.Capability.PROCESS_INFO)));
    }

    @Test
    void runtimeCapabilityCheckCannotBeBypassedByFacadeDispatch() throws Exception {
        IsolatePolicy noOutput = new IsolatePolicy(Set.of(), 64L * 1024 * 1024, 32, Duration.ofSeconds(5));
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub routine main() => void {
                  stdio.stdout.write("forbidden");
                }
                """, "denied.ores").mimeType(OresLanguage.MIME_TYPE).buildLiteral();

        RuntimeException error = assertThrows(RuntimeException.class, () -> {
            try (Context context = noOutput.restrictedContextBuilder(ExecutionProfile.serverJit()).build()) {
                context.eval(source);
            }
        });
        assertTrue(error.getMessage().contains("STDOUT"));
    }

    @Test
    void hotReloadCreatesDistinctVersionedContextsWithoutFfi() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.load("service.ores", """
                    pub routine main() => void { return; }
                    """);
            var second = hot.load("service.ores", """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertNotEquals(first.id(), second.id());
            assertNotEquals(first.sha256(), second.sha256());
            assertNull(hot.active(), "staged code must not become active before successful startup");
            assertEquals(2, hot.liveGenerations());

            first.start();
            first.activate();
            assertEquals(first.id(), hot.active().id());

            second.start();
            second.activate();
            assertEquals(second.id(), hot.active().id());
            assertTrue(first.closed(), "unpinned old generations should be reclaimed after the switch");
            assertEquals(1, hot.liveGenerations());
        }
    }

    @Test
    void hotLoadStagesWithoutRunningMainUntilExplicitStart() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot = new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.load("staged.ores", """
                    pub routine main() => void {
                      val values = arr[1];
                      val boom = values[99];
                      return;
                    }
                    """);
            assertFalse(generation.started());
            assertThrows(RuntimeException.class, generation::start);
            assertTrue(generation.closed());
        }
    }

    @Test
    void actorEntryStagesExactAbiWithoutExecutingGuestCode() {
        IsolatePolicy policy = IsolatePolicy.developer();
        String source = """
                define class Worker extends Actor<int, String, String> as
                  constructor(seed: int) {
                    val observed = seed;
                    return;
                  }

                  private helper(): int { return 1; }

                  pub receive(message: int): void {
                    val observed = self.helper();
                    return;
                  }
                end

                export entry Worker;
                """;

        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.SHARED,
                List.of(Ast.TypeRef.simple("int")),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.loadActorEntry("worker.ores", source, contract);

            assertFalse(generation.started(),
                    "actor ABI admission must not execute guest code");
            assertEquals("Worker", generation.entryExport().orElseThrow().name());
            assertEquals(
                    contract.abiDigest(),
                    generation.actorEntryAbiDigest().orElseThrow());
            assertEquals(1, hot.liveGenerations());
        }
    }

    @Test
    void genericLoadApisCannotBypassActorEntryContract() {
        IsolatePolicy policy = IsolatePolicy.developer();
        String source = """
                define class Worker extends Actor<int, String, String> as
                  pub receive(message: int): void { return; }
                end

                export entry Worker;
                """;

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            IllegalArgumentException generic = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.load("worker.ores", source));
            assertTrue(generic.getMessage().contains("loadActorEntry"));

            IllegalArgumentException entry = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadEntry("worker.ores", source));
            assertTrue(entry.getMessage().contains("loadActorEntry"));

            assertEquals(0, hot.liveGenerations(),
                    "rejected actor entry must not allocate a generation");
        }
    }

    @Test
    void actorEntryRejectsConstructorAndProtocolDriftBeforeStaging() {
        IsolatePolicy policy = IsolatePolicy.developer();
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.SHARED,
                List.of(Ast.TypeRef.simple("int")),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            IllegalArgumentException constructorDrift = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadActorEntry("worker.ores", """
                            define class Worker extends Actor<int, String, String> as
                              constructor(seed: String) { return; }
                              pub receive(message: int): void { return; }
                            end

                            export entry Worker;
                            """, contract));
            assertTrue(constructorDrift.getMessage().contains("constructor"));

            IllegalArgumentException protocolDrift = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadActorEntry("worker.ores", """
                            define class Worker extends Actor<int, int, String> as
                              constructor(seed: int) { return; }
                              pub receive(message: int): void { return; }
                            end

                            export entry Worker;
                            """, contract));
            assertTrue(protocolDrift.getMessage().contains("Actor<Message, Reply, Error>"));

            IllegalArgumentException missingProtocol = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadActorEntry("worker.ores", """
                            define actor Worker as
                              constructor(seed: int) { return; }
                              pub receive(message: int): void { return; }
                            end

                            export entry Worker;
                            """, contract));
            assertTrue(missingProtocol.getMessage().contains("explicitly declare"));

            assertEquals(0, hot.liveGenerations());
        }
    }

    @Test
    void actorEntryDigestIgnoresPrivateImplementationDetails() {
        IsolatePolicy policy = IsolatePolicy.developer();
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.SHARED,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        String v1 = """
                define class Worker extends Actor<int, String, String> as
                  private helper(): int { return 1; }
                  pub receive(message: int): void {
                    val value = self.helper();
                    return;
                  }
                end
                export entry Worker;
                """;
        String v2 = """
                define class Worker extends Actor<int, String, String> as
                  private helper(): int { return 2; }
                  pub receive(message: int): void {
                    val value = self.helper();
                    return;
                  }
                end
                export entry Worker;
                """;

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var first = hot.loadActorEntry("worker.ores", v1, contract);
            var second = hot.loadActorEntry("worker.ores", v2, contract);

            assertNotEquals(first.sha256(), second.sha256());
            assertEquals(
                    first.actorEntryAbiDigest().orElseThrow(),
                    second.actorEntryAbiDigest().orElseThrow());
            assertEquals(contract.abiDigest(), first.actorEntryAbiDigest().orElseThrow());
        }
    }

    @Test
    void isoactorEntryRequiresTrustedIsoactorDomainInPrimaryGraalIsolate() {
        IsolatePolicy policy = IsolatePolicy.developer();
        String source = """
                define class Worker extends IsoActor<int, String, String> as
                  pub receive(message: int): void { return; }
                end
                export entry Worker;
                """;
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.PRIVATE,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager wrongDomain =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> wrongDomain.loadActorEntry(
                            "worker.ores",
                            source,
                            contract));
            assertTrue(failure.getMessage().contains("TRUSTED_ISOACTOR_JIT"));
            assertEquals(0, wrongDomain.liveGenerations());
        }

        try (HotReloadManager isoDomain = new HotReloadManager(
                policy,
                policy,
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_ISOACTOR_JIT)) {
            var generation = isoDomain.loadActorEntry(
                    "worker.ores",
                    source,
                    contract);
            assertEquals(
                    HotReloadManager.ExecutionDomain.TRUSTED_ISOACTOR_JIT,
                    generation.executionDomain());
            assertFalse(generation.executionDomain().spawnedIsolate(),
                    "trusted isoactors stay in the primary Graal isolate");
        }
    }

    @Test
    void untrustedActorEntryRequiresUntrustedJitDomain() {
        IsolatePolicy supervisor = IsolatePolicy.developer();
        String source = """
                define class Worker extends UntrustedActor<int, String, String> as
                  pub receive(message: int): void { return; }
                end
                export entry Worker;
                """;
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.UNTRUSTED,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager wrongDomain =
                     new HotReloadManager(supervisor, ExecutionProfile.serverJit())) {
            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> wrongDomain.loadActorEntry(
                            "worker.ores",
                            source,
                            contract));
            assertTrue(failure.getMessage().contains("UNTRUSTED_JIT"));
            assertEquals(0, wrongDomain.liveGenerations());
        }

        try (HotReloadManager untrustedDomain = new HotReloadManager(
                supervisor,
                IsolatePolicy.untrustedActor(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.UNTRUSTED_JIT)) {
            var generation = untrustedDomain.loadActorEntry(
                    "worker.ores",
                    source,
                    contract);
            assertTrue(generation.executionDomain().spawnedIsolate(),
                    "only untrusted actor hot-load code crosses the subsequent-isolate boundary");
            assertTrue(generation.guestPolicy().adversarial());
        }
    }

    @Test
    void genericActorClassCannotPretendToHaveClosedHotLoadAbi() {
        IsolatePolicy policy = IsolatePolicy.developer();
        ActorEntryContract contract = ActorEntryContract.of(
                "Worker",
                Ast.ActorKind.SHARED,
                List.of(),
                Ast.TypeRef.simple("int"),
                Ast.TypeRef.simple("String"),
                Ast.TypeRef.simple("String"));

        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> hot.loadActorEntry("worker.ores", """
                            define class Worker<T> extends Actor<int, String, String> as
                              pub receive(message: int): void { return; }
                            end
                            export entry Worker;
                            """, contract));

            assertTrue(failure.getMessage().contains("generic"));
            assertEquals(0, hot.liveGenerations());
        }
    }

    @Test
    void explicitNonActorEntryStillUsesGenericEntryLoader() {
        IsolatePolicy policy = IsolatePolicy.developer();
        try (HotReloadManager hot =
                     new HotReloadManager(policy, ExecutionProfile.serverJit())) {
            var generation = hot.loadEntry("plugin.ores", """
                    pub fnc run() -> void { return; }
                    export entry run;
                    """);

            assertEquals("run", generation.entryExport().orElseThrow().name());
            assertTrue(generation.actorEntryAbiDigest().isEmpty());
            assertFalse(generation.started());
        }
    }

    @Test
    void hotReloadRequiresExplicitCapability() {
        assertThrows(SecurityException.class,
                () -> new HotReloadManager(IsolatePolicy.strictFaas(), ExecutionProfile.serverJit()));
    }

    @Test
    void allExplicitStructuralParameterSpellingsWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Bar {
                  marker: 'brand'
                }

                pub interface Foo extends Bar {
                  markerBrand: 'marking/branding'
                }

                fnc first(y structural Foo) => void {
                  return;
                }

                @AllowStructural(y)
                fnc second(y Foo) => void {
                  return;
                }

                fnc third(@Structural Foo y) => void {
                  return;
                }

                pub routine main() => void {
                  val branded = obj{marker: "brand", markerBrand: "marking/branding"};
                  first(branded);
                  second(branded);
                  third(branded);
                  return;
                }
                """)));
    }

    @Test
    void structuralPermissionIsNotImplicit() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Foo {
                  marker: 'brand'
                }

                fnc nominal(Foo y) => void { return; }

                pub routine main() => void {
                  val branded = obj{marker: "brand"};
                  nominal(branded);
                  return;
                }
                """)));
    }

    @Test
    void explicitClosureKeepsReceiverAndSelfCannotBeRebound() throws Exception {
        String output = run("""
                define class Box as
                  val int value;

                  pub get() => int {
                    return self.value;
                  }
                end

                pub routine main() => void {
                  val box = new Box(17);
                  val Fnc<int> callback = || -> { return box.get(); };
                  stdio.stdout.write(callback())
                }
                """);
        assertEquals("17", output);

        IllegalArgumentException extracted = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub get() => int { return 17; }
                        end
                        pub routine main() => void {
                          val box = new Box();
                          val Fnc<int> callback = box.get;
                          return;
                        }
                        """)));
        assertTrue(extracted.getMessage().contains("not a first-class value"));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub bad() => void {
                    self = new Box();
                    return;
                  }
                end
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "receiver.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
