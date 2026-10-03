package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.runtime.OresMutex;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SymbolLifecycleTest {
    private static final String UUID_KEY = "5d60d832-fc6a-4fa2-a551-1448c2df6bf2";

    @Test
    void colonAndScopedFactoriesTypecheckAsCopySymbols() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module symbols
                  pub fnc same() => bool {
                    val Symbol literal = :connected;
                    val Symbol compat = new Symbol("1d46c11d-580c-4ee0-b5ff-cb3bbd83d596");
                    val Symbol process = Symbol.process("connected");
                    val Symbol stable = Symbol.stable("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");
                    return literal == process;
                  }
                end
                """));

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt first = (Ast.BindingStmt) fn.body().getFirst();
        Ast.LiteralExpr literal = (Ast.LiteralExpr) first.initializer();
        assertEquals("connected", ((Ast.Symbol) literal.value()).name());
    }

    @Test
    void languageRuntimeCanonicalizesLiteralAndProcessFactory() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, """
                pub fnc main() => void {
                  val Symbol literal = :ready;
                  val Symbol dynamic = Symbol.process("ready");
                  val Symbol stable =
                      new Symbol("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");

                  if (literal == dynamic) {
                    stdio.println("same-process-symbol");
                  }
                  stdio.println(stable);
                  return;
                }
                """, "symbols.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("same-process-symbol"));
        assertTrue(rendered.contains("Symbol.stable"));
    }

    @Test
    void symbolLocalIsRejectedOutsideActorExecutionByStaticAndRuntimeChecks() {
        IllegalArgumentException staticFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module symbols
                          pub fnc bad() => Symbol {
                            return Symbol.local("request");
                          }
                        end
                        """)));
        assertTrue(staticFailure.getMessage().contains("actor-local"));

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            assertThrows(
                    IllegalStateException.class,
                    () -> OresSymbol.local("off-actor", runtime, IsolatePolicy.developer()));
        }
    }

    @Test
    void symbolLocalTypechecksInsideActorExecution() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module actors
                  pub actor fnc use_local() => void {
                    val Symbol local = Symbol.local("request-local");
                    if (local == local) {
                      return;
                    }
                    return;
                  }
                end
                """));
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void wellKnownIteratorUsesCanonicalProcessSymbolIdentity() {
        OresSymbol iterator = OresSymbol.wellKnown("iterator", IsolatePolicy.developer());
        OresSymbol viaProcess = OresSymbol.process("Symbol.iterator");

        assertSame(iterator, viaProcess);
        assertEquals(OresSymbol.Scope.PROCESS, iterator.scope());
        assertEquals("Symbol.iterator", iterator.key());
        assertEquals("Symbol.iterator", iterator.toString());

        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module protocol
                  pub fnc iterator_symbol() => Symbol {
                    return Symbol.iterator;
                  }
                end
                """));
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void processAndStableSymbolsAreStronglyCanonicalInTheirDomains() {
        OresSymbol first = OresSymbol.process("shared-key");
        OresSymbol again = OresSymbol.process("shared-key");
        OresSymbol other = OresSymbol.process("other-key");
        OresSymbol stable = OresSymbol.stable(UUID_KEY);
        OresSymbol stableAgain = OresSymbol.stable(UUID_KEY.toUpperCase());

        assertSame(first, again);
        assertEquals(OresSymbol.Scope.PROCESS, first.scope());
        assertEquals(first.id(), again.id());
        assertNotEquals(first.id(), other.id());
        assertTrue(other.id() > first.id());

        assertSame(stable, stableAgain);
        assertEquals(OresSymbol.Scope.STABLE, stable.scope());
        assertNotEquals(first, stable);
        assertTrue(first.processStable());
        assertTrue(stable.processStable());
        assertTrue(first.sendable());
        assertTrue(stable.sendable());
    }

    @Test
    void processAndStableInterningRemainCanonicalUnderConcurrency() throws Exception {
        OresSymbol expectedProcess = OresSymbol.process("concurrent-process-key");
        OresSymbol expectedStable =
                OresSymbol.stable("93c1d30c-e89f-47ca-bca5-2f9208f21701");

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<OresSymbol>> processResults = new ArrayList<>();
            List<Future<OresSymbol>> stableResults = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                processResults.add(pool.submit(
                        () -> OresSymbol.process("concurrent-process-key")));
                stableResults.add(pool.submit(
                        () -> OresSymbol.stable("93c1d30c-e89f-47ca-bca5-2f9208f21701")));
            }
            for (Future<OresSymbol> result : processResults) {
                assertSame(expectedProcess, result.get(2, TimeUnit.SECONDS));
            }
            for (Future<OresSymbol> result : stableResults) {
                assertSame(expectedStable, result.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void capabilityAdmissionPreRegistersAllSourceLiteralsBeforeExecution() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module protocol
                  pub fnc never_called() => Symbol {
                    return :wire_ready_before_execution;
                  }
                end
                """));

        OresSymbol.Wire wire = new OresSymbol.Wire(
                OresSymbol.Scope.PROCESS,
                "wire_ready_before_execution");
        assertThrows(
                IllegalArgumentException.class,
                () -> OresSymbol.fromWire(wire, IsolatePolicy.developer()));

        CapabilityChecker.check(program, IsolatePolicy.developer());

        OresSymbol decoded = OresSymbol.fromWire(wire, IsolatePolicy.developer());
        assertSame(
                OresSymbol.process("wire_ready_before_execution"),
                decoded);
    }

    @Test
    void compilerLiteralAndDynamicProcessFactoryShareCanonicalIdentity() {
        OresSymbol dynamicFirst = OresSymbol.process("literal-shared-key");
        OresSymbol literalAfter = OresSymbol.processLiteral(
                "literal-shared-key", IsolatePolicy.developer());
        OresSymbol literalFirst = OresSymbol.processLiteral(
                "literal-first-key", IsolatePolicy.developer());
        OresSymbol dynamicAfter = OresSymbol.process("literal-first-key");

        assertSame(dynamicFirst, literalAfter);
        assertSame(literalFirst, dynamicAfter);
        assertEquals(dynamicFirst.id(), literalAfter.id());
        assertEquals(literalFirst.id(), dynamicAfter.id());
        assertTrue(OresSymbol.RESERVED_LITERAL_PROCESS_SYMBOLS > 0);
        assertTrue(OresSymbol.MAX_DYNAMIC_PROCESS_SYMBOLS < OresSymbol.MAX_PROCESS_SYMBOLS);
    }

    @Test
    void trustedActorTransportPreservesProcessAndStableSymbolIdentity() throws Exception {
        OresSymbol process = OresSymbol.process("trusted-transport");
        OresSymbol stable =
                OresSymbol.stable("64bcc7a9-cef3-4324-8dd6-91a06120963e");
        AtomicReference<Object> observed = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<Object> target = runtime.spawnPrivateTrusted(
                    context -> (message, turn) -> {
                        observed.set(message);
                        delivered.countDown();
                    });

            target.send(List.of(process, stable));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
        }

        List<?> received = assertInstanceOf(List.class, observed.get());
        assertSame(process, received.get(0));
        assertSame(stable, received.get(1));
    }

    @Test
    void localSymbolsAreCanonicalPerActorDistinctAcrossActorsAndNonSendable() throws Exception {
        AtomicReference<OresSymbol> firstActorSymbol = new AtomicReference<>();
        AtomicReference<OresSymbol> secondActorSymbol = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(2);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<Object> target = runtime.spawnSharedTrusted(
                    context -> (message, turn) -> {
                        throw new AssertionError("local Symbol must never be delivered");
                    });

            ActorRuntime.ActorRef<String> actorA = runtime.spawnPrivateTrusted(
                    context -> (message, turn) -> {
                        try {
                            OresSymbol first = OresSymbol.local(
                                    "request-local", turn.runtime(), turn.policy());
                            OresSymbol again = OresSymbol.local(
                                    "request-local", turn.runtime(), turn.policy());
                            assertSame(first, again);
                            assertEquals(OresSymbol.Scope.LOCAL, first.scope());
                            assertFalse(first.sendable());
                            assertThrows(IllegalStateException.class, first::toWire);
                            assertThrows(IllegalArgumentException.class, () -> target.send(first));
                            firstActorSymbol.set(first);
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            finished.countDown();
                        }
                    });

            ActorRuntime.ActorRef<String> actorB = runtime.spawnPrivateTrusted(
                    context -> (message, turn) -> {
                        try {
                            secondActorSymbol.set(OresSymbol.local(
                                    "request-local", turn.runtime(), turn.policy()));
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            finished.countDown();
                        }
                    });

            actorA.send("go");
            actorB.send("go");
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        }

        assertNotNull(firstActorSymbol.get());
        assertNotNull(secondActorSymbol.get());
        assertNotSame(firstActorSymbol.get(), secondActorSymbol.get());
        assertNotEquals(firstActorSymbol.get(), secondActorSymbol.get());
        assertNotEquals(firstActorSymbol.get().domainId(), secondActorSymbol.get().domainId());
    }

    @Test
    void privateActorMayRetainItsOwnLocalSymbolAcrossTurns() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivate(
                    factoryContext -> {
                        OresSymbol retained = OresSymbol.local(
                                "retained-local",
                                factoryContext.runtime(),
                                factoryContext.policy());
                        return (message, turn) -> {
                            OresSymbol again = OresSymbol.local(
                                    "retained-local",
                                    turn.runtime(),
                                    turn.policy());
                            if (retained != again) {
                                throw new AssertionError("retained LOCAL Symbol lost actor canonicality");
                            }
                            turn.self().stop();
                        };
                    });

            actor.send("verify");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void wireFormatUsesScopeAndKeyNeverProcessIds() {
        OresSymbol process = OresSymbol.process("wire-key");
        OresSymbol stable = OresSymbol.stable(UUID_KEY);

        OresSymbol.Wire processWire = process.toWire();
        OresSymbol.Wire stableWire = stable.toWire();

        assertEquals(OresSymbol.Scope.PROCESS, processWire.scope());
        assertEquals("wire-key", processWire.key());
        assertEquals(OresSymbol.Scope.STABLE, stableWire.scope());
        assertEquals(UUID_KEY, stableWire.key());
        assertSame(process, OresSymbol.fromWire(processWire, IsolatePolicy.developer()));
        assertSame(stable, OresSymbol.fromWire(stableWire, IsolatePolicy.developer()));

        int processEntriesBefore = OresSymbol.processRegistryEntries();
        OresSymbol.Wire unknownProcess =
                new OresSymbol.Wire(OresSymbol.Scope.PROCESS, "wire-not-preinterned");
        assertThrows(
                IllegalArgumentException.class,
                () -> OresSymbol.fromWire(unknownProcess, IsolatePolicy.developer()));
        assertEquals(
                processEntriesBefore,
                OresSymbol.processRegistryEntries(),
                "ordinary wire decode must not intern attacker-controlled process keys");

        OresSymbol admitted =
                OresSymbol.fromTrustedWire(unknownProcess, IsolatePolicy.developer());
        assertSame(admitted, OresSymbol.fromWire(unknownProcess, IsolatePolicy.developer()));

        int stableEntriesBefore = OresSymbol.stableRegistryEntries();
        OresSymbol.Wire unknownStable = new OresSymbol.Wire(
                OresSymbol.Scope.STABLE,
                "b2c45229-4e99-4ebd-a551-c1331fe40ef3");
        assertThrows(
                IllegalArgumentException.class,
                () -> OresSymbol.fromWire(unknownStable, IsolatePolicy.developer()));
        assertEquals(stableEntriesBefore, OresSymbol.stableRegistryEntries());
        OresSymbol trustedStable =
                OresSymbol.fromTrustedWire(unknownStable, IsolatePolicy.developer());
        assertSame(trustedStable, OresSymbol.fromWire(unknownStable, IsolatePolicy.developer()));

        assertThrows(
                SecurityException.class,
                () -> OresSymbol.fromWire(processWire, IsolatePolicy.strictFaas()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OresSymbol.Wire(OresSymbol.Scope.LOCAL, "forbidden"));
    }

    @Test
    void symbolBuiltinNamespaceCannotBeShadowedIncludingGenericScopes() {
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module Symbol
                          pub fnc x() => int { return 1; }
                        end
                        """))).getMessage().contains("reserved"));

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Symbol as
                          end
                        end
                        """))).getMessage().contains("reserved"));

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          pub fnc x() => int {
                            val int Symbol = 1;
                            return Symbol;
                          }
                        end
                        """))).getMessage().contains("reserved"));

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          pub fnc identity<Symbol>(Symbol value) => Symbol {
                            return value;
                          }
                        end
                        """))).getMessage().contains("reserved"));
    }

    @Test
    void adversarialPoliciesCannotCreateDecodeOrReceiveSymbols() {
        Ast.Program literalProgram = TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc tag() => Symbol {
                    return :secret;
                  }
                end
                """));
        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(literalProgram, IsolatePolicy.strictFaas()));

        Ast.Program factoryProgram = TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc tag() => Symbol {
                    return Symbol.process("secret");
                  }
                end
                """));
        assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(factoryProgram, IsolatePolicy.strictFaas()));

        assertThrows(
                SecurityException.class,
                () -> OresSymbol.process("secret", IsolatePolicy.strictFaas()));
        assertThrows(
                SecurityException.class,
                () -> OresSymbol.stable(UUID_KEY, IsolatePolicy.strictFaas()));

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<Object> restricted = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) -> { });

            SecurityException transportDenied = assertThrows(
                    SecurityException.class,
                    () -> restricted.send(List.of(
                            "safe",
                            List.of(OresSymbol.process("nested-secret")))));
            assertTrue(transportDenied.getMessage().contains("Symbol"));
        }
    }

    @Test
    void adversarialPolicyCannotReenableSharedMutexWithCapabilityFlag() {
        IsolatePolicy adversarialShared = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.SHARED_MEMORY);
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module shared_state
                  fnc use(SharedMutex<int> value) => void {
                    return;
                  }
                end
                """));

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, adversarialShared));
        assertTrue(denied.getMessage().contains("shared mutable"));
    }

    @Test
    void adversarialActorCannotCreateSyncCellEvenWithSharedMemoryCapability() throws Exception {
        IsolatePolicy adversarialShared = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.SHARED_MEMORY);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<String> target = runtime.spawnShared(
                    adversarialShared,
                    factoryContext -> (message, turn) -> turn.runtime().syncCell(1));

            target.send("go");
            assertTrue(target.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, target.failure().orElseThrow());
        }
    }

    @Test
    void adversarialSharedActorCannotReceiveLiveSharedMutableCapability() {
        IsolatePolicy adversarialShared = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.SHARED_MEMORY);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<Object> target = runtime.spawnShared(
                    adversarialShared,
                    factoryContext -> (message, turn) -> { });
            OresMutex.Shared<Object> shared =
                    OresMutex.shared((Object) OresSymbol.process("shared-symbol"));

            SecurityException denied =
                    assertThrows(SecurityException.class, () -> target.send(shared));
            assertTrue(denied.getMessage().contains("adversarial"));
        }
    }

    @Test
    void adversarialActorCannotBypassSymbolPolicyWithPermissiveArgument() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<String> restricted = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) ->
                            OresSymbol.process("policy-bypass", IsolatePolicy.developer()));

            restricted.send("go");
            assertTrue(restricted.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, restricted.failure().orElseThrow());
        }
    }

    @Test
    void keysAndStableIdsAreStrictlyValidatedAndBounded() {
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process("   "));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process("bad\nkey"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OresSymbol.process("x".repeat(OresSymbol.MAX_KEY_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.stable("not-a-uuid"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OresSymbol.stable("5d60d832-fc6a-4fa2-a551-1448c2df6bf"));
        assertThrows(
                IllegalArgumentException.class,
                () -> OresSymbol.processLiteral("not-a-source-literal!", IsolatePolicy.developer()));
        assertThrows(
                IllegalArgumentException.class,
                () -> OresSymbol.processLiteral(
                        "x".repeat(OresSymbol.MAX_LITERAL_NAME_LENGTH + 1),
                        IsolatePolicy.developer()));
    }
}
