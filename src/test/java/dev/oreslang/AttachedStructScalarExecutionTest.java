package dev.oreslang;
import dev.oreslang.parser.AttachedStructScalarLowering;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class AttachedStructScalarExecutionTest {
    private static final String SOURCE = """
define struct Point { int x; int y; } with {
 constructor(int x, int y) { self.x = x; self.y = y; }
 mtd int • sum() { return self.x + self.y; }
 mtd int <- product() { return self.x * self.y; }
 mtd is_origin() -> bool { return self.x == 0 && self.y == 0; }
}
define struct Rectangle { Point a; Point b; } with {
 constructor(Point a, Point b) { self.a = a; self.b = b; }
 mtd area() -> int { return (self.b.x - self.a.x) * (self.b.y - self.a.y); }
}
pub fnc main():int { let p = Point(1, 2); let q = Point(4, 6); let Rectangle r = Rectangle(p,q); let snapshot = copy! r; return snapshot.area() + p.sum() + q.product() + 3; }

""";
    private static final String MUTABLE = """
define struct Point { int x; int y; } with {
 constructor(int x, int y) { self.x = x; self.y = y; }
 mut mtd shift(int n):void { self.x = self.x + n; }
 mtd int • sum() { return self.x + self.y; }
 mtd int <- product() { return self.x * self.y; }
 mtd is_origin() -> bool { return self.x == 0 && self.y == 0; }
}
define struct Rectangle { Point a; Point b; } with {
 constructor(Point a, Point b) { self.a = a; self.b = b; }
 mut mtd shift(int n):void { self.a.x = self.a.x + n; self.b.y = self.b.y + n; }
 mtd area() -> int { return (self.b.x - self.a.x) * (self.b.y - self.a.y); }
}
pub fnc main():int { let mut p = Point(1, 2); let q = Point(4, 6); let mut Rectangle r = Rectangle(p,q); let snapshot = copy! r; p.shift(10); let before = Point(1,2); r.shift(1); return snapshot.area() * 100 + r.area() * 10 + p.sum() + before.sum(); }

""";
    @Test void mutableNestedSnapshotsExecuteIndependently() {
        String lowered=AttachedStructScalarLowering.lowerChecked(MUTABLE);
        assertEquals(lowered,AttachedStructScalarLowering.lowerChecked(MUTABLE));
        try(Context context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()){
            var result=context.eval(Source.newBuilder(OresLanguage.ID,lowered,"attached-mutable.atto").mimeType(OresLanguage.MIME_TYPE).buildLiteral());
            assertEquals(1316L,result.asLong());
        }
        for(String bad:new String[]{
            MUTABLE.replace("let mut Rectangle","let Rectangle"),
            MUTABLE.replace("r.shift(1)","r.shift(true)"),
            MUTABLE.replace("self.a.x = self.a.x + n","self.a.z = self.a.x + n"),
            MUTABLE.replace("p.shift(10);","let invalid = p.shift(10);"),
            MUTABLE.replace("mut mtd shift(int n):void { self.x","mtd shift(int n):void { self.x")
        })assertThrows(IllegalArgumentException.class,()->AttachedStructScalarLowering.lowerChecked(bad),bad);
    }
    @Test void orderedMutationAndScalarConstructorArguments() {
        String source="define struct Flag { bool value; } with { constructor(bool value) { self.value = value; } }\n"+
            MUTABLE.replace("p.shift(10);","p.shift(10); let f = Flag(p.is_origin()); let g = Flag(false && p.is_origin());")
                   .replace("r.shift(1)","r.shift(r.a.x)")
                   .replace("self.b.y = self.b.y + n;","self.b.y = self.b.y + self.a.x;");
        String lowered=AttachedStructScalarLowering.lowerChecked(source);
        try(Context context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()){
            var result=context.eval(Source.newBuilder(OresLanguage.ID,lowered,"attached-ordered.atto").mimeType(OresLanguage.MIME_TYPE).buildLiteral());
            assertEquals(1336L,result.asLong());
        }
    }
    @Test void actualCheckedJavaGuest() {
        String lowered=AttachedStructScalarLowering.lowerChecked(SOURCE);
        assertEquals(lowered,AttachedStructScalarLowering.lowerChecked(SOURCE));
        assertFalse(lowered.contains("new "));
        try(Context context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()){
            var result=context.eval(Source.newBuilder(OresLanguage.ID,lowered,"attached-scalar.atto").mimeType(OresLanguage.MIME_TYPE).buildLiteral());
            assertEquals(42L,result.asLong());
        }
    }
    @Test void malformedOrUnsupportedNeverReturnsScalarArtifact() {
        for(String bad:new String[]{
            SOURCE.replace("let Rectangle r = Rectangle(p,q)","let Point r = Rectangle(p,q)"),
            SOURCE.replace("self.b = b;",""),
            SOURCE.replace("Point a;","Rectangle a;"),
            SOURCE.replace("self.b = b;","self.a = b; self.b = b;"),
            SOURCE.replace("self.b.x -","self.b.z -"),
            SOURCE.replace("mtd area()","mut mtd area()"),
            SOURCE+" fnc extra():int{return 0;}",
            SOURCE.replace("Rectangle(p,q)","Rectangle(p,42)"),
            SOURCE.replace("Point(1, 2)","Point(\"1\", 2)")
        })assertThrows(IllegalArgumentException.class,()->AttachedStructScalarLowering.lowerChecked(bad),bad);
        assertThrows(IllegalArgumentException.class,()->Parser.parse(SOURCE));
    }
}
