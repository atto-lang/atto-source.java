package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor-contract target from the separately staged #2792 source surface.
 * This suite must run with the #2896 witness and manifest gate.
 */
final class ActorContractTargetCompilerIntegrationTest {
    private static final String NS = "namespace foo;\n";
    private static final String CONTRACT = NS +
            "define pub contract ActorXYZ for actor as "
                    + "mtd bar(int x): int; end\n";

    private static void rejects(String source, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(diagnostic),
                failure.getMessage());
    }

    @Test
    void fullInlineActorDischargesDirectNominalActorTargetContract() {
        var program = TypeChecker.check(Parser.parse(CONTRACT
                + "define pub actor Foo implements ActorXYZ as\n"
                + "  pub bar(int x): int { return x; }\nend"));
        assertNotNull(program);
    }

    @Test
    void regularClassCannotDischargeActorContractThroughMatchingMethod() {
        rejects(CONTRACT + "define pub class Foo implements ActorXYZ as "
                        + "pub bar(int x): int { return x; } end",
                "requires non-generic nominal actor");
    }

    @Test
    void wrongReturnTypeCannotDischargeActorContract() {
        rejects(CONTRACT + "define pub actor Foo implements ActorXYZ as "
                        + "pub bar(int x): bool { return true; } end",
                "direct public concrete matching method");
    }

    @Test
    void missingDirectInstanceMethodCannotDischargeActorContract() {
        rejects(CONTRACT + "define pub actor Foo implements ActorXYZ as "
                        + "pub other(int x): int { return x; } end",
                "direct public concrete matching method");
    }

    @Test
    void privateActorMethodCannotDischargePublicActorContract() {
        rejects(CONTRACT + "define pub actor Foo implements ActorXYZ as "
                        + "bar(int x): int { return x; } end",
                "direct public concrete matching method");
    }

    @Test
    void methodParameterTypeIsNotEligibleForCoerciveContractMatching() {
        rejects(CONTRACT + "define pub actor Foo implements ActorXYZ as "
                        + "pub bar(bool x): int { return 1; } end",
                "parameter signature mismatch");
    }

    @Test
    void runtimeStateLoanHookCannotBeRequiredByActorContract() {
        rejects(NS + "define pub contract Unsafe for actor as "
                        + "mtd handle_state(): void; end",
                "reserved hook");
    }

    @Test
    void genericActorContractNeedsCheckedTypeWitness() {
        rejects(NS + "define pub contract Generic<T> for actor as "
                        + "mtd bar(T x): void; end",
                "requires non-generic");
    }

    @Test
    void actorContractIsNotOrdinaryInterfaceInParsedAst() {
        var a = assertInstanceOf(Ast.InterfaceDecl.class,
                Parser.parse(CONTRACT).modules().getFirst().declarations().getFirst());
        var b = assertInstanceOf(Ast.InterfaceDecl.class,
                Parser.parse(NS + "define pub interface ActorXYZ as "
                        + "fnc bar(int x): int; end")
                        .modules().getFirst().declarations().getFirst());
        assertTrue(a.actorContract());
        assertFalse(a.moduleContract());
        assertFalse(b.actorContract());
        assertNotEquals(a.actorContract(), b.actorContract());
    }
}
