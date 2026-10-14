package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

final class TransparentAliasBuildTest {
    @Test
    void libraryBuildKeepsPublicAliasesButNotUnusedPrivateOnes() {
        Ast.Program checked = TypeChecker.check(Parser.parse("""
                define module products
                    type Hidden = int;
                    define pub type Entry<K,V> = record { key: K, value: V };
                    define pub type alias Pair<K,V> as Entry<K,V>;
                end
                """));
        TreeShaker.Result result = TreeShaker.shake(checked, BuildOptions.library(Map.of()));
        Set<String> remaining = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.TypeAliasDecl.class::isInstance)
                .map(Ast.TypeAliasDecl.class::cast)
                .map(Ast.TypeAliasDecl::name)
                .collect(Collectors.toSet());
        assertTrue(remaining.containsAll(Set.of("Entry", "Pair")), remaining.toString());
        assertFalse(remaining.contains("Hidden"), remaining.toString());
    }
}
