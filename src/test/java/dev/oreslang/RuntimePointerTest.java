package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class RuntimePointerTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 7;
            end
            """;

    private void check(String body) {
        TypeChecker.check(Parser.parse(BOX + " fnc test(): void { " + body + " return; }"));
    }

    private void reject(String body, String message) {
        var error = assertThrows(IllegalArgumentException.class, () -> check(body));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    @Test void runtimeHandlesCannotNestOrContainNull() {
        Object pointee = new Object();
        var pointer = new dev.oreslang.runtime.OresPointer(pointee);
        // Dereference borrows the existing object: it does not clone the pointee.
        assertSame(pointee, pointer.target());
        assertSame(pointer.target(), pointer.target());
        assertThrows(NullPointerException.class,
                () -> new dev.oreslang.runtime.OresPointer(null));
        var nested = assertThrows(IllegalArgumentException.class,
                () -> new dev.oreslang.runtime.OresPointer(pointer));
        assertTrue(nested.getMessage().contains("nested Pointer<T>"), nested.getMessage());
    }

    @Test void hostCannotManufacturePointerOwnershipForRuntimeCapabilities() {
        for (Object capability : java.util.List.of(
                new dev.oreslang.runtime.ChannelRuntime.Channel<>(1),
                dev.oreslang.runtime.OresMutex.local(7),
                new java.util.concurrent.CompletableFuture<Integer>())) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new dev.oreslang.runtime.OresPointer(capability));
            assertTrue(error.getMessage().contains("cannot own"), error.getMessage());
        }
    }

    @Test void namedAndFreshTargetsHaveCheckedRoundTrips() {
        for (String access : new String[] {"unref", "deref"}) {
            check("const Pointer<Box> p = rt ref new Box(); const Box view = rt " + access + " p; stdio.println(view.value);");
            check("val Box box = new Box(); const Pointer<Box> p = rt ref(box); const Box view = rt " + access + "(p); stdio.println(view.value);");
            check("const p = rt ref struct{value: int}{value: 7}; const view = rt " + access + " p; stdio.println(view.value);");
            check("const Pointer<{}> p = rt ref struct{}{}; const {} view = rt " + access + " p;");
        }
    }

    @Test void referencesRetainFreshValuesInBothEvaluatorPaths() throws Exception {
        for (String callable : new String[] {"routine", "async fnc"}) {
            String code = BOX + """
                    pub %s main(): void {
                      const Pointer<Box> p = rt ref new Box();
                      process.gc();
                      const Box first = rt unref p;
                      const Box second = rt deref(p);
                      stdio.stdout.write(first.value + second.value);
                      const q = rt ref struct{value: int}{value: 11};
                      const record = rt unref q;
                      stdio.stdout.write(record.value);
                      return;
                    }
                    """.formatted(callable);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder("ores").allowAllAccess(true).out(out).build()) {
                context.eval("ores", code);
            }
            assertEquals("1411", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test void refTransfersOwnershipAndCannotUpgradeBorrowing() {
        reject("val Box box = new Box(); const p = rt ref box; stdio.println(box.value);", "moved");
        reject("val Box box = new Box(); const view = rt borrow box; const p = rt ref view;", "Pointer<T>");
        reject("const p = rt ref 7;", "Pointer<T>");
        reject("const p = rt ref new Box(); const q = rt ref p;", "Pointer<T>");
        reject("const p = new Pointer<Box>();", "rt ref");
    }

    @Test void unsupportedExclusivePointerFormsAreRejectedBeforeTypeChecking() {
        for (String operation : new String[] {"ref", "unref", "deref"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(BOX + "fnc bad(): void { const p = rt " + operation
                            + " mut value; return; }"));
            assertTrue(error.getMessage().contains("exclusive Pointer<T>"), error.getMessage());
        }
    }

    @Test void pointerOperationsRequireCorrectArityAndType() {
        for (String access : new String[] {"ref", "unref", "deref"}) {
            reject("const p = rt " + access + "();", "argument");
            reject("const p = rt " + access + "(1, 2);", ")");
        }
        reject("const p = rt unref new Box();", "Pointer<T>");
        reject("const p = rt deref 1;", "Pointer<T>");
        reject("const p = rt unref(rt ref new Box());", "named owner");
    }

    @Test void viewsCannotMutateOrManufactureOwnership() {
        for (String op : new String[] {"unref", "deref"}) {
            String setup = "const p = rt ref new Box(); val Box view = rt " + op + " p; ";
            reject(setup + "view.value = 9;", "borrow");
            reject(setup + "const owned = rt take view;", "borrow");
            reject(setup + "const moved = rt take p;", "borrow");
            reject(setup + "block { const alias = view; } const moved = rt take p;", "borrow");
        }
        check("const p = rt ref new Box(); block { const Box view = rt unref p; } const moved = rt take p;");
        reject("const p = rt ref new Box(); const copied = rt copy p;", "rt copy");
        reject("const p = rt ref new Box(); const shared = rt share p;", "rt share");
    }

    @Test void viewsCannotEscapeTheirOwner() {
        var e = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + """
                fnc bad(): borrow Box {
                  const p = rt ref new Box();
                  return rt unref p;
                }
                """)));
        assertTrue(e.getMessage().contains("borrow"), e.getMessage());
        reject("const p = rt ref new Box(); const view = rt unref p; const f = || -> { stdio.println(view.value); return; };", "borrow");
        reject("const p = rt ref new Box(); const view = rt unref p; rt cooperate;", "borrow");
    }

    @Test void nestedViewsCannotLeakMutableChildren() {
        reject("const p = rt ref struct{child: Box}{child: new Box()}; const view = rt unref p; val child = view.child; child.value = 9;", "borrow");
        reject("const p = rt ref struct{child: Box}{child: new Box()}; const view = rt unref p; const child = rt ref view.child;", "projected");
    }

    @Test void castsAndAnnotationsCannotEraseBorrowProvenance() {
        reject("const p = rt ref new Box(); const view = rt unref p; val Box alias = view as Box; alias.value = 9;", "borrow");
        reject("const p = rt ref new Box(); const view = rt unref p; const Box alias = view as Box; const q = rt ref alias;", "borrow");
        reject("const p = rt ref struct{child: Box}{child: new Box()}; const view = rt unref p; val {child} = view; child.value = 9;", "borrow");
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + """
                fnc mutate(Box mut box): void { box.value = 9; return; }
                fnc bad(): void {
                  const p = rt ref new Box();
                  const view = rt unref p;
                  mutate(view as Box);
                  return;
                }
                """)));
        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
    }

    @Test void callBorrowsLastThroughLaterArgumentEvaluation() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + """
                fnc read(borrow Box view): int { return view.value; }
                fnc ok(): void {
                  const p = rt ref new Box();
                  const int n = read(rt unref p);
                  const moved = rt take p;
                  return;
                }
                """)));
        for (String access : new String[] {"unref", "deref"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + """
                    fnc read(borrow Box view, Pointer<Box> moved): void { return; }
                    fnc bad(): void {
                      const p = rt ref new Box();
                      read(rt %s p, rt take p);
                      return;
                    }
                    """.formatted(access))));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
    }

    @Test void collectionReadsAndAliasesCannotMintOwningPointers() {
        reject("val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); const p = rt ref boxes.get(0);", "projected");
        reject("val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); const p = rt ref boxes; const view = rt unref p; val child = view.get(0); child.value = 9;", "borrow");
        reject("val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); const p = rt ref boxes; const view = rt unref p; val child = view[0]; child.value = 9;", "borrow");
    }

    @Test void pointerTypeCannotBeRedefinedOrTransported() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("define class Pointer as end")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + "fnc bad(Channel<Pointer<Box>> messages): void { return; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(BOX + "fnc bad(SharedMutex<Pointer<Box>> value): void { return; }")));
        reject("const Pointer<int> p = rt ref 7;", "Pointer<T>");
    }

    @Test void recursivePointerTargetGraphsFailWithADiagnostic() {
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Node as
                  pub val Pointer<Node> next;
                end
                """)));
        assertTrue(error.getMessage().contains("Pointer<T>"), error.getMessage());
    }

    @Test void expressionsCannotHidePointerViewLifetimes() {
        reject("const p = rt ref new Box(); const view = rt unref p; const hidden = true ? (view as Box) : (view as Box); const moved = rt take p;", "borrow");
        reject("const p = rt ref new Box(); const hidden = true ? (rt unref p) : (rt deref p); const moved = rt take p;", "borrow");
        reject("const p = rt ref new Box(); const hidden = (rt unref p) as? Box; const moved = rt take p;", "borrow");
        reject("const p = rt ref new Box(); const hidden = true ? ((rt unref p) as Box) : ((rt deref p) as Box);", "borrow");
        check("const p = rt ref new Box(); const n = true ? (rt unref p).value : (rt deref p).value; const moved = rt take p;");
    }

    @Test void structuralCallsRetainTemporaryPointerViews() {
        String prefix = BOX + """
                define interface Shape
                  value: int;
                end
                fnc read(structural Shape view, Pointer<Box> moved): void { return; }
                fnc inspect(structural Shape view): int { return view.value; }
                """;
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(prefix + """
                fnc bad(): void {
                  const p = rt ref new Box();
                  read((rt unref p) as Box, rt take p);
                  return;
                }
                """)));
        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(prefix + """
                fnc ok(): void {
                  const p = rt ref new Box();
                  const n = inspect((rt unref p) as Box);
                  const moved = rt take p;
                  return;
                }
                """)));
    }

    @Test void firstClassCallRetainsTemporaryPointerViews() {
        reject("const Fnc<Box, Pointer<Box>, void> f = |Box view, Pointer<Box> moved| -> { return; }; const p = rt ref new Box(); f((rt unref p) as Box, rt take p);", "borrow");
        check("const Fnc<Box, void> f = |Box view| -> { return; }; const p = rt ref new Box(); f((rt unref p) as Box); const moved = rt take p;");
    }

    @Test void symbolicPointerAndBorrowSyntaxIsRejected() {
        for (String expression : new String[] {"&box", "&mut box", "*box"}) {
            var e = assertThrows(IllegalArgumentException.class, () -> Parser.parse(BOX + "fnc bad(): void { const p = " + expression + "; return; }"));
            assertTrue(e.getMessage().contains("symbolic"), e.getMessage());
        }
        for (String type : new String[] {"&Box", "&mut Box", "*Box", "Box*"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(BOX + "fnc bad(" + type + " p): void { return; }"));
        }
        check("const int n = 3 * 4; const int bits = 7 & 3; const bool b = true && false;");
    }
}
