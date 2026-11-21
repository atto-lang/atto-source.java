package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The first executable Oreslang guest Region<T> subset: only Region<Motion>
 * with four double fields, opaque handles, and synchronous root operations.
 */
final class RegionMotionGuestV0Test {
    private static String execute(String guest) throws Exception {
        TypeChecker.check(Parser.parse(guest));
        var out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "region-motion-v0.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test void compiledGuestRunsSevenRealResidencyCombinations() throws Exception {
        for (int mask = 1; mask <= 7; mask++) {
            int first = (mask & 1) != 0 ? 0 : (mask & 2) != 0 ? 1 : 2;
            int last = (mask & 4) != 0 ? 2 : (mask & 2) != 0 ? 1 : 0;
            String guest = """
                    pub fnc main(): void {
                        let region = Region.new<Motion>(3, 2, %d);
                        let row = region.insert(10.0, 20.0, 1.0, 2.0);
                        stdio.stdout.write(region.get_x(row));
                        region.set_layout(%d);
                        region.set(row, 15.0, 25.0, 1.0, 2.0);
                        region.set_layout(%d);
                        stdio.stdout.write("|");
                        stdio.stdout.write(region.get_y(row));
                        stdio.stdout.write("|");
                        stdio.stdout.write(region.size());
                        region.erase_swap(row);
                        stdio.stdout.write("|");
                        stdio.stdout.write(region.size());
                        region.close();
                        return;
                    }
                    """.formatted(mask, last, first);
            assertEquals("10.0|25.0|1|0", execute(guest), "residency mask " + mask);
        }
    }

    @Test void explicitRegionAndOpaqueRowHandleTypeAnnotationsWork() throws Exception {
        String guest = """
                pub fnc main(): void {
                    let Region<Motion> region = Region.new<Motion>(3, 2, 7);
                    let RegionHandle row = region.insert(4.0, 8.0, 1.0, 2.0);
                    region.set_layout(2);
                    stdio.stdout.write(region.get_x(row));
                    region.set_layout(0);
                    stdio.stdout.write("|");
                    stdio.stdout.write(region.get_y(row));
                    region.close();
                    return;
                }
                """;
        assertEquals("4.0|8.0", execute(guest));
    }

    @Test void invalidRegionConstructionAndUnknownMethodsFailStaticAdmission() {
        for (String body : new String[] {
                "let region = Region.new<Other>(2, 2, 1);",
                "let region = Region.new<Motion>(2, 2, 1.5);",
                "let region = Region.new<Motion>(2, 2);",
                "let region = Region.new<Motion>(2, 2, 1); region.unsafe_mutate();",
                "let region = Region.new<Motion>(2, 2, 1); region.insert(1.0, 2.0);"
        }) {
            String guest = "pub fnc main(): void { " + body + " return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), body);
        }
    }

    @Test void constructorBoundsAndForeignHandlesRejectAtRuntime() {
        for (String guest : new String[] {
                "pub fnc main(): void { let r = Region.new<Motion>(2, 2, 0); return; }",
                "pub fnc main(): void { let r = Region.new<Motion>(2, 2, 8); return; }",
                "pub fnc main(): void { let r = Region.new<Motion>(4097, 2, 1); return; }",
                "pub fnc main(): void { let r = Region.new<Motion>(0, 2, 1); return; }",
                """
                pub fnc main(): void {
                    let a = Region.new<Motion>(2, 2, 1);
                    let b = Region.new<Motion>(2, 2, 1);
                    let handle = a.insert(1.0, 2.0, 3.0, 4.0);
                    b.get_x(handle);
                    return;
                }
                """,
                """
                pub fnc main(): void {
                    let r = Region.new<Motion>(2, 2, 1);
                    let handle = r.insert(1.0, 2.0, 3.0, 4.0);
                    r.close();
                    r.get_x(handle);
                    return;
                }
                """
        }) {
            assertThrows(RuntimeException.class, () -> execute(guest), guest);
        }
    }

    @Test void absentLayoutAndStaleHandleFailAtRuntime() {
        String badLayout = """
                pub fnc main(): void {
                    let region = Region.new<Motion>(2, 2, 1);
                    region.set_layout(2);
                    return;
                }
                """;
        assertThrows(RuntimeException.class, () -> execute(badLayout));

        String stale = """
                pub fnc main(): void {
                    let region = Region.new<Motion>(2, 2, 3);
                    let row = region.insert(1.0, 2.0, 3.0, 4.0);
                    region.erase_swap(row);
                    region.get_x(row);
                    return;
                }
                """;
        assertThrows(RuntimeException.class, () -> execute(stale));
    }
}
