package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class CanonicalBareFactoryGuestTest {
    @TempDir Path temp;

    private static final String CANONICAL = """
            define pub class Cart implements Newable[Cart] as
              val int count = 42;
              pub new(): Cart { return new Cart(); }
              pub get_count(): int { return self.count; }
            end
            pub fnc main(): void {
              val Cart c = Cart.new();
              stdio.out.write(c.get_count());
              return;
            }
            """;

    @Test
    void canonicalAndLegacyFactoriesPreserveStaticWitnessAndExecute() throws Exception {
        String legacy = CANONICAL.replace("pub new(", "pub fnc new(")
                .replace("pub get_count(", "pub fnc get_count(");
        for (String source : new String[]{CANONICAL, legacy}) {
            Ast.Program program = Parser.parse(source);
            Ast.ClassDecl cart = assertInstanceOf(Ast.ClassDecl.class,
                    program.modules().getFirst().declarations().getFirst());
            assertEquals("new", cart.methods().getFirst().name());
            assertTrue(cart.methods().getFirst().isStatic());
            assertFalse(cart.methods().get(1).isStatic());
            TypeChecker.checkTypes(program);
            Path entry = temp.resolve("main.atto");
            Files.writeString(entry, source);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), Set.of(), Map.of(), out, err);
            assertEquals("42", out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void bareFactorySpellingCannotBypassWitnessChecks() {
        for (String member : new String[]{
                "new(): Cart { return new Cart(); }",
                "pub new(int x): Cart { return new Cart(); }",
                "pub new(): int { return 42; }"
        }) {
            String source = "define pub class Cart implements Newable[Cart] as " + member + " end";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("define pub class Cart as pub new(): Cart { return new Cart(); } end")));
    }

    @Test
    void genericFactoryRemainsExplicitlyUnsupportedUntilSpecializedWitnessExists() {
        String source = """
                define pub class Box<T> implements Newable[Box<T>] as
                  pub new(): Box<T> { return new Box<T>(); }
                  pub echo(T value): T { return value; }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
    }
}
