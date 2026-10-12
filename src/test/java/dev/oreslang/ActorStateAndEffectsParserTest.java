package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.FunctionEffects;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ActorStateAndEffectsParserTest {
    private static Ast.ClassDecl actor(String source) {
        Ast.Program program = Parser.parse(source);
        return program.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
    }

    @Test void parsesFiniteActorStateAndSealedCallbackEffectMetadata() {
        Ast.ClassDecl actor = actor("""
            define actor Counter as
                state {
                    count: int = 0;
                    enabled: bool = true;
                }
                pub increment(): int with state.read, state.write {
                    return self.handle_state(critical write |s| -> {
                        s.count = s.count + 1;
                        return s.count;
                    });
                }
            end
            """);
        assertEquals(1, actor.fields().size());
        Ast.FieldDecl state = ActorStateContract.stateField(actor);
        assertNotNull(state);
        assertEquals("int", state.type().recordMembers().get("count").name());
        assertEquals("bool", state.type().recordMembers().get("enabled").name());
        assertEquals(2, ((Ast.ObjectExpr) state.initializer()).fields().size());
        var effects = actor.methods().getFirst().annotations().stream()
                .filter(a -> a.name().equals("$with$")).findFirst().orElseThrow();
        assertEquals(List.of("state.read", "state.write"),
                effects.arguments().stream().map(Ast.TypeRef::name).toList());
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse("""
            define actor A as
              state { count: int = 0; }
              pub f(): void { self.handle_state(critical write |s| -> { s.count = s.count + 1; }); return; }
            end
            """)));
    }

    @Test void parsesDynamicMapWithTypedDeclaredKeysAndFromInterface() {
        Ast.ClassDecl actor = actor("""
            define interface Measurable as
            end
            define actor Cache from Measurable as
                state: Map<string, int> {
                    hits: int = 0;
                    errors: int = 0;
                }
                pub run(): void { return; }
            end
            """);
        assertEquals("Measurable", actor.interfaces().getFirst().name());
        Ast.FieldDecl state = ActorStateContract.stateField(actor);
        assertEquals("$actor_state_map$", state.type().name());
        assertEquals("Map", state.type().arguments().getFirst().name());
        assertEquals(2, state.type().arguments().get(1).recordMembers().size());
    }

    @Test void parsesRequirementsAndPreservesTrapNlex() {
        Ast.Program p = Parser.parse("""
            pub nlex fnc f(): int with alloc requires bounded, no_block {
                return 1;
            }
            pub trap fnc g(): int with io { return 2; }
            """);
        Ast.FunctionDecl f = (Ast.FunctionDecl) p.modules().getFirst().declarations().getFirst();
        assertTrue(f.nonLexical());
        assertFalse(f.trapped());
        assertTrue(f.annotations().stream().anyMatch(a -> a.name().equals("$requires$")));
        Ast.FunctionDecl g = (Ast.FunctionDecl) p.modules().getFirst().declarations().get(1);
        assertTrue(g.trapped());
    }

    @Test void rejectsInvalidSchemaAndEffectSpelling() {
        assertThrows(IllegalArgumentException.class, () -> actor("""
            define actor A as state { x: int = 1; x: int = 2; } end
            """));
        assertThrows(IllegalArgumentException.class, () -> actor("""
            define actor A as
              state { x: int = 1; }
              val int leaked = 42;
            end
            """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc foo(): int with state.read, state.read { return 1; }
            """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc foo(): int using magic { return 1; }
            """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
            fnc foo(): int requires no_alloc, no_alloc { return 1; }
            """));
    }

    @Test void rejectsStateEscapeAndDirectAccessBeforeTypeLowering() {
        assertThrows(IllegalArgumentException.class, () ->
            ActorStateContract.verify(Parser.parse("""
                define actor A as
                  state { count: int = 0; }
                  pub leak(): int { return self.state.count; }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () ->
            ActorStateContract.verify(Parser.parse("""
                define actor A as
                  state { count: int = 0; }
                  pub leak(): int { return self.handle_state(critical write |s| -> { return s; }); }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () ->
            ActorStateContract.verify(Parser.parse("""
                define actor A as
                  state { count: int = 0; }
                  pub bogus(): void { self.handle_state(critical write |s| -> { s.missing = s.missing + 1; }); return; }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () ->
            ActorStateContract.verify(Parser.parse("""
                define actor A as
                  state { count: int = 0; }
                  handle_state(): void { return; }
                end
                """)));
    }

    @Test void permitsDynamicMapButRejectsNestedStateLoans() {
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse("""
            define actor Cache as
                state: Map<string, int>;
                pub noop(): void { return; }
            end
            """)));
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(Parser.parse("""
            define actor Counter as
                state { count: int = 0; }
                pub nested(): void {
                    self.handle_state(critical write |s| -> {
                        self.handle_state(critical write |s| -> { s.count = s.count + 1; });
                        s.count = s.count + 1;
                    });
                    return;
                }
            end
            """)));
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(Parser.parse("""
            define actor Counter as
                state { count: int = 0; }
                pub illegal(): void {
                    self.handle_state(critical write |s| -> {
                        self.handle_state(critical write |inner| -> {
                            s.count = s.count + 1;
                        });
                    });
                    return;
                }
            end
            """)));
    }

    @Test void rejectsSuspensionWhileStateLoanIsLive() {
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(Parser.parse("""
            define actor Worker as
                state { count: int = 0; }
                pub tick(): void {
                    self.handle_state(critical write |s| -> {
                        s.count = s.count + 1;
                        await fetch();
                        s.count = s.count + 1;
                    });
                    return;
                }
            end
            """)));
    }

    @Test void criticalHandleStateRequiresCriticalCallbackWithUsingUpperBound() {
        String valid = """
            define actor Counter as
                state { count: int = 0; }
                pub increment(): int with state.read, state.write {
                    return self.handle_state(critical write |s| -> {
                        s.count = s.count + 1;
                        return s.count;
                    });
                }
            end
            """;
        Ast.Program p = Parser.parse(valid);
        Ast.ClassDecl counter = actor(valid);
        Ast.CallExpr call = (Ast.CallExpr) ((Ast.ReturnStmt) counter.methods()
                .getFirst().body().getFirst()).value();
        Ast.LambdaExpr callback = (Ast.LambdaExpr) call.arguments().getFirst();
        assertTrue(callback.critical());
        assertFalse(callback.nonLexical());
        assertDoesNotThrow(() -> ActorStateContract.verify(p));
        assertDoesNotThrow(() -> dev.oreslang.types.DeclaredEffectVerifier.verify(p));

        String missing = valid.replace("critical write |s|", "|s|");
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(missing)));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.DeclaredEffectVerifier.verify(Parser.parse(missing)));

        String underDeclared = valid.replace(
                "with state.read, state.write", "with state.read");
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.DeclaredEffectVerifier.verify(Parser.parse(underDeclared)));

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(valid.replace("critical write |s|", "critical 123")));
    }

    @Test void rejectsReifiedCriticalCallbackOutsideSealedIntrinsic() {
        String escaped = """
            define actor Counter as
                state { count: int = 0; }
                pub invalid(): void {
                    stdio.println(critical |s| -> 42);
                    return;
                }
            end
            """;
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(escaped)));
    }

    @Test void handleStateCallbackReturnsTypedValueNotUnknown() {
        String valid = """
            define actor Counter as
                state { count: int = 0; }
                pub read(): int with state.read {
                    return self.handle_state(critical read |s| -> s.count);
                }
                receive(ActorMail<String> mail): void { return; }
            end
            """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(valid)));

        String mismatched = valid.replace(
                "critical read |s| -> s.count", "critical read |s| -> true");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(mismatched)));
    }

    @Test void writingStateDoesNotImplicitlyReadIt() {
        Ast.Program writeOnly = Parser.parse("""
            define actor Counter as
                state { count: int = 0; }
                pub initialize(): void with state.write {
                    self.handle_state(critical write |s| -> {
                        s.count = 42;
                    });
                    return;
                }
            end
            """);
        assertDoesNotThrow(() -> ActorStateContract.verify(writeOnly));
        assertDoesNotThrow(() -> dev.oreslang.types.DeclaredEffectVerifier.verify(writeOnly));

        Ast.Program readModifyWrite = Parser.parse("""
            define actor Counter as
                state { count: int = 0; }
                pub increment(): void with state.write {
                    self.handle_state(critical write |s| -> {
                        s.count = s.count + 1;
                    });
                    return;
                }
            end
            """);
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.DeclaredEffectVerifier.verify(readModifyWrite));
    }

    @Test void effectVocabularyHasMatchingUpperBoundOperations() {
        var allowed = FunctionEffects.ofNames(List.of("state.read", "state.write", "suspend"));
        var actual = FunctionEffects.ofNames(List.of("state.read", "suspend"));
        assertTrue(allowed.permits(actual));
        assertFalse(actual.permits(allowed));
        assertTrue(FunctionEffects.empty().union(actual).permits(actual));
        assertThrows(IllegalArgumentException.class, () -> FunctionEffects.ofNames(List.of("io", "io")));
        assertNotEquals(FunctionEffects.Effect.THROW, FunctionEffects.Effect.PANIC);
    }
}
