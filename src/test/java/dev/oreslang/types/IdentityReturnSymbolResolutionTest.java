package dev.oreslang.types;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct proof-pass tests intentionally bypass external import resolution:
 * the verifier must fail closed when a name could bind to a different symbol.
 */
final class IdentityReturnSymbolResolutionTest {
    @Test void aStaticallyResolvedLocalCallProvesIdentity() {
        assertDoesNotThrow(() -> IdentityReturnChecker.check(Parser.parse("""
                define module m as
                  define class T as
                  end
                  fnc relay(T x): x { return x; }
                  fnc forward(T v): v { return relay(v); }
                end
                """)));
    }

    @Test void importedCallableMayNotStealALocalIdentityProof() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    import fnc relay from "./external.ores";
                    define module m as
                      define class T as
                      end
                      fnc relay(T x): x { return x; }
                      fnc forward(T v): v { return relay(v); }
                    end
                    """)));
        assertTrue(error.getMessage().contains("identity return mismatch"), error::getMessage);
    }

    @Test void importedNamespaceMayNotStealAQualifiedIdentityProof() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    import * as Helpers from "./external.ores";
                    define class T as
                    end
                    define module Helpers as
                      fnc relay(T x): x { return x; }
                    end
                    define module Client as
                      fnc forward(T v): v { return Helpers.relay(v); }
                    end
                    """)));
        assertTrue(error.getMessage().contains("identity return mismatch"), error::getMessage);
    }

    @Test void classNamespaceMayNotMasqueradeAsModuleIdentityHelper() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    define class T as
                    end
                    define class Helpers as
                    end
                    define module Helpers as
                      fnc relay(T x): x { return x; }
                    end
                    define module Client as
                      fnc forward(T v): v { return Helpers.relay(v); }
                    end
                    """)));
        assertTrue(error.getMessage().contains("identity return mismatch"), error::getMessage);
    }
}
