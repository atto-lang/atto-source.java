package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Never let the historical MaxActorThreads policy become a second authority. */
final class ActorThreadsLegacyCompatibilityTest {
    private static Ast.ClassDecl actor(String source) {
        return Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void legacyLowNativeAndModernAnnotationsNormalizeToOnePolicy() {
        String[] spelling = {
                "define actor Worker as end",
                "@ActorMaxThreads(MaxActorThreads.LOW) define actor Worker as end",
                "@ActorThreads(min = 1, max = 1) define actor Worker as end",
                "define actor Worker with threads(min: 1, max: 1) as end"
        };
        for (String source : spelling) {
            var declaration = actor(source);
            assertEquals(new Ast.ActorThreadPolicy(1, 1), declaration.actorThreads(), source);
            assertEquals(Ast.MaxActorThreads.LOW, declaration.maxActorThreads(), source);
            assertEquals(new Ast.ActorThreadPolicy(1, 1),
                    actorAfterTypeChecking(source).actorThreads(), source);
        }
    }

    private static Ast.ClassDecl actorAfterTypeChecking(String source) {
        return OresCompiler.parseAndTypeCheck(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void canonicalFrozenEnumDeclarationIsAcceptedExactlyOnce() {
        String compat = """
                define enum MaxActorThreads as
                    LOW int = 1
                    MEDIUM int = 2
                    HIGH int = 3
                end
                @ActorMaxThreads(MaxActorThreads.LOW)
                define actor Worker from private actor as
                end
                """;
        var declaration = actor(compat);
        assertEquals(Ast.ActorKind.PRIVATE, declaration.actorKind());
        assertEquals(Ast.ActorThreadPolicy.defaults(), declaration.actorThreads());
    }

    @Test
    void enumOnlyAstConstructorsMapIntoCanonicalPolicy() {
        var original = actor("define actor Worker as end");
        var legacy = new Ast.ClassDecl(original.name(), original.visibility(),
                original.isAbstract(), original.actorKind(), original.genericParameters(),
                original.parents(), original.interfaces(), original.fields(),
                original.constructor(), original.methods(), Ast.MaxActorThreads.LOW);
        assertEquals(Ast.ActorThreadPolicy.defaults(), legacy.actorThreads());
        assertEquals(Ast.MaxActorThreads.LOW, legacy.maxActorThreads());

        var forged = new Ast.ClassDecl(original.name(), original.visibility(),
                original.isAbstract(), original.actorKind(), original.genericParameters(),
                original.parents(), original.interfaces(), original.fields(),
                original.constructor(), original.methods(), Ast.MaxActorThreads.HIGH);
        assertEquals(new Ast.ActorThreadPolicy(1, 3), forged.actorThreads());
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.ActorThreadAdmission.requireExclusiveBackend(forged));
    }

    @Test
    void newAndOldSpellingAreMutuallyExclusiveIncludingEquivalentValues() {
        String[] invalid = {
                "@ActorMaxThreads(MaxActorThreads.LOW) @ActorThreads(max = 1) define actor Worker as end",
                "@ActorThreads(max = 1) @ActorMaxThreads(MaxActorThreads.LOW) define actor Worker as end",
                "@ActorMaxThreads(MaxActorThreads.LOW) @ActorMaxThreads(MaxActorThreads.LOW) define actor Worker as end",
                "@ActorMaxThreads(MaxActorThreads.LOW) define actor Worker with threads(max: 1) as end",
                "@ActorMaxThreads(MaxActorThreads.MEDIUM) define actor Worker as end",
                "@ActorMaxThreads(MaxActorThreads.HIGH) define actor Worker as end",
                "@ActorMaxThreads(MaxActorThreads.UNKNOWN) define actor Worker as end",
                "@ActorMaxThreads(1) define actor Worker as end",
                "@ActorMaxThreads() define actor Worker as end",
                "@ActorMaxThreads(MaxActorThreads.LOW) define class C as end",
                "@ActorMaxThreads(MaxActorThreads.LOW) fnc work(): void { return; }",
                "define class C as @ActorMaxThreads(MaxActorThreads.LOW) fnc work(): void { return; } end",
                "define class C as @ActorMaxThreads(MaxActorThreads.LOW) let int n = 0; end",
                "define actor Worker as @ActorMaxThreads(MaxActorThreads.LOW) fnc work(): void { return; } end"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void frozenEnumCannotBeRedefinedOrShadowed() {
        String canonical = """
                define enum MaxActorThreads as
                    LOW int = 1
                    MEDIUM int = 2
                    HIGH int = 3
                end
                """;
        String[] invalid = {
                canonical + canonical,
                canonical.replace("HIGH int = 3", "HIGH int = 4"),
                canonical.replace("MEDIUM int = 2", "EXTRA int = 2"),
                canonical.replace("MaxActorThreads as", "UserDefined as"),
                canonical.replace("LOW int = 1", "LOW string = 1"),
                "@ActorThreads(max = 1) " + canonical
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }
}
