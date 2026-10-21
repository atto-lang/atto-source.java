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
    void rejectsDuplicateKeysInsteadOfChoosingFirstOrLast() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse("{\"x\":1,\"x\":2}"));

        assertTrue(failure.getMessage().contains("duplicate object key"),
                failure.getMessage());
    }

    @Test
    void rejectsNonJsonNumberGrammar() {
        for (String source : new String[]{"01", "1.", "1e", "+1", "NaN", "Infinity"}) {
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
    void rejectsExcessiveNestingBeforePublishingAValue() {
        String nested = "[".repeat(140) + "0" + "]".repeat(140);
        assertThrows(
                IllegalArgumentException.class,
                () -> AllocJsonText.parse(nested));
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
