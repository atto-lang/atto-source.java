package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DynNominalWitnessTest {
    private static final String API = """
            define interface Deployable as
                fnc deploy(): int;
            end
            define class Database implements Deployable as
                pub deploy(): int { return 1; }
            end
            """;

    @Test
    void uncertaintyCannotForgeDynProof() {
        Types.Dyn interfaceView = new Types.Dyn(new Types.Named("Deployable", List.of()));
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, interfaceView));
        assertFalse(Types.isAssignable(new Types.Generic("T"), interfaceView));
        assertFalse(Types.isAssignable(new Types.Named("Deployable", List.of()), interfaceView));
        assertTrue(Types.isAssignable(interfaceView, interfaceView));
    }

    @Test
    void genericValueCannotBecomeErasedInterfaceWithoutProof() {
        String source = API + """
                fnc unsafe<T>(T input): dyn Deployable {
                    return input;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void unresolvedInterfaceGenericCannotBeErased() {
        String source = """
                define interface Boxed<T> as
                    fnc get(): T;
                end
                fnc unsafe<T>(dyn Boxed<T> item): void { return; }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("concrete, proven types"), error::getMessage);
    }

    @Test
    void provenConcreteImplementationMayBeErased() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(API + """
                fnc deploy(dyn Deployable resource): int {
                    return resource.deploy();
                }
                fnc valid(): int {
                    val dyn Deployable resource = new Database();
                    return deploy(resource);
                }
                """)));
    }

    @Test
    void runtimeCastCannotForgeDynWitness() {
        String source = API + """
                fnc unsafe(Database database): dyn Deployable {
                    return database as dyn Deployable;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("runtime"), error::getMessage);
    }

    @Test
    void dynCannotBeImplicitlyDeepCopied() {
        String source = API + """
                fnc invalid(dyn Deployable instance): void {
                    val duplicate = rt copy instance;
                    return;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("rt copy of move-only"), error::getMessage);
    }

    @Test
    void dynCannotBeMadeSharedWithoutOwnershipContract() {
        String source = API + """
                fnc invalid(dyn Deployable instance): void {
                    val shared = rt share instance;
                    return;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("rt share of move-only"), error::getMessage);
    }

    @Test
    void nestedDynIsForbiddenInActorMailContract() {
        String source = API + """
                fnc invalid(ActorMail<List<dyn Deployable>> mail): void {
                    return;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("transport/ownership ABI"), error::getMessage);
    }

    @Test
    void dynCannotEscapeThroughGenericChannelAlias() {
        String source = API + """
                define type Payload<T> = List<T>;
                fnc unsafe(Channel<Payload<dyn Deployable>> queue): void { return; }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }
}
