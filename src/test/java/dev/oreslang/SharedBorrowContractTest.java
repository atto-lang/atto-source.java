package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Identical source corpus is also compiled/executed by the native backend. */
final class SharedBorrowContractTest {
    @Test void sharedContract() throws Exception {
        for (String line : Files.readAllLines(Path.of("src/test/resources/borrow-contract.tsv"))) {
            String[] fields = line.split("\t", 3);
            String name = fields[1];
            var parsed = Parser.parse(fields[2]);
            if (fields[0].equals("reject")) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(parsed), name);
                assertTrue(failure.getMessage().toLowerCase().contains("borrow"), name + ": " + failure);
                assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(parsed), name);
            } else {
                assertDoesNotThrow(() -> TypeChecker.check(parsed), name);
                assertDoesNotThrow(() -> OwnershipChecker.check(parsed), name);
                try (var context = Context.newBuilder(OresLanguage.ID)
                        .option("engine.WarnInterpreterOnly", "false").build()) {
                    assertEquals(42, context.eval(Source.newBuilder(OresLanguage.ID, fields[2], name + ".ores").build()).asInt(), name);
                }
            }
        }
    }
    @Test void retainedSharedReborrowPreservesTypeAndRootLifetime() {
        String source = """
            pub define class Cell as pub let int value=42; end
            fnc read(borrow Cell c):int{return c.value;}
            fnc forward(borrow Cell c):int {
              val borrow Cell view = rt borrow c;
              return read(view);
            }
            pub fnc main():int { let Cell c=new Cell(); return forward(rt borrow c); }
            """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
        try (var context = Context.newBuilder(OresLanguage.ID)
                .option("engine.WarnInterpreterOnly", "false").build()) {
            assertEquals(42, context.eval(OresLanguage.ID, source).asInt());
        }
    }
    @Test void retainedReborrowStillPreventsTakingIncomingLoan() {
        var parsed = Parser.parse("""
            pub define class Cell as pub let int value=42; end
            fnc bad(borrow Cell c):Cell {
              val borrow Cell view = rt borrow c;
              return rt take c;
            }
            """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
    }
    @Test void reborrowOfLocalCannotEscapeOwner() {
        var parsed = Parser.parse("""
            pub define class Cell as pub let int value=42; end
            fnc bad():borrow Cell {
              let Cell c=new Cell();
              val borrow Cell first = rt borrow c;
              return rt borrow first;
            }
            """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
    }

}
