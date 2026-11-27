package dev.oreslang;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;
final class AttoStateValueLoopTest {
    private long run(String source) throws Exception {
        var out=new ByteArrayOutputStream(); var err=new ByteArrayOutputStream();
        try(var ctx=Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                .logHandler(System.err).out(out).err(err).build()) {
            var result=ctx.eval(Source.newBuilder(OresLanguage.ID,source,"recur.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
            assertTrue(result.fitsInLong()); assertEquals(0,out.size()); assertEquals(0,err.size());
            return result.asLong();
        }
    }
    private String wrap(String expression) { return "pub fnc main(): int { return "+expression+"; }"; }
    @Test void guests() throws Exception {
        assertEquals(45,run(wrap("loop (val i:int=0,val sum:int=0) { if i>=10 {break sum;} recur(i+1,sum+i); }")));
        assertEquals(42,run(wrap("loop (val i=0,val a=2,val b=a+40) {if i eq 1 {break a;} else {recur(i+1,b,a);} }")));
        assertEquals(49999995000000L,run(wrap("loop (val i=0,val sum=0) {if i>=10000000 {break sum;} recur(i+1,sum+i);}")));
        assertEquals(42,run("fnc f(int n):int {return n;} "+wrap("2+f(loop (val i=0) {if i eq 1 {break 40;} recur(i+1);})")));
        assertEquals(42,run(wrap("loop (val i=0) {if i eq 1 {break 42;} if i eq 1 {break 7;} recur(i+1);}")));
        assertEquals(42,run("pub fnc main():int {val n:int=loop (val i=0) {if i eq 1 {break 42;} recur(i+1);}; return n;}"));
    }
    @Test void rejects() {
        for(String expr:new String[]{
            "loop (val i=0) {if i eq 1 {break i;} recur(i+1,2);}",
            "loop (val a=b,val b=42) {if b eq 42 {break a;} recur(a,b);}",
            "loop (val i=0) {if i eq 1 {break i;} recur(i eq 0);}",
            "loop (val i=0) {if i {break i;} recur(i+1);}",
            "loop (val i=0) {if i eq 1 {break i;} recur(i+1); recur(i+1);}",
            "loop (val i=0) {if i eq 1 {break i;} recur(loop (val j=0) {if j eq 0 {break 1;} recur(j+1);});}"
        }) assertThrows(org.graalvm.polyglot.PolyglotException.class,()->run(wrap(expr)),expr);
    }
}
