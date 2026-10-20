package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralKeywordMatrixTest {
    @Test
    void keywordProjectsTraitsInterfacesRecordsStructsEntryAndPair() {
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define trait HasId as
                  id: int;
                end

                define interface HasName
                  name: string;
                end

                define class Candidate as
                  pub val int id = 7;
                  pub val string name = "class";
                  pub val bool extra = true;
                end

                fnc from_trait(structural HasId value): int {
                  return value.id;
                }

                fnc from_interface(structural HasName value): string {
                  return value.name;
                }

                fnc from_record(structural { id: int } value): int {
                  return value.id;
                }

                fnc from_struct(structural struct{ id: int } value): int {
                  return value.id;
                }

                fnc from_entry(structural Entry<int, string> value): string {
                  return value.value;
                }

                fnc from_pair(structural Tuple<int, string> value): string {
                  return value[1];
                }

                fnc exercise(): string {
                  const row = record{id: 11, name: "record"};
                  const Point point = infer<Point> struct{id: 12, name: "struct"};
                  const kv = record{key: 1, value: "record-entry", extra: true};
                  const pair = tuple {2, "pair"};
                  const int a = from_trait(new Candidate());
                  const string b = from_interface(new Candidate());
                  const int c = from_record(point);
                  const int d = from_struct(row);
                  const string e = from_entry(kv);
                  const string f = from_pair(pair);
                  return b + e + f;
                }
                """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void entryRemainsNominalWithoutStructuralButCanSatisfyStructuralKeyValueContract() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc nominal(Entry<int, string> value): string { return value.value; }
                fnc bad(): string {
                  return nominal(record{key: 1, value: "not-entry"});
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define trait KeyValue as
                  key: int;
                  value: string;
                end

                fnc structural_read(structural KeyValue value): string {
                  return value.value;
                }

                fnc ok(): string {
                  return structural_read(Entry.of(1, "entry"));
                }
                """)));
    }

    @Test
    void structuralPatternBindingNeverRecoversNominalOrWriteAuthority() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Contract as
                          pub val int id = 1;
                        end
                        define class Root as end
                        define class Candidate extends Root as
                          pub val int id = 2;
                        end

                        fnc bad(Root input): void {
                          match input over
                            on structural Contract view -> {
                              view.id = 9;
                              return;
                            }
                            on _ -> { return; }
                          end
                        }
                        """)));
        assertTrue(
                rejected.getMessage().toLowerCase().contains("readonly")
                        || rejected.getMessage().toLowerCase().contains("read-only"),
                rejected.getMessage());
    }

    @Test
    void runtimeMatchesClassStructEntryTraitAndTupleComposition() throws Exception {
        String program = """
                define module app
                  define class Root as end
                  define class BaseReturn as end
                  define class DerivedReturn extends BaseReturn as end
                  define class DerivedArg extends Root as end

                  define class Contract as
                    pub val int id = 1;
                    pub handle(DerivedArg value): BaseReturn {
                      return new BaseReturn();
                    }
                  end

                  define class Candidate extends Root as
                    pub val int id = 7;
                    pub handle(Root value): DerivedReturn {
                      return new DerivedReturn();
                    }
                  end

                  define trait KeyValue as
                    key: int;
                    value: string;
                  end

                  fnc class_case(Root value, DerivedArg arg): int {
                    match value over
                      on structural Contract shaped -> {
                        shaped.handle(arg);
                        return shaped.id;
                      }
                      on _ -> { return 0; }
                    end
                  }

                  fnc struct_case(): int {
                    const Wide value = infer<Wide> struct{id: 8, extra: 9};
                    match value over
                      on structural struct{ id: int } shaped -> {
                        return shaped.id;
                      }
                      on _ -> { return 0; }
                    end
                  }

                  fnc entry_target_case(): string {
                    const row = record{key: 3, value: "shape", extra: true};
                    match row over
                      on structural Entry<int, string> shaped -> {
                        return shaped.value;
                      }
                      on _ -> { return "miss"; }
                    end
                  }

                  fnc entry_actual_case(): string {
                    const value = Entry.of(4, "entry");
                    match value over
                      on structural KeyValue shaped -> {
                        return shaped.value;
                      }
                      on _ -> { return "miss"; }
                    end
                  }

                  fnc pair_case(structural Tuple<int, string> pair): string {
                    match pair over
                      on (_, string value) -> { return value; }
                    end
                  }

                  pub fnc main(): void {
                    stdio.println(class_case(new Candidate(), new DerivedArg()));
                    stdio.println(struct_case());
                    stdio.println(entry_target_case());
                    stdio.println(entry_actual_case());
                    stdio.println(pair_case(tuple {5, "pair"}));
                    return;
                  }
                end
                """;

        var typed = TypeChecker.check(Parser.parse(program));
        OwnershipChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "structural-keyword-matrix.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals(
                "7\n8\nshape\nentry\npair",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }
}
