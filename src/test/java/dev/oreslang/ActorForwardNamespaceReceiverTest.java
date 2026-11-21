package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multiple actor forward declarations are signatures, not runtime actor
 * instances or extensions to the compiler-owned mailbox/state authority.
 */
final class ActorForwardNamespaceReceiverTest {
    @Test
    void twoActorsShareFileAndNamespaceButNotTheirMethods() {
        var parsed = Parser.parse("""
                use namespace two_workers;
                declare actor Foo1;
                declare actor Foo2;

                define actor Foo1 as
                  mtd increment(int x): int { return x + 1; }
                  receive(ActorMail<String> mail): void {
                    self.send(self.increment(40));
                    self.end();
                    return;
                  }
                end

                define actor Foo2 as
                  mtd double_value(int x): int { return x + x; }
                  receive(ActorMail<String> mail): void {
                    self.send(self.double_value(21));
                    self.end();
                    return;
                  }
                end
                """);
        assertEquals("two_workers", parsed.namespace());
        var declarations = parsed.modules().getFirst().declarations();
        assertEquals(2, declarations.size(), "forwards do not become executable declarations");
        var first = assertInstanceOf(Ast.ClassDecl.class, declarations.get(0));
        var second = assertInstanceOf(Ast.ClassDecl.class, declarations.get(1));
        assertEquals(Ast.ActorKind.SHARED, first.actorKind());
        assertEquals(Ast.ActorKind.SHARED, second.actorKind());
        assertEquals("Foo1", first.name());
        assertEquals("Foo2", second.name());
        assertEquals("increment", first.methods().getFirst().name());
        assertEquals("double_value", second.methods().getFirst().name());
        assertFalse(first.methods().stream().anyMatch(m -> m.name().equals("double_value")));
        assertFalse(second.methods().stream().anyMatch(m -> m.name().equals("increment")));
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
    }

    @Test
    void eachModuleOwnsItsForwardAndActorIdentity() {
        var program = Parser.parse("""
                use namespace actor_services;
                define module left as
                  declare actor Worker;
                  define actor Worker as
                    mtd left_only(): int { return 21; }
                  end
                end
                define module right as
                  declare actor Worker;
                  define actor Worker as
                    mtd right_only(): int { return 42; }
                  end
                end
                """);
        assertEquals("actor_services", program.namespace());
        assertEquals(2, program.modules().size());
        for (var module : program.modules()) {
            assertEquals(1, module.declarations().size());
            assertEquals("Worker", ((Ast.ClassDecl) module.declarations().getFirst()).name());
        }
        var left = (Ast.ClassDecl) program.modules().get(0).declarations().getFirst();
        var right = (Ast.ClassDecl) program.modules().get(1).declarations().getFirst();
        assertEquals("left_only", left.methods().getFirst().name());
        assertEquals("right_only", right.methods().getFirst().name());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
    }

    @Test
    void supportsIsolatedActorForwardWithExactKind() {
        var program = Parser.parse("""
                use namespace isolated;
                declare isoactor PrivateOne;
                define isoactor PrivateOne as
                  mtd answer(): int { return 42; }
                end
                """);
        var actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.PRIVATE, actor.actorKind());
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
    }

    @Test
    void crossScopeForwardsDoNotImplicitlyBindEvenWithSameNamespace() {
        reject("""
                use namespace wrong_scope;
                define module first as
                  declare actor Worker;
                end
                define module second as
                  define actor Worker as end
                end
                """, "unresolved forward");
        reject("""
                use namespace wrong_scope;
                declare actor Worker;
                define module nested as
                  define actor Worker as end
                end
                """, "unresolved forward");
    }

    @Test
    void rejectsDuplicateMissingAndMismatchedActorForwards() {
        reject("declare actor Foo; declare actor Foo; define actor Foo as end",
                "duplicate forward");
        reject("declare actor Missing; define actor Other as end",
                "unresolved forward");
        reject("declare actor Worker; define isoactor Worker as end",
                "kind mismatch");
        reject("declare isoactor Worker; define actor Worker as end",
                "kind mismatch");
        reject("declare actor Worker; define class Worker as end",
                "value declaration");
        reject("declare actor Worker; define type Worker = int;",
                "kind mismatch");
        reject("declare pub actor Worker; define actor Worker as end",
                "visibility mismatch");
        reject("declare actor Worker; define pub actor Worker as end",
                "visibility mismatch");
        reject("declare actor Worker<T>; define actor Worker as end",
                "generic parameter mismatch");
        reject("declare actor Worker; define untrusted actor Worker as end",
                "kind mismatch");
    }

    @Test
    void rejectsExternalTypedReceiverMethodsOnActorsToProtectSealedState() {
        reject("""
                use namespace guarded;
                declare actor Foo1;
                declare actor Foo2;
                define actor Foo1 as end
                define actor Foo2 as end
                mtd (mut self: Foo1) steal_state(): int { return 42; }
                """, "sealed state");
        reject("""
                declare actor Foo1;
                define actor Foo1 as
                  mtd handle_state(): void { return; }
                end
                """, "compiler-owned");
    }

    @Test
    void sameNamespaceDoesNotPermitSiblingMethodLookupOrActorFieldAccess() {
        var siblingMethod = Parser.parse("""
                use namespace two_workers;
                declare actor Foo1;
                declare actor Foo2;
                define actor Foo1 as
                  mtd first_only(): int { return 1; }
                  receive(ActorMail<String> mail): void {
                    self.second_only();
                    self.end();
                    return;
                  }
                end
                define actor Foo2 as
                  mtd second_only(): int { return 2; }
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(siblingMethod));

        var foreignField = Parser.parse("""
                declare actor Foo1;
                declare actor Foo2;
                define actor Foo1 as
                  mtd peek_other(Foo2 other): int { return other.secret; }
                end
                define actor Foo2 as
                  let int secret;
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(foreignField));
    }

    @Test
    void duplicateActorDefinitionsDoNotSatisfyOneForwardAmbiguously() {
        reject("""
                declare actor Foo1;
                define actor Foo1 as end
                define actor Foo1 as end
                """, "ambiguous forward");
    }

    @Test
    void declaredActorsKeepDeclarationOrderAndDispatchSlotsWhenMethodsAreInterleaved() {
        Ast.Program program = Parser.parse("""
                use namespace workers;
                mtd (Foo2 self) foo(int x): int { return x + 2; }
                declare actor Foo1;
                mtd (Foo2 self) foo(): int { return 42; }
                declare actor Foo2;
                mtd (Foo1 self) foo(): int { return 41; }
                mtd (Foo1 self) foo(int x): int { return x + 1; }
                """);
        var declarations = program.modules().getFirst().declarations();
        assertEquals(2, declarations.size());
        var foo1 = assertInstanceOf(Ast.ClassDecl.class, declarations.get(0));
        var foo2 = assertInstanceOf(Ast.ClassDecl.class, declarations.get(1));
        assertEquals("Foo1", foo1.name(), "nominal ordering follows declare, not first mtd");
        assertEquals("Foo2", foo2.name());
        assertEquals(2, foo1.methods().size());
        assertEquals(2, foo2.methods().size());
        assertEquals(java.util.Set.of(0, 1),
                foo1.methods().stream().map(Ast.MethodDecl::arity)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of(0, 1),
                foo2.methods().stream().map(Ast.MethodDecl::arity)
                        .collect(java.util.stream.Collectors.toSet()));
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void deferredSelectCannotUpgradeReadonlyActorSelf() {
        Ast.Program program = Parser.parse("""
                declare actor Worker;
                mtd (Worker self) illegal(): void {
                  val Channel<int> gate = Channel.new<int>(0);
                  nb select over
                    when readch gate: int value -> {
                      self.end();
                    }
                  end
                  return;
                }
                """);
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(program));
        assertTrue(rejected.getMessage().contains("exclusive mutable actor self"),
                rejected.getMessage());
    }

    @Test
    void actorForwardKindAndVisibilitySurviveTypeFirstCompletion() {
        var program = Parser.parse("""
                use namespace workers;
                declare pub actor PublicWorker;
                declare isoactor PrivateWorker;
                pub mtd (PublicWorker self) status(): int { return 41; }
                mtd (PrivateWorker self) status(): int { return 42; }
                """);
        var actors = program.modules().getFirst().declarations();
        assertEquals(2, actors.size());
        var shared = assertInstanceOf(Ast.ClassDecl.class, actors.get(0));
        var isolated = assertInstanceOf(Ast.ClassDecl.class, actors.get(1));
        assertEquals("PublicWorker", shared.name());
        assertEquals(Ast.ActorKind.SHARED, shared.actorKind());
        assertEquals(Ast.Visibility.PUBLIC, shared.visibility());
        assertEquals("PrivateWorker", isolated.name());
        assertEquals(Ast.ActorKind.PRIVATE, isolated.actorKind());
        assertEquals(Ast.Visibility.PRIVATE, isolated.visibility());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void actorMethodMutabilityCannotCreateDistinctOverloadSlot() {
        reject("""
                declare actor Foo1;
                mtd (Foo1 self) foo(int x): int { return x; }
                mtd (Foo1 mut self) foo(int x): int { return x + 1; }
                """, "duplicate receiver method");
        reject("""
                declare actor Foo1;
                declare actor Foo1;
                mtd (Foo1 self) foo(): int { return 42; }
                """, "duplicate actor forward");
    }

    @Test
    void manyActorsWithSameMethodNameStaySeparate() {
        var source = new StringBuilder("use namespace workers;\n");
        for (int i = 0; i < 24; i++) {
            source.append("declare actor Worker").append(i).append(";\n");
        }
        for (int i = 23; i >= 0; i--) {
            source.append("mtd (Worker").append(i)
                    .append(" self) foo(): int { return ")
                    .append(i).append("; }\n");
        }
        var program = Parser.parse(source.toString());
        var actors = program.modules().getFirst().declarations();
        assertEquals(24, actors.size());
        for (int i = 0; i < 24; i++) {
            var actor = assertInstanceOf(Ast.ClassDecl.class, actors.get(i));
            assertEquals("Worker" + i, actor.name());
            assertEquals(1, actor.methods().size());
            assertEquals("foo", actor.methods().getFirst().name());
        }
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void declarationOnlyActorsBindTypeFirstMethodsWithSameName() {
        Ast.Program program = Parser.parse("""
                use namespace workers;
                declare actor Foo1;
                declare actor Foo2;

                mtd (Foo1 self) foo(): void { return; }
                mtd (Foo2 self) foo(): void { return; }
                """);
        assertEquals("workers", program.namespace());
        var declarations = program.modules().getFirst().declarations();
        assertEquals(2, declarations.size(),
                "forward declarations plus methods create two runtime nominal actor types");
        var foo1 = assertInstanceOf(Ast.ClassDecl.class, declarations.get(0));
        var foo2 = assertInstanceOf(Ast.ClassDecl.class, declarations.get(1));
        assertEquals("Foo1", foo1.name());
        assertEquals("Foo2", foo2.name());
        assertEquals(Ast.ActorKind.SHARED, foo1.actorKind());
        assertEquals(Ast.ActorKind.SHARED, foo2.actorKind());
        assertEquals("foo", foo1.methods().getFirst().name());
        assertEquals("foo", foo2.methods().getFirst().name());
        assertEquals("Foo1", foo1.methods().getFirst().explicitReceiverType().name());
        assertEquals("Foo2", foo2.methods().getFirst().explicitReceiverType().name());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void twoModulesMayDeclareSameActorNameWithDistinctExternalFoo() {
        Ast.Program program = Parser.parse("""
                use namespace workers;
                define module first as
                  declare actor Foo;
                  mtd (Foo self) foo(): int { return 1; }
                end
                define module second as
                  declare actor Foo;
                  mtd (Foo self) foo(): int { return 2; }
                end
                """);
        assertEquals(2, program.modules().size());
        for (var module : program.modules()) {
            assertEquals(1, module.declarations().size());
            assertEquals("foo", ((Ast.ClassDecl) module.declarations()
                    .getFirst()).methods().getFirst().name());
        }
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
    }

    @Test
    void declarationOnlyActorCannotSpoofRuntimeStateAuthority() {
        for (var method : new String[]{
                "mtd (Foo self) handle_state(): void { return; }",
                "mtd (Foo self) send(): void { return; }",
                "mtd (Foo self) on_panic(): void { return; }",
                "mtd (Foo self) tear_down(): void { return; }"
        }) {
            reject("declare actor Foo; " + method,
                    "reserved for compiler-owned lifecycle/state authority");
        }
        reject("""
                declare actor Foo;
                define actor Foo as
                  let int secret;
                end
                mtd (Foo self) foo(): int { return self.secret; }
                """, "sealed state");
        reject("declare actor Foo; mtd (Unrelated self) foo(): void { return; }",
                "must be declared");
        reject("""
                declare actor Foo;
                mtd (Foo self) foo(): void { return; }
                mtd (Foo self) foo(): void { return; }
                """, "duplicate receiver method");
    }

    @Test
    void typeFirstClassReceiverCoexistsWithLegacySelfFirstSyntax() {
        Ast.Program program = Parser.parse("""
                define class Point as
                  pub let int x = 42;
                end
                mtd (Point self) answer(): int { return self.x; }
                mtd (self: Point) other(): int { return self.x; }
                """);
        var point = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(2, point.methods().size());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void immutableExternalActorReceiverCannotCallMutableMethod() {
        Ast.Program program = Parser.parse("""
                declare actor Foo1;
                mtd (Foo1 mut self) exclusive(): void { return; }
                mtd (Foo1 self) readonly(): void {
                  self.exclusive();
                  return;
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program),
                "a read-only receiver cannot acquire an exclusive actor loan");
    }

    @Test
    void sameNamedActorsInSiblingModulesRetainExactOwnershipIdentity() {
        Ast.Program valid = Parser.parse("""
                use namespace workers;
                define module west as
                  declare actor Worker;
                  mtd (Worker self) foo(): int { return 41; }
                end
                define module east as
                  declare actor Worker;
                  mtd (Worker self) foo(): int { return 42; }
                end
                """);
        assertDoesNotThrow(() -> OwnershipChecker.check(valid),
                "two nominal Worker actors cannot share ownership identity");

        Ast.Program illegalMutCall = Parser.parse("""
                use namespace workers;
                define module west as
                  declare actor Worker;
                  mtd (Worker mut self) mutate(): void { return; }
                  mtd (Worker self) invalid(): void {
                    self.mutate();
                    return;
                  }
                end
                define module east as
                  declare actor Worker;
                  mtd (Worker self) foo(): int { return 42; }
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(illegalMutCall),
                "an ambiguous unqualified Worker name must not skip mutable loan validation");

        Ast.Program illegalEscape = Parser.parse("""
                define module west as
                  declare actor Worker;
                  mtd (Worker self) leak(): Worker { return self; }
                end
                define module east as
                  declare actor Worker;
                  mtd (Worker self) foo(): int { return 42; }
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(illegalEscape),
                "an ambiguous unqualified Worker name must not permit actor self escape");
    }

    @Test
    void readonlyActorSelfCannotEscapeItsMailboxTurn() {
        for (String receiver : new String[]{"Foo1 self", "Foo1 mut self"}) {
            Ast.Program program = Parser.parse("""
                    declare actor Foo1;
                    mtd (%s) leak(): Foo1 {
                      return self;
                    }
                    """.formatted(receiver));
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.check(program));
            assertTrue(rejected.getMessage().contains("non-escapable mailbox capability"),
                    rejected.getMessage());
        }
    }

    @Test
    void readonlyActorReceiverCannotSendOrEndMailbox() {
        for (String call : new String[]{"self.end();", "self.send(42);"}) {
            Ast.Program program = Parser.parse("""
                    declare actor Foo1;
                    mtd (Foo1 self) illegal(): void {
                      %s
                      return;
                    }
                    """.formatted(call));
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.check(program));
            assertTrue(rejected.getMessage().contains("requires exclusive mutable actor self"),
                    rejected.getMessage());
        }
        var permitted = Parser.parse("""
                declare actor Foo1;
                mtd (Foo1 mut self) finish(): void {
                  self.end();
                  return;
                }
                """);
        assertDoesNotThrow(() -> OwnershipChecker.check(permitted));
    }

    @Test
    void externalReceiveRemainsRuntimeOnlyNotCallableAsOrdinaryMethod() {
        var program = Parser.parse("""
                declare actor Worker;
                mtd (Worker mut self) receive(ActorMail<String> mail): void {
                  self.receive(mail);
                  return;
                }
                """);
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(rejected.getMessage().contains("reserved actor lifecycle hook"),
                rejected.getMessage());
    }

    @Test
    void explicitMailboxHandlerCannotSilentlyUpgradeReadonlyReceiver() {
        reject("""
                declare actor Foo1;
                mtd (Foo1 self) receive(ActorMail<String> mail): void {
                  self.end();
                  return;
                }
                """, "exclusive mailbox-turn receiver");
        var program = Parser.parse("""
                declare actor Foo1;
                mtd (Foo1 self) foo(): int { return 42; }
                mtd (Foo1 mut self) receive(ActorMail<String> mail): void {
                  self.send(self.foo());
                  self.end();
                  return;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void declarationOnlyActorWithoutMailboxHandlerCannotAcceptMessages() {
        Ast.Program program = Parser.parse("""
                declare actor Foo1;
                mtd (Foo1 self) foo(): int { return 42; }
                pub async routine main(): void {
                  val actor = spawn Foo1();
                  actor.send("payload");
                  return;
                }
                """);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(failure.getMessage().contains("no receive(ActorMail<T>)"),
                failure.getMessage());
    }

    @Test
    void declarationOnlyActorsExecuteSharedNamedMethods() throws Exception {
        String source;
        try (var stream = getClass().getResourceAsStream(
                "/atto/mtd-receiver-v1/declared-actors-type-first.atto")) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var program = Parser.parse(source);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        var guest = Source.newBuilder(OresLanguage.ID, source, "declared-actors-type-first.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("41|42", output.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void actualTwoActorGuestRunsWithIndependentMethodSets() throws Exception {
        String source;
        try (var stream = getClass().getResourceAsStream(
                "/atto/mtd-receiver-v1/multi-actor-namespace.atto")) {
            assertNotNull(stream, "missing same-file two-actor .atto fixture");
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        var guest = Source.newBuilder(OresLanguage.ID, source, "multi-actor-namespace.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("41|42", output.toString(StandardCharsets.UTF_8).trim());
    }

    private static void reject(String source, String fragment) {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source), source);
        assertTrue(exception.getMessage().contains(fragment),
                "expected '" + fragment + "', got " + exception.getMessage());
    }
}
