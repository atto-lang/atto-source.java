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
    @Test void moduleValueBindingCannotImpersonateQualifiedIdentityHelper() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    define class T as
                    end
                    define module Helpers as
                      fnc relay(T x): x { return x; }
                    end
                    define module Client as
                      val Helpers = 42;
                      fnc forward(T v): v { return Helpers.relay(v); }
                    end
                    """)));
        assertTrue(error.getMessage().contains("identity return mismatch"), error::getMessage);
    }

    @Test void moduleValueBindingCannotImpersonateUnqualifiedIdentityHelper() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    define module m as
                      define class T as
                      end
                      val relay = 42;
                      fnc relay(T x): x { return x; }
                      fnc forward(T v): v { return relay(v); }
                    end
                    """)));
        assertTrue(error.getMessage().contains("identity return mismatch"), error::getMessage);
    }

    @Test void ambiguousUnqualifiedClassAncestryFailsClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    define module A as
                      define class Root as
                        pub same(): this { return self; }
                      end
                    end
                    define module B as
                      define class Root as
                        pub same(): self { return self; }
                      end
                    end
                    define module C as
                      define class Child extends Root as
                        pub same(): self { return self; }
                      end
                    end
                    """)));
        assertTrue(error.getMessage().contains("ambiguous inherited class"), error::getMessage);
    }

    @Test void ambiguousUnqualifiedInterfaceAncestryFailsClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    define module A as
                      define interface Root as
                        fnc same(): this;
                      end
                    end
                    define module B as
                      define interface Root as
                        fnc same(): self;
                      end
                    end
                    define module C as
                      define interface Child extends Root as
                        fnc same(): self;
                      end
                    end
                    """)));
        assertTrue(error.getMessage().contains("ambiguous inherited interface"), error::getMessage);
    }

    @Test void transitiveInterfaceLookupUsesDeclaringModuleDespiteShadowName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    define module A as
                      define interface Root as
                        fnc same(): this;
                      end
                      define interface Mid extends Root as
                      end
                    end
                    define module B as
                      define interface Root as
                        fnc same(): self;
                      end
                      define interface Child extends A.Mid as
                        fnc same(): self;
                      end
                    end
                    """)));
        assertTrue(error.getMessage().contains("cannot weaken inherited identity contract"),
                error::getMessage);
    }

    @Test void transitiveInterfaceIdentityCanBePreservedAcrossModules() {
        assertDoesNotThrow(() -> IdentityReturnChecker.check(Parser.parse("""
                define module A as
                  define interface Root as
                    fnc same(): this;
                  end
                  define interface Mid extends Root as
                  end
                end
                define module B as
                  define interface Root as
                    fnc same(): self;
                  end
                  define interface Child extends A.Mid as
                    fnc same(): this;
                  end
                end
                """)));
    }

    @Test void transitiveClassOverrideCannotResolveAnUnrelatedShadowParent() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse("""
                    define module A as
                      define class Root as
                        pub same(): this { return self; }
                      end
                      define class Mid extends Root as
                      end
                    end
                    define module B as
                      define class Root as
                        pub same(): self { return self; }
                      end
                      define class Child extends A.Mid as
                        pub same(): self { return self; }
                      end
                    end
                    """)));
        assertTrue(error.getMessage().contains("cannot weaken inherited identity return"),
                error::getMessage);
    }

}
