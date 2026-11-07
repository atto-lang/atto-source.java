package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-driven JUnit checks for lexical binding creation and loan authority.
 * No compile-time binding ID implies an independent heap allocation origin.
 */
final class OwnershipMintedBindingIdTest {
    @TestFactory
    Stream<DynamicTest> scalarSnapshotReceivesIndependentValueAuthorization() {
        return IntStream.range(0, 10).mapToObj(index ->
                DynamicTest.dynamicTest("detached-copy-" + index, () -> {
                    String name = "loan" + index;
                    String source = """
                            fnc read(borrow int %s): int {
                              val int detached = rt copy %s;
                              return rt share detached;
                            }
                            pub fnc main(): int {
                              val int owner = %d;
                              return read(rt borrow owner);
                            }
                            """.formatted(name, name, 33 + index);
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                            "must parse before testing lexical binding identity");
                    assertDoesNotThrow(() -> OwnershipChecker.check(parsed),
                            "explicit Copy creates a scalar value, not new authority for the loan");
                }));
    }

    @TestFactory
    Stream<DynamicTest> borrowedParameterCannotManufactureShareOwner() {
        return IntStream.range(0, 10).mapToObj(index ->
                DynamicTest.dynamicTest("borrowed-capability-" + index, () -> {
                    String name = "loan" + index;
                    String source = """
                            fnc read(borrow int %s): int {
                              return rt share %s;
                            }
                            pub fnc main(): int {
                              val int owner = %d;
                              return read(rt borrow owner);
                            }
                            """.formatted(name, name, 33 + index);
                    var parsed = assertDoesNotThrow(() -> Parser.parse(source),
                            "a parser-only failure is not semantic loan evidence");
                    var rejected = assertThrows(IllegalArgumentException.class,
                            () -> OwnershipChecker.check(parsed),
                            "borrowed named binding must not become an owned Share");
                    assertTrue(rejected.getMessage().contains("rt share"),
                            "diagnostic must originate in Share ownership admission");
                }));
    }
}
