package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Actual production-checker tests, not a grammar-only or host-model oracle. */
final class OwnershipBorrowedProjectionShareTest {
    private static final String CLASS = """
            define class Box as
              pub let int count = 42;
              pub let int alt = 11;
              pub let int third = 7;
            end
            """;

    private record Variant(String name, String code) {}

    private static final List<Variant> NEGATIVES = List.of(
            new Variant("count-direct-return", "return rt share box.count;"),
            new Variant("alt-direct-return", "return rt share box.alt;"),
            new Variant("third-direct-return", "return rt share box.third;"),
            new Variant("count-parenthesized-return", "return rt share (box.count);"),
            new Variant("alt-parenthesized-return", "return rt share (box.alt);"),
            new Variant("third-parenthesized-return", "return rt share (box.third);"),
            new Variant("count-direct-binding", "val int v = rt share box.count; return v;"),
            new Variant("alt-direct-binding", "val int v = rt share box.alt; return v;"),
            new Variant("third-direct-binding", "val int v = rt share box.third; return v;"),
            new Variant("count-parenthesized-binding", "val int v = rt share (box.count); return v;"),
            new Variant("alt-parenthesized-binding", "val int v = rt share (box.alt); return v;"),
            new Variant("third-parenthesized-binding", "val int v = rt share (box.third); return v;")
    );

    private static final List<Variant> POSITIVES = List.of(
            new Variant("copy-before-share-count", "val int detached = rt copy box.count; return rt share detached;"),
            new Variant("copy-before-share-alt", "val int detached = rt copy box.alt; return rt share detached;"),
            new Variant("copy-parenthesized-third", "val int detached = rt copy (box.third); return rt share detached;"),
            new Variant("nested-explicit-copy", "return rt share (rt copy box.count);")
    );

    private static String source(Variant variant) {
        return CLASS + """
                fnc use(borrow Box box): int {
                  %s
                }
                """.formatted(variant.code());
    }

    @TestFactory
    Stream<DynamicTest> directBorrowedFieldShareIsNotAnOwnedCapability() {
        assertEquals(12, NEGATIVES.size());
        return NEGATIVES.stream().map(v -> DynamicTest.dynamicTest(v.name(), () -> {
            var parsed = assertDoesNotThrow(() -> Parser.parse(source(v)),
                    "must be parsed before semantic ownership rejection: " + v.name());
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.check(parsed), v.name());
            assertTrue(denied.getMessage().contains("E_SHARE_BORROWED_PROJECTION"),
                    "expected borrowed root/projection semantic rejection: " + denied.getMessage());
        }));
    }

    @TestFactory
    Stream<DynamicTest> detachedScalarCopyCanShareWithoutUpgradingLoan() {
        assertEquals(4, POSITIVES.size());
        return POSITIVES.stream().map(v -> DynamicTest.dynamicTest(v.name(), () -> {
            var parsed = assertDoesNotThrow(() -> Parser.parse(source(v)), v.name());
            assertDoesNotThrow(() -> OwnershipChecker.check(parsed),
                    "explicit Copy scalar snapshot must not be confused with borrowed root: " + v.name());
        }));
    }
}
