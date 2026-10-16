package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defense-in-depth: these gates must hold even if annotation expansion or
 * another compiler pass constructs AST nodes not representable in source.
 * This is source admission only, not a runtime isolation proof.
 */
final class ActorSealedStateAdmissionHardeningTest {
    private static Ast.Program source(String defaultValue) {
        return Parser.parse("define actor Counter as\n"
                + "state { value: int = " + defaultValue + "; }\n"
                + "end\n");
    }

    private static Ast.Program replaceActor(Ast.Program source, Ast.ClassDecl replacement) {
        Ast.ModuleDecl original = source.modules().getFirst();
        return new Ast.Program(source.namespace(), source.imports(),
                List.of(new Ast.ModuleDecl(original.name(), original.annotations(),
                        original.contracts(), List.of(replacement))));
    }

    @Test void literalInitializationAllowedButAmbientReadsAndCallsAreDenied() {
        assertDoesNotThrow(() -> ActorStateContract.verify(source("0")));
        assertDoesNotThrow(() -> ActorStateContract.verify(source("-1")));
        for (String expression : List.of("helper()", "other.value", "self.state.value")) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(source(expression)), expression);
            assertTrue(denied.getMessage().contains("owned constant expression"),
                    () -> expression + ": " + denied.getMessage());
        }
    }

    @Test void purelyLiteralFreshCollectionsAreAllowedAtAdmission() {
        Ast.Program program = Parser.parse("define actor Counter as\n"
                + "state { values: List[int] = [1, 2, 3]; }\n"
                + "end\n");
        assertDoesNotThrow(() -> ActorStateContract.verify(program));
    }

    @Test void syntheticActorConstructorCannotAcquireUncontrolledInitialization() {
        Ast.Program parsed = source("0");
        Ast.ClassDecl actor = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.ConstructorDecl forged = new Ast.ConstructorDecl(
                Ast.Visibility.PRIVATE, List.of(), List.of(), List.of());
        Ast.ClassDecl rewritten = new Ast.ClassDecl(
                actor.name(), actor.visibility(), actor.isAbstract(), actor.actorKind(),
                actor.genericParameters(), actor.parents(), actor.interfaces(), actor.fields(),
                forged, actor.methods(), actor.serial());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(replaceActor(parsed, rewritten)));
        assertTrue(denied.getMessage().contains("constructors cannot bypass"));
    }

    @Test void transformedActorCannotExposeHiddenStorageOrForgeMarker() {
        Ast.Program parsed = source("0");
        Ast.ClassDecl actor = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.FieldDecl field = actor.fields().getFirst();
        Ast.FieldDecl exposed = new Ast.FieldDecl(field.name(), Ast.Visibility.PUBLIC,
                field.bindingKind(), field.type(), field.annotations(),
                field.initializer(), field.mutableReferent());
        Ast.ClassDecl rewritten = new Ast.ClassDecl(
                actor.name(), actor.visibility(), actor.isAbstract(), actor.actorKind(),
                actor.genericParameters(), actor.parents(), actor.interfaces(), List.of(exposed),
                actor.constructor(), actor.methods(), actor.serial());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(replaceActor(parsed, rewritten)));
        assertTrue(denied.getMessage().contains("canonical sealed compiler-owned layout"));
    }
    @Test void implicitLoopRejectsLiveStateLoan() {
        String src = "define actor Counter as\n"
                + "state { value: int = 0; }\n"
                + "read(): int with state.read, suspend { "
                + "return using state.read(|s| -> { loop { break; } return s.value; }); "
                + "} end";
        Ast.Program parsed = Parser.parse(src);
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(parsed));
    }
    @Test void duplicateLoweredStateDefaultsAreDenied() {
        Ast.Program parsed = source("0");
        Ast.ClassDecl actor = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.FieldDecl original = actor.fields().getFirst();
        Ast.ObjectExpr duplicated = new Ast.ObjectExpr(List.of(
                Ast.ObjectField.named("value", new Ast.LiteralExpr(0L)),
                Ast.ObjectField.named("value", new Ast.LiteralExpr(1L))));
        Ast.FieldDecl changed = new Ast.FieldDecl(original.name(), original.visibility(),
                original.bindingKind(), original.type(), original.annotations(),
                duplicated, original.mutableReferent());
        Ast.ClassDecl rewritten = new Ast.ClassDecl(
                actor.name(), actor.visibility(), actor.isAbstract(), actor.actorKind(),
                actor.genericParameters(), actor.parents(), actor.interfaces(), List.of(changed),
                actor.constructor(), actor.methods(), actor.serial());
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(replaceActor(parsed, rewritten)));
    }
    @Test void rejectsOpenActorStateMap() {
        Ast.Program p = Parser.parse("define actor A as state: Map<string,int>; end");
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(p));
    }

    private static Ast.Program replaceStateField(Ast.Program parsed, Ast.FieldDecl changed) {
        Ast.ClassDecl actor = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        return replaceActor(parsed, new Ast.ClassDecl(
                actor.name(), actor.visibility(), actor.isAbstract(), actor.actorKind(),
                actor.genericParameters(), actor.parents(), actor.interfaces(), List.of(changed),
                actor.constructor(), actor.methods(), actor.serial()));
    }

    @Test void forgedHiddenStateFlagsAndAnnotationsRejectAfterAstRewrite() {
        Ast.Program parsed = source("0");
        Ast.FieldDecl field = ((Ast.ClassDecl) parsed.modules().getFirst()
                .declarations().getFirst()).fields().getFirst();
        List<Ast.FieldDecl> forged = List.of(
                new Ast.FieldDecl(field.name(), field.visibility(), Ast.BindingKind.LET,
                        field.type(), field.annotations(), field.initializer(), field.mutableReferent()),
                new Ast.FieldDecl(field.name(), field.visibility(), field.bindingKind(),
                        field.type(), field.annotations(), field.initializer(), false),
                new Ast.FieldDecl(field.name(), field.visibility(), field.bindingKind(),
                        field.type(), List.of(new Ast.Annotation("$actor_state$",
                                List.of(Ast.TypeRef.simple("int")))), field.initializer(), true),
                new Ast.FieldDecl(field.name(), field.visibility(), field.bindingKind(),
                        field.type(), List.of(new Ast.Annotation("$actor_state$", List.of()),
                                new Ast.Annotation("injected", List.of())), field.initializer(), true));
        for (Ast.FieldDecl changed : forged) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(replaceStateField(parsed, changed)));
            assertTrue(denied.getMessage().contains("canonical sealed compiler-owned layout"),
                    denied::getMessage);
        }
    }

    @Test void nestedDuplicateDefaultKeysRejectAfterAstRewrite() {
        Ast.Program parsed = source("0");
        Ast.FieldDecl state = ((Ast.ClassDecl) parsed.modules().getFirst()
                .declarations().getFirst()).fields().getFirst();
        Ast.ObjectExpr nested = new Ast.ObjectExpr(List.of(
                Ast.ObjectField.named("same", new Ast.LiteralExpr(1L)),
                Ast.ObjectField.named("same", new Ast.LiteralExpr(2L))));
        Ast.ObjectExpr defaults = new Ast.ObjectExpr(
                List.of(Ast.ObjectField.named("value", nested)));
        Ast.FieldDecl injected = new Ast.FieldDecl(state.name(), state.visibility(),
                state.bindingKind(), state.type(), state.annotations(),
                defaults, state.mutableReferent());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(replaceStateField(parsed, injected)));
        assertTrue(denied.getMessage().contains("owned constant expression"),
                denied::getMessage);
    }

    @Test void everyImplicitSuspensionShapeRejectsInsideLiveLoan() {
        List<String> statements = List.of(
                "for (let i = 0; i < 2; i = i + 1) do val next = i + 1; done",
                "for const item of arr[1, 2, 3] do val next = item + 1; done",
                "for const [item] of arr[[1], [2]] do val next = item + 1; done",
                "loop do break; done",
                "defer cleanup();");
        for (String statement : statements) {
            String text = "define actor Counter as\n"
                    + "state { value: int = 0; }\n"
                    + "read(): int with state.read, suspend, alloc { "
                    + "return using state.read(|s| -> { " + statement
                    + " return s.value; }); } end";
            Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(text), statement);
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(parsed), statement);
            assertTrue(denied.getMessage().contains("actor state loan cannot cross"),
                    () -> statement + ": " + denied.getMessage());
        }
    }

    @Test void indexedStateLoanProjectionCannotBypassTypedMemberGate() {
        String text = "define actor Counter as\n"
                + "state { value: int = 0; }\n"
                + "read(): int with state.read { "
                + "return using state.read(|s| -> { return s[\"value\"]; }); } end";
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(text));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(parsed));
        assertTrue(denied.getMessage().contains("verified typed projection lowering"),
                denied::getMessage);
    }

    @Test void canonicalClosedMapSchemaAcceptedButForgedWrappersRejected() {
        Ast.Program parsed = Parser.parse("define actor A as "
                + "state: Map<string,int> { value: int = 0; } end");
        assertDoesNotThrow(() -> ActorStateContract.verify(parsed));

        Ast.FieldDecl state = ((Ast.ClassDecl) parsed.modules().getFirst()
                .declarations().getFirst()).fields().getFirst();
        Ast.TypeRef record = Ast.TypeRef.recordType(
                java.util.Map.of("value", Ast.TypeRef.simple("int")));
        Ast.TypeRef badMap = new Ast.TypeRef("Map",
                List.of(Ast.TypeRef.simple("int"), Ast.TypeRef.simple("int")), false);
        List<Ast.TypeRef> forged = List.of(
                new Ast.TypeRef("$actor_state_map$", List.of(), false),
                new Ast.TypeRef("$actor_state_map$", List.of(record), false),
                new Ast.TypeRef("$actor_state_map$", List.of(Ast.TypeRef.simple("NotMap"), record), false),
                new Ast.TypeRef("$actor_state_map$", List.of(badMap, record), false),
                new Ast.TypeRef("$actor_state_map$",
                        List.of(state.type().arguments().getFirst(),
                                Ast.TypeRef.readonlyRecordType(
                                        java.util.Map.of("value", Ast.TypeRef.simple("int")))), false),
                new Ast.TypeRef("$actor_state_map$", state.type().arguments(), true));
        for (Ast.TypeRef type : forged) {
            Ast.FieldDecl changed = new Ast.FieldDecl(
                    state.name(), state.visibility(), state.bindingKind(), type,
                    state.annotations(), state.initializer(), state.mutableReferent());
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(replaceStateField(parsed, changed)));
            assertTrue(denied.getMessage().contains("sealed actor state map"),
                    denied::getMessage);
        }
    }

    @Test void forgedDirectStateRecordLayoutsRejectBeforeDefaultOrCodegen() {
        Ast.Program parsed = source("0");
        Ast.FieldDecl state = ((Ast.ClassDecl) parsed.modules().getFirst()
                .declarations().getFirst()).fields().getFirst();
        Ast.TypeRef scalar = Ast.TypeRef.simple("int");
        Ast.TypeRef value = new Ast.TypeRef("$field$value", List.of(scalar), false);
        List<Ast.TypeRef> forged = List.of(
                Ast.TypeRef.readonlyRecordType(java.util.Map.of("value", scalar)),
                new Ast.TypeRef("$record$", List.of(value), true),
                new Ast.TypeRef("$record$", List.of(value, value), false),
                new Ast.TypeRef("$record$", List.of(
                        new Ast.TypeRef("$field$", List.of(scalar), false)), false),
                new Ast.TypeRef("$record$", List.of(
                        new Ast.TypeRef("value", List.of(scalar), false)), false),
                new Ast.TypeRef("$record$", List.of(
                        new Ast.TypeRef("$field$z", List.of(scalar), false),
                        new Ast.TypeRef("$field$a", List.of(scalar), false)), false));
        for (Ast.TypeRef type : forged) {
            Ast.FieldDecl changed = new Ast.FieldDecl(
                    state.name(), state.visibility(), state.bindingKind(), type,
                    state.annotations(), state.initializer(), state.mutableReferent());
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(replaceStateField(parsed, changed)));
            assertTrue(denied.getMessage().contains("record schema"), denied::getMessage);
        }

        Ast.FieldDecl nullType = new Ast.FieldDecl(
                state.name(), state.visibility(), state.bindingKind(), null,
                state.annotations(), state.initializer(), state.mutableReferent());
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(replaceStateField(parsed, nullType)));
    }

    @Test void canonicalEmptyAndMultipleKeyDirectRecordsStayValid() {
        assertDoesNotThrow(() -> ActorStateContract.verify(
                Parser.parse("define actor Empty as state { } end")));
        assertDoesNotThrow(() -> ActorStateContract.verify(
                Parser.parse("define actor Pair as "
                        + "state { z: int = 1; a: bool = true; } end")));
    }

    @Test void wrappedTypedMapRejectsMalformedNestedRecordLayout() {
        Ast.Program parsed = Parser.parse("define actor A as "
                + "state: Map<string,int> { value: int = 0; } end");
        Ast.FieldDecl state = ((Ast.ClassDecl) parsed.modules().getFirst()
                .declarations().getFirst()).fields().getFirst();
        Ast.TypeRef valueField = new Ast.TypeRef("$field$value",
                List.of(Ast.TypeRef.simple("int")), false);
        Ast.TypeRef duplicate = new Ast.TypeRef("$record$",
                List.of(valueField, valueField), false);
        Ast.TypeRef forgedMap = new Ast.TypeRef("$actor_state_map$",
                List.of(state.type().arguments().getFirst(), duplicate), false);
        Ast.FieldDecl changed = new Ast.FieldDecl(state.name(),
                state.visibility(), state.bindingKind(), forgedMap,
                state.annotations(), state.initializer(), state.mutableReferent());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(replaceStateField(parsed, changed)));
        assertTrue(denied.getMessage().contains("malformed sealed actor state record schema"),
                denied::getMessage);
    }
}
