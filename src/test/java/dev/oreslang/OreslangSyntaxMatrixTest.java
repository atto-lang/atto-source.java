package dev.oreslang;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

final class OreslangSyntaxMatrixTest {
    private record Sample(String name, String source) {}
    private static final List<Sample> PARSE_OK = List.of(
        new Sample("imports/wildcard", "import * as all from \"./pkg\"; fnc noop(): void {return;}"),
        new Sample("imports/modules", "import module {foo,bar} from \"../x\"; fnc noop(): void {return;}"),
        new Sample("imports/types", "import types (A,B) from \"../types\"; fnc noop(): void {return;}"),
        new Sample("imports/actors", "import actor Worker from \"../actors\"; fnc noop(): void {return;}"),
        new Sample("imports/class", "import class Box from \"../model\"; fnc noop(): void {return;}"),
        new Sample("imports/fnc", "import fnc * as callbacks from \"../fns\"; fnc noop(): void {return;}"),
        new Sample("namespace", "namespace syntax; fnc noop(): void {return;}"),
        new Sample("module/as", "define module M as pub fnc ping(): int {return 1;} end"),
        new Sample("module/multiple", "define module A fnc x(): int {return 1;} end define module B fnc x(): int {return 2;} end"),
        new Sample("module/contract", "define contract Api as fnc ping(): int; end define module M conforms Api as pub fnc ping(): int{return 1;} end"),
        new Sample("callable/fnc", "fnc f(int a): int{return a;}"),
        new Sample("callable/arrow", "fnc f(int a) -> int{return a;}"),
        new Sample("callable/routine", "routine work(): void{return;}"),
        new Sample("callable/pipe-fnc", "fnc twice = |int a| -> int {return a * 2;}"),
        new Sample("callable/pipe-routine", "routine idle = || -> void {return;}"),
        new Sample("callable/async", "async fnc work(): void{return;}"),
        new Sample("callable/generic", "fnc identity<T>(T value): T{return value;}"),
        new Sample("callable/shared-actor", "pub shared actor fnc worker(int v): int{return v;}"),
        new Sample("callable/private-actor", "pub isoactor routine worker(int v): int{return v;}"),
        new Sample("callable/annotation", "@Ret<int> fnc n(): int{return 1;}"),
        new Sample("callable/contextual-name", "fnc block(): void{return;} fnc loop(): void{return;}"),
        new Sample("type/alias", "type Count = int; fnc noop(): void{return;}"),
        new Sample("type/generic", "type Item<T> = T; fnc noop(): void{return;}"),
        new Sample("type/tuple", "type Pair = Tuple[string,int]; fnc noop(): void{return;}"),
        new Sample("type/record", "type Person = {id:string,active:bool}; fnc noop(): void{return;}"),
        new Sample("type/union", "type Either = int | string; fnc noop(): void{return;}"),
        new Sample("type/typeof", "type F = typeof fnc(int x) => int; fnc noop(): void{return;}"),
        new Sample("type/arrow", "type F = (int) => int; fnc noop(): void{return;}"),
        new Sample("type/borrow", "type Ref = borrow int; type MutRef = borrow mut int; fnc noop(): void{return;}"),
        new Sample("class/basic", "define class Box as pub val string name = \"ore\"; end"),
        new Sample("class/abstract", "define abstract class Base as end"),
        new Sample("class/inheritance", "define class Base as end define class Child extends Base as end"),
        new Sample("class/constructor", "pub define class Box as pub val int id; pub constructor(int n){self.id=n;} end"),
        new Sample("class/static", "define class Math as pub static fnc twice(int n):int{return n*2;} end"),
        new Sample("interface/basic", "define interface Reader as fnc read() => string; end"),
        new Sample("interface/extends", "define interface Reader as fnc read()=>string; end define interface Child extends Reader as end"),
        new Sample("trait/fields", "define trait Named as id:string; fnc ping()=>void; end"),
        new Sample("actor/shared", "shared actor Counter { let count=0; pub fnc read():int{return self.count;} }"),
        new Sample("actor/plain", "actor Counter { let count=0; pub fnc read():int{return self.count;} }"),
        new Sample("actor/final-field", "actor Counter { final epoch:int=1; pub fnc read():int{return self.epoch;} }"),
        new Sample("binding/const-val-let", "fnc f():void{const n=1; val x=2; let y=3; y=y+n;return;}"),
        new Sample("binding/type-first", "fnc f():void{const int n=1;return;}"),
        new Sample("binding/type-last", "fnc f():void{const n:int=1;return;}"),
        new Sample("binding/infer-struct", "fnc f():void{const T row=infer<T> struct{id:\"x\"};return;}"),
        new Sample("binding/infer-tuple", "fnc f():void{const T row=infer<T> tuple (\"x\",1);return;}"),
        new Sample("value/inferred-struct", "fnc f():void{const row=infer struct {id:\"x\"};return;}"),
        new Sample("value/tuple", "fnc f():void{const pair=tuple (\"x\",1);return;}"),
        new Sample("value/tuple-parens", "fnc f():void{const pair=(\"x\",1);return;}"),
        new Sample("value/array", "fnc f():void{const a=arr[1,2];return;}"),
        new Sample("value/legacy-list", "fnc f():void{const a=[1,2];return;}"),
        new Sample("value/lambda", "fnc f():void{const callback=|x| -> x+1;return;}"),
        new Sample("value/zero-lambda", "fnc f():void{const callback=|| -> {return;};return;}"),
        new Sample("control/if-then-fi", "fnc f(bool c):int{if c; then return 1; else return 2; fi}"),
        new Sample("control/if-do-fi", "fnc f(bool c):int{if c; do return 1; else return 2; fi}"),
        new Sample("control/elif", "fnc f(bool c,bool b):int{if c; then return 1; elif b; then return 2; else return 0; fi}"),
        new Sample("control/elseif", "fnc f(bool c,bool b):int{if c; then return 1; elseif b; then return 2; else return 0; fi}"),
        new Sample("control/else-if", "fnc f(bool c,bool b):int{if c; then return 1; else if b; then return 2; else return 0; fi}"),
        new Sample("control/block", "fnc f():void{block {val v=1;} return;}"),
        new Sample("control/loop-break", "fnc f():void{loop {break;} return;}"),
        new Sample("control/for-c", "fnc f():void{for(let i=0;i<2;i++){ }return;}"),
        new Sample("control/for-of", "fnc f():void{for const item of arr[1,2] { }return;}"),
        new Sample("control/match", "fnc f(int x):int{match x over on 1 -> {return 1;} on _ -> {return 0;} end}"),
        new Sample("control/match-tuple", "fnc f():int{match (1,2) over on (1,2) -> {return 1;} on (_,_) -> {return 0;} end}"),
        new Sample("control/switch", "fnc f(int n):int{switch n case 1 -> {return 1;} default -> {return 0;} end}"),
        new Sample("control/try-catch", "fnc f():void{try {val x=1;} catch(e) {return;} finally {val y=2;}return;}"),
        new Sample("control/defer", "fnc f():void{defer stdio.println(\"done\");return;}"),
        new Sample("channels/read-write", "fnc f():void{val Channel<int> c=Channel.new<int>(1);writech c,1;val n=readch c;return;}"),
        new Sample("channels/select-default", "fnc f():void{do select {default: {return;}}return;}"),
        new Sample("destructure/tuple", "fnc f():void{[const a,_,let b]=(1,2,3);return;}")
    );
    private static final List<Sample> PARSE_BAD = List.of(
        new Sample("declaration/runtime-module", "fnc f():void{define module M end return;}"),
        new Sample("declaration/runtime-class", "fnc f():void{define class M as end return;}"),
        new Sample("declaration/runtime-namespace", "fnc f():void{namespace nested;return;}"),
        new Sample("declaration/shared-no-actor", "shared fnc f():void{return;}"),
        new Sample("declaration/bad-arrow", "fnc f() => void {return;}"),
        new Sample("bindings/infer-val", "fnc f():void{val T x=infer<T> struct{id:\"x\"};return;}"),
        new Sample("bindings/infer-mismatch", "fnc f():void{const A x=infer<B> tuple (1,2);return;}"),
        new Sample("bindings/infer-unsupported", "fnc f():void{const T x=infer<T> bad();return;}"),
        new Sample("bindings/reserved-new", "fnc f():void{val new=1;return;}"),
        new Sample("control/if-end", "fnc f():void{if true; then return; end}"),
        new Sample("control/match-default", "fnc f(int n):void{match n over default -> {return;} end}"),
        new Sample("control/match-no-on", "fnc f(int n):void{match n over 1 -> {return;} end}"),
        new Sample("control/match-list", "fnc f(List<int> xs):void{match xs over on [_,_] -> {return;} end}")
    );
    private static final List<Sample> TYPE_OK = List.of(
        new Sample("named struct", "fnc f():string{const T row=infer<T> struct{id:\"x\"};const T copy=row;return copy.id;}"),
        new Sample("named tuple", "fnc f():int{const T row=infer<T> tuple (\"x\",3);return row[1];}"),
        new Sample("readonly nested", "fnc f():string{const T row=infer<T> struct{nested:infer struct{id:\"x\"}};return row.nested.id;}"),
        new Sample("math", "fnc f():int{return (2+3)*4;}"),
        new Sample("ternary", "fnc f(bool cond):int{return cond?1:2;}"),
        new Sample("let reassignment", "fnc f():int{let n=1;n=n+1;return n;}"),
        new Sample("underscore destructure", "fnc f():int{[_,const v]=(1,2);return v;}"),
        new Sample("shared actor", "shared actor Counter {let balance=1; pub fnc value():int{return self.balance;}}")
    );
    private static final List<Sample> TYPE_BAD = List.of(
        new Sample("readonly", "fnc bad():void{const T row=infer<T> struct{id:\"x\"};row.id=\"bad\";return;}"),
        new Sample("tuple OOB", "fnc bad():int{const T row=infer<T> tuple (\"x\",2);return row[2];}"),
        new Sample("tuple negative", "fnc bad():int{const T row=infer<T> tuple (\"x\",2);return row[-1];}"),
        new Sample("tuple position", "fnc bad():string{const T row=infer<T> tuple (\"x\",2);return row[1];}"),
        new Sample("tuple arity", "fnc bad():void{const T row=infer<T> tuple (\"x\",2);const T other=tuple (\"x\",2,3);return;}"),
        new Sample("val reassign", "fnc bad():void{val x=1;x=2;return;}"),
        new Sample("null", "fnc bad():void{val n=null;return;}")
    );
    @TestFactory Stream<DynamicTest> parserAdmitsSupportedForms() {
        return PARSE_OK.stream().map(s -> DynamicTest.dynamicTest(s.name(),
            () -> assertDoesNotThrow(() -> Parser.parse(s.source()), s.source())));
    }
    @TestFactory Stream<DynamicTest> parserRejectsUnsupportedForms() {
        return PARSE_BAD.stream().map(s -> DynamicTest.dynamicTest(s.name(),
            () -> assertThrows(IllegalArgumentException.class, () -> Parser.parse(s.source()), s.source())));
    }
    @TestFactory Stream<DynamicTest> typeCheckerAcceptsSupportedForms() {
        return TYPE_OK.stream().map(s -> DynamicTest.dynamicTest(s.name(),
            () -> assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(s.source())), s.source())));
    }
    @TestFactory Stream<DynamicTest> typeCheckerRejectsInvalidForms() {
        return TYPE_BAD.stream().map(s -> DynamicTest.dynamicTest(s.name(),
            () -> assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(s.source())), s.source())));
    }
}
