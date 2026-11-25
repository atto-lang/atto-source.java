package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AllocJsonTextTest {
    @Test
    void parsesStrictTreeAndPreservesDeterministicOrder() {
        OresJsonValue value = AllocJsonText.parse(
                "{\"name\":\"Ada\",\"enabled\":true,\"items\":[1,null,\"🚀\"]}");

        assertEquals(
                "{\"name\":\"Ada\",\"enabled\":true,\"items\":[1,null,\"🚀\"]}",
                OresJsonValue.stringify(value));
    }

    @Test
    void preservesIntegersBeyondJavascriptSafeRangeExactly() {
        OresJsonValue value = AllocJsonText.parse(
                "{\"n\":900719925474099312345678901234567890}");

        assertEquals(
                "{\"n\":900719925474099312345678901234567890}",
                OresJsonValue.stringify(value));
    }

    @Test
    void plainStringAndEscapedSuffixPathsPreserveExactUnicode() {
        for (String expected : new String[]{"", "ascii", "Hélium 😀", "🚀 tail", "prefix\nsuffix", "é😀\t\"\\/"}) {
            String encoded = OresJsonValue.stringify(OresJsonValue.of(expected));
            assertEquals(encoded, OresJsonValue.stringify(AllocJsonText.parse(encoded)));
            assertEquals(encoded, OresJsonValue.stringify(OresJsonValue.parseImmutable(encoded)));
        }
        assertEquals("\"prefix😀\\nend\"",
                OresJsonValue.stringify(AllocJsonText.parse("\"prefix😀\\nend\"")));
        for (String malformed : new String[]{"\"prefix" + (char) 0xD800 + "\"",
                "\"prefix" + (char) 0xDC00 + "\"", "\"prefix\\n" + (char) 0xD800 + "\"",
                "\"prefix", "\"prefix\\", "\"prefix\u0001\""}) {
            assertThrows(IllegalArgumentException.class, () -> AllocJsonText.parse(malformed));
            assertThrows(IllegalArgumentException.class, () -> OresJsonValue.parseImmutable(malformed));
        }
        assertThrows(IllegalArgumentException.class,
                () -> AllocJsonText.parse("{\"é😀\":1,\"\\u00e9\\uD83D\\uDE00\":2}"));
    }

    @Test
    void shortIntegralPathPreservesExactDecimalCarrierAndScale() {
        for (String token : new String[]{"0", "-0", "1", "-1", "999999999999999999",
                "-99999999999999999", "1000000000000000000", "9223372036854775807",
                "-9223372036854775808", "9223372036854775808", "1.00", "-0.0", "1e2", "1e-2"}) {
            var expected = new java.math.BigDecimal(token);
            var actual = (OresJsonValue.NumberValue) AllocJsonText.parse(token);
            assertEquals(expected, actual.value(), token);
            var immutable = (OresJsonValue.NumberValue) OresJsonValue.parseImmutable(token);
            assertEquals(expected, immutable.value(), token);
        }
        var random = new java.util.Random(19);
        for (int i = 0; i < 1000; i++) {
            String token = Long.toString(random.nextLong() % 100000000000000000L);
            assertEquals(new java.math.BigDecimal(token),
                    ((OresJsonValue.NumberValue) AllocJsonText.parse(token)).value(), token);
        }
    }

    @Test
    void rejectsDuplicateKeysInsteadOfChoosingFirstOrLast() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("{\"x\":1,\"x\":2}"));

        assertTrue(failure.getMessage().contains("duplicate object key"),
                failure.getMessage());
    }

    @Test
    void rejectsNonJsonNumberGrammar() {
        for (String source : new String[]{"01", "1.", "1e", "+1", "NaN", "Infinity", "1٢", "1.٢", "1e٢"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AllocJsonText.parse(source),
                    source);
        }
    }

    @Test
    void rejectsTrailingCommaCommentsAndTrailingData() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("{\"x\":1,}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("[1,]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("{/*x*/\"x\":1}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("true false"));
    }

    @Test
    void rejectsMalformedUnicodeAndControlCharacters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("\"\\uD800\""));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("\"\\uDC00\""));
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("\"line\nraw\""));

        assertEquals(
                "\"🚀\"",
                OresJsonValue.stringify(AllocJsonText.parse("\"\\uD83D\\uDE80\"")));
    }

    @Test
    void rejectsNonAsciiHexAndEscapedDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> AllocJsonText.parse("\"\\uＦＦＦＦ\""));
        assertThrows(IllegalArgumentException.class, () -> AllocJsonText.parse("{\"x\":1,\"\\u0078\":2}"));
    }

    @Test
    void dynamicLookupDistinguishesMissingFromJsonNullAndKeepsUnicode() {
        var object = (OresJsonValue.ObjectValue) AllocJsonText.parse(
                "{\"Xx\":{\"name\":\"Hélium 😀\"},\"nullish\":null}");
        assertEquals("{\"name\":\"Hélium 😀\"}",
                OresJsonValue.stringify(object.fields().lookup("Xx").value()));
        assertTrue(object.fields().lookup("nullish").present());
        assertInstanceOf(OresJsonValue.NullValue.class, object.fields().lookup("nullish").value());
        assertFalse(object.fields().lookup("absent").present());
    }

    @Test
    void rejectsExcessiveNestingBeforePublishingAValue() {
        String nested = "[".repeat(140) + "0" + "]".repeat(140);
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse(nested));
    }

    @Test
    void immutableGuestParseSealsNestedMapsIncludingThroughArrays() {
        var root = (OresJsonValue.ObjectValue) OresJsonValue.parseImmutable("{\"a\":[{\"n\":1}],\"nullish\":null}");
        assertTrue(OresJsonValue.provenImmutable(root));
        var array = (OresJsonValue.ArrayValue) root.fields().getRequired("a");
        var child = (OresJsonValue.ObjectValue) array.values().getFirst();
        assertTrue(OresJsonValue.provenImmutable(array));
        assertTrue(OresJsonValue.provenImmutable(child));
        assertThrows(IllegalStateException.class, () -> root.fields().put("new", OresJsonValue.of(2L)));
        assertThrows(IllegalStateException.class, () -> child.fields().putAll(java.util.Map.of("n", OresJsonValue.of(2L))));
        assertThrows(IllegalStateException.class, () -> child.fields().take("n"));
        assertThrows(IllegalStateException.class, () -> child.fields().remove("n"));
        assertThrows(IllegalStateException.class, () -> child.fields().clear());
        assertTrue(OresJsonValue.getOwned(root, "missing").isEmpty());
        assertInstanceOf(OresJsonValue.NullValue.class, OresJsonValue.getOwned(root, "nullish").orElseThrow());
        var detached = (OresJsonValue.ObjectValue) OresJsonValue.atOwned(array, 0).orElseThrow();
        detached.fields().put("n", OresJsonValue.of(9L));
        assertEquals("{\"n\":1}", OresJsonValue.stringify(child));
        assertEquals("{\"n\":9}", OresJsonValue.stringify(detached));
    }

    @Test
    void immutablePathDoesNotWeakenQuotaOrMutableHostCompatibility() {
        var mutable = (OresJsonValue.ObjectValue) AllocJsonText.parse("{\"n\":1}");
        assertFalse(OresJsonValue.provenImmutable(mutable));
        mutable.fields().put("n", OresJsonValue.of(2L));
        assertEquals("{\"n\":2}", OresJsonValue.stringify(mutable));
        String huge = "x".repeat(OresJsonValue.DEFAULT_STRINGIFY_UTF8_BYTES);
        var root = OresJsonValue.parseImmutable("{\"huge\":\"" + huge + "\"}");
        assertThrows(IllegalArgumentException.class, () -> OresJsonValue.getOwned(root, "huge"));
        assertTrue(OresJsonValue.getOwned(root, "missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> OresJsonValue.parseImmutable("{\"a\":1,\"a\":2}"));
    }

    @Test
    void parseReturnsCanonicalJsonObjectRuntimeNotHostMap() {
        OresJsonValue parsed = AllocJsonText.parse("{\"x\":1}");
        assertInstanceOf(OresJsonValue.ObjectValue.class, parsed);
        OresJsonValue.ObjectValue object = (OresJsonValue.ObjectValue) parsed;
        assertEquals(OresKeyedMap.KeyDomain.STRING, object.fields().keyDomain());
        assertEquals(OresKeyedMap.ValueDomain.JSON, object.fields().valueDomain());
    }
}
