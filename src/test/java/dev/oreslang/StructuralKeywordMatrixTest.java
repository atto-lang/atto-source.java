package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
    void readonlyStructuralViewsDoNotCoerceFieldRepresentations() {
        Types.Record intShape = new Types.Record(
                Map.of("value", Types.Primitive.INT), false, Types.AggregateKind.RECORD);
        Types.Record floatView = new Types.Record(
                Map.of("value", Types.Primitive.FLOAT), true, Types.AggregateKind.CONTRACT);
        assertFalse(Types.isAssignable(intShape, floatView),
                "readonly shape projections cannot reinterpret an int field as float");
        assertFalse(Types.isAssignable(floatView,
                new Types.Record(Map.of("value", Types.Primitive.FLOAT),
                        false, Types.AggregateKind.RECORD)),
                "readonly projections must not recover write access");

        Types.Record nestedInt = new Types.Record(Map.of("inner", intShape),
                false, Types.AggregateKind.RECORD);
        Types.Record nestedFloat = new Types.Record(Map.of("inner", floatView),
                true, Types.AggregateKind.CONTRACT);
        assertFalse(Types.isAssignable(nestedInt, nestedFloat),
                "nested structural fields must not regain numeric coercion");
        assertTrue(Types.isAssignable(intShape, new Types.Record(
                Map.of("value", Types.Primitive.INT), true, Types.AggregateKind.CONTRACT)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wants(structural { value: float } row): float {
                  return row.value;
                }
                fnc invalid(): float {
                  return wants(record{value: 1});
                }
                """)));
    }

    @Test
    void explicitReadonlyStructViewsAllowWidthButNeverRecoverStructOrWriteAuthority() {
        Types.Record concreteStruct = Types.Record.struct(
                Map.of("id", Types.Primitive.INT, "extra", Types.Primitive.STRING));
        Types.Record exactStruct = Types.Record.struct(Map.of("id", Types.Primitive.INT));
        Types.Record readonlyContract = new Types.Record(
                Map.of("id", Types.Primitive.INT), true, Types.AggregateKind.CONTRACT);
        Types.Record writableContract = new Types.Record(
                Map.of("id", Types.Primitive.INT), false, Types.AggregateKind.CONTRACT);
        Types.Record ordinaryRecord = new Types.Record(Map.of("id", Types.Primitive.INT));

        assertTrue(Types.isAssignable(concreteStruct, readonlyContract),
                "the structural keyword permits readonly width projection of structs");
        assertFalse(Types.isAssignable(concreteStruct, writableContract),
                "structural projections must not manufacture writable aliases");
        assertFalse(Types.isAssignable(concreteStruct, ordinaryRecord),
                "ordinary records cannot erase concrete struct identity");
        assertFalse(Types.isAssignable(concreteStruct, exactStruct),
                "ordinary struct assignment remains exact, not width-subtyped");
        assertFalse(Types.isAssignable(readonlyContract, concreteStruct),
                "readonly structural views cannot recover nominal struct identity");
        assertFalse(Types.isAssignable(concreteStruct, new Types.Record(
                Map.of("id", Types.Primitive.FLOAT), true, Types.AggregateKind.CONTRACT)),
                "structural readonly projection cannot reinterpret numeric storage");
    }

    @Test
    void structuralPairsDoNotReinterpretNumericOrNestedTupleSlots() {
        Types.Tuple actual = new Types.Tuple(java.util.List.of(
                Types.Primitive.INT, Types.Primitive.STRING));
        Types.Tuple unsafeFloatView = new Types.Tuple(java.util.List.of(
                Types.Primitive.FLOAT, Types.Primitive.STRING));
        assertFalse(Types.isAssignable(actual, unsafeFloatView),
                "readonly structural pairs must not reinterpret stored int slots as float");
        assertTrue(Types.isAssignable(actual, new Types.Tuple(java.util.List.of(
                Types.Primitive.INT, Types.Primitive.STRING))));

        Types.Tuple nestedActual = new Types.Tuple(java.util.List.of(actual));
        Types.Tuple nestedUnsafe = new Types.Tuple(java.util.List.of(unsafeFloatView));
        assertFalse(Types.isAssignable(nestedActual, nestedUnsafe),
                "nested positional aliases must preserve actual slot representations");

        Types.Tuple fixedArray = new Types.Tuple(
                java.util.List.of(Types.Primitive.INT), Types.TupleKind.FIXED_ARRAY);
        Types.Tuple fixedArrayFloat = new Types.Tuple(
                java.util.List.of(Types.Primitive.FLOAT), Types.TupleKind.FIXED_ARRAY);
        assertFalse(Types.isAssignable(fixedArray, fixedArrayFloat),
                "mutable fixed array slots cannot be covariantly widened");
        assertFalse(Types.isAssignable(actual, new Types.Tuple(
                actual.elements(), Types.TupleKind.FIXED_ARRAY)),
                "Tuple and fixed Array remain distinct physical kinds");

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wants(structural Tuple<float, string> pair): float {
                  return pair[0];
                }
                fnc invalid(): float {
                  return wants(tuple {1, "untouched"});
                }
                """)), "structural tuple float parameter must reject an int slot");
    }

    @Test
    void nestedStructuralLoansNeverExposeWritableRecords() {
        var parameterProgram = Parser.parse("""
                fnc forbidden(structural { inner: { value: int } } loan): void {
                  loan.inner.value = 7;
                  return;
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parameterProgram),
                "a nested record must stay readonly through a structural argument");

        var patternProgram = Parser.parse("""
                fnc forbidden({ inner: { value: int } } row): void {
                  match row over
                    on structural { inner: { value: int } } loan -> {
                      loan.inner.value = 8;
                      return;
                    }
                    on _ -> { return; }
                  end
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(patternProgram),
                "structural match refinement must not restore nested write authority");

        var readOnlyProgram = Parser.parse("""
                fnc read(structural { inner: { value: int } } loan): int {
                  return loan.inner.value;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(readOnlyProgram),
                "readonly nested projections remain readable");
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

                  define interface Empty
                  end

                  define class FieldOnly as
                    pub val int id = 0;
                  end

                  define interface HasPublicId
                    id: int;
                  end

                  fnc interface_arg(structural HasPublicId value): int {
                    return value.id;
                  }

                  fnc record_arg(structural {id: int} value): int {
                    return value.id;
                  }

                  fnc struct_arg(structural struct{id: int} value): int {
                    return value.id;
                  }

                  fnc class_arg(structural FieldOnly value): int {
                    return value.id;
                  }

                  fnc trait_arg(structural KeyValue value): string {
                    return value.value;
                  }

                  fnc entry_arg(structural Entry<int, string> value): string {
                    return value.value;
                  }

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

                  fnc field_only_class_case(): int {
                    const row = record{id: 13, extra: true};
                    match row over
                      on structural FieldOnly shaped -> { return shaped.id; }
                      on _ -> { return 0; }
                    end
                  }

                  fnc empty_shape_case<T>(T value): bool {
                    match value over
                      on structural {} shaped -> { return true; }
                      on _ -> { return false; }
                    end
                  }

                  fnc empty_interface_case<T>(T value): bool {
                    match value over
                      on structural Empty shaped -> { return true; }
                      on _ -> { return false; }
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
                    stdio.println(field_only_class_case());
                    stdio.println(empty_shape_case(99));
                    stdio.println(empty_shape_case(record{id: 1}));
                    stdio.println(empty_interface_case(99));
                    stdio.println(empty_interface_case(record{id: 1}));
                    stdio.println(interface_arg(new Candidate()));
                    stdio.println(record_arg(record{id: 21, extra: true}));
                    stdio.println(struct_arg(record{id: 22, extra: true}));
                    stdio.println(class_arg(record{id: 23, extra: true}));
                    stdio.println(trait_arg(Entry.of(2, "trait")));
                    stdio.println(entry_arg(record{key: 3, value: "entry-arg", extra: true}));
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
                "7\n8\n13\nfalse\ntrue\nfalse\ntrue\n7\n21\n22\n23\ntrait\nentry-arg\nshape\nentry\npair",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }
}
