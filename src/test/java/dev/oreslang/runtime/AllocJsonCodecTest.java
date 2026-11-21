package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AllocJsonCodecTest {
    @Test
    void stringifyPreservesExactLargeIntegerAndInsertionOrder() {
        OresKeyedMap<String, OresJsonValue> object =
                OresJsonValue.objectMap();
        object.put(
                "large",
                OresJsonValue.of(
                        new BigInteger("123456789012345678901234567890")));
        object.put("name", OresJsonValue.of("Ada"));

        assertEquals(
                "{\"large\":123456789012345678901234567890,\"name\":\"Ada\"}",
                AllocJsonCodec.stringifyObject(object));
    }

    @Test
    void stringifyEscapesJsonControlCharactersAndQuotes() {
        OresKeyedMap<String, OresJsonValue> object =
                OresJsonValue.objectMap();
        object.put(
                "text",
                OresJsonValue.of("a\\b\"c\n\t" + (char) 1));

        assertEquals(
                "{\"text\":\"a\\\\b\\\"c\\n\\t\\u0001\"}",
                AllocJsonCodec.stringifyObject(object));
    }
}
