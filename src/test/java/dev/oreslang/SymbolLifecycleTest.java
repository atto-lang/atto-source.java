package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

final class SymbolLifecycleTest {
    @Test
    void colonLiteralsAndExplicitKeysTypecheckAsSymbols() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module symbols as
                  pub fnc same() => bool {
                    val Symbol a = :connected;
                    val Symbol b = new Symbol("1d46c11d-580c-4ee0-b5ff-cb3bbd83d596");
                    return a != b;
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
    void processCanonicalSymbolsUseStableLiveHandles() {
        OresSymbol first = OresSymbol.of("shared-key");
        OresSymbol again = OresSymbol.of("shared-key");
        OresSymbol other = OresSymbol.of("other-key");

        assertSame(first, again);
        assertEquals(first, again);
        assertEquals("shared-key", first.key());
        assertEquals(first.id(), again.id());
        assertNotEquals(first.id(), other.id());
        assertTrue(other.id() > first.id());
    }

    @Test
    void singletonModuleCanPinAndExportAnImmutableSymbol() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define singleton module UniversalSymbols as
                  pub val Symbol request = new Symbol("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");
                end

                define module app as
                  pub fnc request_symbol() => Symbol {
                    return await UniversalSymbols.request;
                  }
                end
                """)));

        IllegalArgumentException bareRead = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module UniversalSymbols as
                          pub val Symbol request = new Symbol("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");
                        end

                        define module app as
                          pub fnc request_symbol() => Symbol {
                            return UniversalSymbols.request;
                          }
                        end
                        """)));
        assertTrue(bareRead.getMessage().contains("immediately awaited")
                || bareRead.getMessage().contains("assign"));

        IllegalArgumentException mutableExport = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module UniversalSymbols as
                          pub let Symbol request = new Symbol("5d60d832-fc6a-4fa2-a551-1448c2df6bf2");
                        end
                        """)));
        assertTrue(mutableExport.getMessage().contains("must use val or const"));
    }

    @Test
    void untrustedPoliciesCannotCreateOrReceiveProcessSymbols() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module app as
                  pub fnc tag() => Symbol {
                    return :secret;
                  }
                end
                """));

        SecurityException sourceDenied = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertTrue(sourceDenied.getMessage().contains("process-wide symbols"));

        assertThrows(SecurityException.class,
                () -> OresSymbol.of("secret", IsolatePolicy.strictFaas()));

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            CountDownLatch delivered = new CountDownLatch(1);
            ActorRuntime.ActorRef<Object> untrusted = runtime.spawn(
                    IsolatePolicy.strictFaas(),
                    () -> (message, context) -> delivered.countDown());

            SecurityException transportDenied = assertThrows(SecurityException.class,
                    () -> untrusted.send(List.of("safe", OresSymbol.of("secret"))));
            assertTrue(transportDenied.getMessage().contains("cannot cross"));
            assertEquals(1L, delivered.getCount());
        }
    }

    @Test
    void symbolKeysAreBoundedAndRejectControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.of("   "));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.of("bad\nkey"));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbol.of("x".repeat(OresSymbol.MAX_KEY_LENGTH + 1)));
    }
}
