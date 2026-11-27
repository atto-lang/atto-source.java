package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production parser admission only; no typechecker or guest certification. */
final class RecursiveRecordParserTest {
    private static Object parseBinding(String declaration) {
        return Parser.parseStrictAtto("pub fnc main(): void { " + declaration + "; return; }");
    }
    private static Object parse(String expression) {
        return parseBinding("val result = " + expression);
    }
    private static void rejectBinding(String declaration, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class, () -> parseBinding(declaration), declaration);
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }
    private static void reject(String expression, String diagnostic) {
        rejectBinding("val result = " + expression, diagnostic);
    }

    @Test void declarationLocalRecordForms() {
        assertEquals(parse("(five: 5, six: 8)"), parse("{five: 5, six: 8}"));
        assertEquals(parse("{five: 5, six: 8}"), parse("{five=5, six:=8}"));
        var typed = parseBinding("const Record x = {five:5, six:8}");
        for (String init : new String[]{"(five:5,six:8)", "(five=5,six=8)", "(five:=5,six:=8)", "{five:=5,six=8}", "(five:5,six:=8)"})
            assertEquals(typed, parseBinding("const Record x = "+init), init);
        assertEquals(parseBinding("const Record x = ()"),parseBinding("const Record x = {}"));
        assertDoesNotThrow(() -> parse("()"));
        assertNotEquals(parse("()"),parse("{five:5}"));
        reject("{}", "E_EMPTY_AGGREGATE");
        reject("(five=5)", "E_RECORD_CONTEXT");
        reject("(five:5,six:=8)", "E_LITERAL_KIND");
        reject("(five:5,8)", "E_LITERAL_KIND");
    }

    @Test void recursiveBracesAndCallBoundaries() {
        assertEquals(parse("{outer:{inner:42}}"), parse("{\"outer\":={\"inner\"=42}}"));
        assertEquals(parse("(outer: {inner:42})"), parse("{outer:{inner:42}}"));
        assertEquals(parse("({x:1},{y:2})"),parse("({\"x\"=1},{\"y\":=2})"));
        assertEquals(parse("foo({x:42})"),parse("foo({\"x\":=42})"));
        assertEquals(parse("foo(x=42)"),parse("foo(\"x\"=42)"));
        assertEquals(parse("foo(x:42)"),parse("foo(x=42)"));
        assertNotEquals(parse("foo(x=42)"),parse("foo({x:42})"));
        assertNotEquals(parse("foo({x:1,y:2})"),parse("foo({x:1},{y:2})"));
        assertEquals(parse("foo(outer={inner:42})"),parse("foo(\"outer\"={\"inner\":42})"));
        reject("foo((x:42))", "E_RECORD_CONTEXT");
        reject("foo((x=42))", "E_RECORD_CONTEXT");
        assertEquals(parse("foo(x:=42)"),parse("foo((x:=42))"));
        assertNotEquals(parse("foo(x:=42)"),parse("foo(x=42)"));
        assertNotEquals(parse("foo(x:=42)"),parse("foo({x:=42})"));
        assertNotEquals(parse("foo(x:=42)"),parse("foo((x:=42,))"));
        assertNotEquals(parse("foo(x:=1,y:=2)"),parse("foo((x:=1,y:=2))"));
        assertEquals(parse("foo(x:=42)"),parse("foo(\"x\":=42)"));
        reject("foo(n=1,x:=42)","positional arguments cannot follow named arguments");
        reject("foo(x: =42)","expected expression");
        reject("foo(x:/* gap */=42)","expected expression");
        reject("foo(x=(y=42))", "E_RECORD_CONTEXT");
        reject("foo(x=(obj.field=42))", "E_RECORD_FIELD");
        rejectBinding("const Record x=(a:1).a","E_RECORD_CONTEXT");
        reject("foo(x=1,{y:2})", "positional arguments cannot follow named arguments");
        reject("foo(x=1,\"x\"=2)", "duplicate named argument");
        reject("foo(\"x\"=1,\"\\u0078\"=2)", "duplicate named argument");
        rejectBinding("const Record x=(outer:=(inner:42))","E_RECORD_CONTEXT");
        rejectBinding("const Record x=(outer:=(inner=42))","E_RECORD_CONTEXT");
    }

    @Test void nominalProductNodesAndAssociativity() {
        assertEquals(parse("x:=42"),parse("(x:=42)"));
        assertEquals(parse("x:=42"),parse("\"x\":=42"));
        assertNotEquals(parse("x:=42"),parse("\"x\"=>42"));
        assertNotEquals(parse("x:=42"),parse("x=>42"));
        assertNotEquals(parse("x:=42"),parse("{x:42}"));
        assertNotEquals(parse("x:=42"),parse("(x:=42,)"));
        assertNotEquals(parse("x:=42"),parse("(42,5)"));
        assertDoesNotThrow(() -> parse("outer:=(inner:=42)"));
        assertDoesNotThrow(() -> parse("outer:={inner:42}"));
        assertDoesNotThrow(() -> parse("{outer:inner:=42}"));
        assertDoesNotThrow(() -> parse("(x:=1,y:=2)"));
        assertDoesNotThrow(() -> parse("foo(\"x\"=>42)"));
        assertDoesNotThrow(() -> parse("foo(x:=1,n=2)"));
        reject("x:=y:=42","E_PRODUCT_CHAIN");
        reject("x:=1=>42","E_PRODUCT_CHAIN");
        reject("1=>x:=42","E_PRODUCT_CHAIN");
        reject("1=>2=>3","E_PRODUCT_CHAIN");
        reject("(x+1):=42","E_PRODUCT_KEY");
    }

    @Test void executableNominalProductGateRemainsClosed() {
        // These are explicitly staging checks, not conformance passes.
        for (String expression : new String[]{"x:=42", "(x:=42)", "\"x\"=>42",
                "(x:=42,)", "{x:(y:=42)}"}) {
            String src = "pub fnc main(): void { val result = " + expression + "; return; }";
            assertDoesNotThrow(() -> Parser.parseStrictAtto(src));
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(src), expression);
            assertTrue(failure.getMessage().contains("E_NOMINAL_PRODUCT_UNSUPPORTED"),failure::getMessage);
        }
    }

    @Test void staticLabelsAndMixedKindRejection() {
        assertEquals(parse("{outer_value2:42}"), parse("{\"outer_value2\"=42}"));
        assertEquals(parse("{x:42}"),parse("{\"x\" /* comment */ : 42}"));
        for (String label : new String[]{"", "2x", "x-y", "x y", "x ", "x.y", "x/*c*/", "return", "x\\n"}) {
            reject("{\"" + label + "\":42}","E_RECORD_LABEL");
            reject("foo(\"" + label + "\"=42)","E_RECORD_LABEL");
        }
        reject("{'x':42}","E_RECORD_LABEL");
        reject("{\"x\":1,x:=2}","E_DUPLICATE");
        reject("{\"x\":1,:x}","E_DUPLICATE");
        reject("{\"x\":1,\"\\u0078\"=2}","E_DUPLICATE");
        reject("{x:1,\"y\"=>2}","E_LITERAL_KIND");
        assertEquals(parse("Record.of(x=42)"),parse("Record.of(\"x\"=42)"));
        reject("Record.of(\"x-y\":42)","E_RECORD_LABEL");
        reject("Record.of(\"x-y\"=42)","E_RECORD_LABEL");
        reject("{x: =42}","expected expression");
        assertEquals(parse("{x:=42}"),parse("{x:42}"));
    }

    @Test void typedMapAndOrdinaryTupleControls() {
        assertDoesNotThrow(() -> parseBinding("const Map<string,int> m={}"));
        assertDoesNotThrow(() -> parseBinding("const Map<string,int> m={\"x-y\"=>42}"));
        assertEquals(parse("{\"name\"=>42}"),parse("Map.of<string,int>(\"name\"=>42)"));
        assertNotEquals(parse("{\"name\"=>42}"),parse("{\"name\":42}"));
        assertDoesNotThrow(() -> parse("{\"name\"=>\"Alice\",\"other\"=>\"Bob\"}"));
        assertDoesNotThrow(() -> parse("{#ready=>true,#done=>false}"));
        reject("{\"name\"=>42,\"other\"=>true}","E_MAP_INFERENCE");
        reject("{1=>42}","E_MAP_INFERENCE");
        reject("{key=>42}","E_MAP_INFERENCE");
        reject("{\"x\"=>1,y:2}","E_LITERAL_KIND");
        reject("{\"x\"=>1=>2}","E_PRODUCT_CHAIN");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(
                "pub fnc main(): void { const m = {\"alpha\"=>1,\"beta\"=>2}; return; }"));
        rejectBinding("const Map<string,int> m={\"x\"=>1,y:2}","E_LITERAL_KIND");
        assertEquals(parse("(1)"),parse("1"));
        assertNotEquals(parse("(1,)"),parse("1"));
        assertNotEquals(parse("({x:1},)"),parse("{x:1}"));
        assertEquals(parse("(x:1,)"),parse("{x:1}"));
    }
}
