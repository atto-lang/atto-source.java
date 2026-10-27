package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InvertedArrayFailClosedTest {
    @Test
    void unsupportedMemberCannotReachGenericRuntimeDispatch() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.reserve(8);
                          return;
                        }
                        """));

        assertTrue(
                failure.getMessage().contains("v1 exposes only push"),
                failure::getMessage);
    }

    @Test
    void bareBracketProjectionCannotReachGenericIndexDispatch() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          particles[0];
                          return;
                        }
                        """));

        assertTrue(
                failure.getMessage().contains("row projection cannot escape"),
                failure::getMessage);
    }

    @Test
    void callableSignaturesCannotTransferOrWrapInvertedStorage() {
        String prefix = """
                define inverted class Particle as
                  val f64 x;
                end
                """;
        String[][] invalid = {
                {
                    "fnc bad(InvertedArray<Particle> particles): void { return; }",
                    "cannot receive InvertedArray storage by parameter"
                },
                {
                    "fnc bad(Future<InvertedArray<Particle>> pending): void { return; }",
                    "cannot receive InvertedArray storage by parameter"
                },
                {
                    "fnc bad(): InvertedArray<Particle> {"
                            + " return new InvertedArray<Particle>(); }",
                    "cannot return InvertedArray storage"
                },
                {
                    "type Hidden = InvertedArray<Particle>;"
                            + " fnc bad(Hidden particles): void { return; }",
                    "cannot receive InvertedArray storage by parameter"
                }
        };
        for (String[] input : invalid) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(prefix + input[0]),
                    input[0]);
            assertTrue(failure.getMessage().contains(input[1]),
                    () -> input[0] + ": " + failure.getMessage());
        }
    }

    @Test
    void runtimeOwnershipOperationsCannotLaunderInvertedStorage() {
        for (String operation : new String[] {"borrow", "take", "share", "copy"}) {
            String source = """
                    define inverted class Particle as
                      val f64 x;
                    end
                    fnc bad(): void {
                      let mut InvertedArray<Particle> particles =
                          new InvertedArray<Particle>();
                      val hidden = rt %s particles;
                      return;
                    }
                    """.formatted(operation);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source),
                    operation);
            assertTrue(
                    failure.getMessage().contains(
                            "rt " + operation + " cannot "),
                    failure::getMessage);
        }
    }


    @Test
    void alternateHostColumnAndRowOperationsStayGuestInaccessible() {
        for (String member : new String[] {
                "eraseSwap", "swap", "row", "snapshot", "borrowRead",
                "borrowWrite", "bindField", "index", "invoke",
                "updateRow", "pushValues"
        }) {
            String source = """
                    define inverted class Particle as
                      val f64 x;
                    end
                    fnc bad(): void {
                      let mut InvertedArray<Particle> particles =
                          new InvertedArray<Particle>();
                      particles.%s(0);
                      return;
                    }
                    """.formatted(member);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source),
                    member);
            assertTrue(failure.getMessage().contains("v1 exposes only push"),
                    () -> member + ": " + failure.getMessage());
        }
    }

}
