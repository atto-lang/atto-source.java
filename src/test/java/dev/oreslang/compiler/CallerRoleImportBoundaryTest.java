package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Link-only policy probes: no enum execution is required. */
final class CallerRoleImportBoundaryTest {
    private static final String SOURCE = """
            define module Locked as
                define enum Roles as
                    A
                end
                @CallableBy(Roles.A)
                pub fnc guarded(): void {}
                pub fnc safe(): void {}
            end
            """;

    private static void link(String exported, String imported) {
        Map<String, Ast.Program> sources = Map.of(
                "provider.ores", Parser.parse(exported),
                "client.ores", Parser.parse(imported));
        ImportGraph.validateLinkedImports(sources);
    }

    @Test
    void protectedFunctionCannotBeImportedWithoutAuthenticatedPolicy() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> link(SOURCE, "import fnc guarded from \"provider.ores\";"))
                .getMessage().contains("verified caller-role ABI"));
    }

    @Test
    void protectedModuleCannotBeImportedAsAnErasedNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> link(SOURCE, "import module Locked from \"provider.ores\";"));
        assertThrows(IllegalArgumentException.class,
                () -> link(SOURCE, "import * as All from \"provider.ores\";"));
        assertThrows(IllegalArgumentException.class,
                () -> link(SOURCE, "import fnc * as F from \"provider.ores\";"));
    }

    @Test
    void importingUnrestrictedSiblingIsStillPermitted() {
        assertDoesNotThrow(() -> link(
                SOURCE, "import fnc safe from \"provider.ores\";"));
    }

    @Test
    void importingModuleWithProtectedClassMethodIsRejected() {
        String source = """
                define module Protected as
                    define enum Roles as
                        A
                    end
                    define pub class Worker as
                        @CallableBy(Roles.A)
                        pub static fnc guarded(): void {}
                    end
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> link(source, "import module Protected from \"provider.ores\";"));
    }

    @Test
    void importingModuleWithUnrestrictedClassMethodIsAllowed() {
        String source = """
                define module Open as
                    define pub class Worker as
                        pub static fnc helper(): void {}
                    end
                end
                """;
        assertDoesNotThrow(() -> link(source,
                "import module Open from \"provider.ores\";"));
    }

    @Test
    void inheritedProtectedStaticMethodIsRejectedOnClassImport() {
        String source = """
                define enum Roles as A end
                define class Base as
                    @CallableBy(Roles.A)
                    pub static fnc guarded(): void {}
                end
                define pub class Child extends Base as end
                """;
        assertThrows(IllegalArgumentException.class, () -> link(
                source, "import class Child from \"provider.ores\";"));
    }

    @Test
    void unknownParentRequiresVerifiedImportedClassPolicy() {
        String source = """
                define pub class Child extends External as end
                """;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> link(
                source, "import class Child from \"provider.ores\";"))
                .getMessage().contains("unverified inherited callable policy"));
    }

    @Test
    void verifiedUnguardedInheritedMethodsRemainImportable() {
        String source = """
                define class Base as
                    pub static fnc safe(): void {}
                end
                define pub class Child extends Base as end
                """;
        assertDoesNotThrow(() -> link(
                source, "import class Child from \"provider.ores\";"));
    }

    @Test
    void protectedPublicStaticMethodInImportedClassIsRejected() {
        String source = """
                define enum Roles as
                    A
                end
                define pub class Worker as
                    @CallableBy(Roles.A)
                    pub static fnc guarded(): void {}
                    pub static fnc safe(): void {}
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> link(source, "import class Worker from \"provider.ores\";"));
    }
}
