package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralContainerSourceParityTest {

    @Test
    void structuralArrayAndListStayDistinctAndExecuteReadonlyReads() throws Exception {
        String sourceText = """
                define module app
                  fnc head_array(structural Array<int> xs): int { return xs[0]; }
                  fnc head_list(structural List<int> xs): int { return xs[0]; }

                  pub fnc main(): void {
                    val Array<int> a = [7, 8];
                    val List<int> l = [9, 10];
                    stdio.println(head_array(a));
                    stdio.println(head_array([11, 12]));
                    stdio.println(head_list(l));
                    return;
                  }
                end
                """;
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(sourceText)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));

        var out = new ByteArrayOutputStream();
        var source = org.graalvm.polyglot.Source.newBuilder(
                OresLanguage.ID, sourceText, "structural-container-v3.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (var context = org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        assertEquals("7\n11\n9\n",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void arrayListInterchangeAndReadonlyMutationFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc take(structural Array<int> xs): int { return xs[0]; }
                  fnc bad(List<int> xs): int { return take(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc take(structural List<float> xs): float { return xs[0]; }
                  fnc bad(List<int> xs): float { return take(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural List<int> xs): int {
                    xs[0] = 7;
                    return xs[0];
                  }
                end
                """)));
    }

    @Test
    void fixedArrayAndFixedListRequireExactKindAndExtent() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc head(structural FixedArray[2 of int] xs): int { return xs[0]; }
                  fnc ok(FixedArray[2 of int] xs): int { return head(xs); }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc empty(structural FixedArray[0 of int] xs): int { return xs.length; }
                  fnc ok(FixedArray[0 of int] xs): int { return empty(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc empty(structural FixedArray[0 of int] xs): int { return xs.length; }
                  fnc bad(FixedArray[0 of string] xs): int { return empty(xs); }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc empty(structural FixedList[0 of string] xs): int { return xs.length; }
                  fnc ok(FixedList[0 of string] xs): int { return empty(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc empty(structural FixedList[0 of string] xs): int { return xs.length; }
                  fnc bad(FixedList[0 of int] xs): int { return empty(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc head(structural FixedArray[2 of int] xs): int { return xs[0]; }
                  fnc bad(FixedArray[3 of int] xs): int { return head(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc head(structural FixedArray[2 of int] xs): int { return xs[0]; }
                  fnc bad(FixedList[2 of int] xs): int { return head(xs); }
                end
                """)));
    }

    @Test
    void structuralMapUsesInvariantStableKeysAndOptionalLookup() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc lookup(structural Map<string,int> values): Option<int> {
                    return values.get("missing");
                  }
                  fnc pass(Map<string,int> values): Option<int> {
                    return lookup(values);
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc read_list(structural List<string> values): string {
                    return values[0];
                  }
                  fnc list_literal_projection(List<"literal"> values): string {
                    return read_list(values);
                  }
                  fnc read_map(structural Map<string,string> values): Option<string> {
                    return values.get("key");
                  }
                  fnc map_literal_projection(Map<string,"literal"> values): Option<string> {
                    return read_map(values);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc lookup(structural Map<string,int> values): Option<int> {
                    return values.get("missing");
                  }
                  fnc bad(Map<"literal",int> values): Option<int> {
                    return lookup(values);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural Map<float,int> values): Option<int> {
                    return values.get(1.0);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural Map<string,int> values): void {
                    values.put("key", 1);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(Map<string,int> values): void {
                    values.unknown_map_operation();
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural List<int> values): void {
                    values.add(1);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural List<List<int>> values): int {
                    return 0;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural Map<string,List<int>> values): int {
                    return 0;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc sink(Channel<List<int>> ch, structural List<int> values): void {
                    writech(ch, values);
                    return;
                  }
                end
                """)));
    }

    @Test
    void ownedMapRuntimeSupportsOptionalLookupMutationAndEntryIteration() throws Exception {
        String sourceText = """
                define module app
                  fnc lookup(structural Map<string,int> values, string key): Option<int> {
                    return values.get(key);
                  }

                  fnc make(): Map<string,int> {
                    let mut Map<string,int> values = new Map<string,int>();
                    values.put("a", 7);
                    return values;
                  }

                  pub routine main(): void {
                    let mut Map<string,int> values = make();
                    values.put("b", 9);
                    stdio.stdout.write(values.size);
                    stdio.stdout.write(":");
                    stdio.stdout.write(lookup(values, "a").unwrap());
                    stdio.stdout.write(":");
                    stdio.stdout.write(lookup(values, "missing").is_none());
                    for const [key, value] of values do
                      stdio.stdout.write("|");
                      stdio.stdout.write(key);
                      stdio.stdout.write("=");
                      stdio.stdout.write(value);
                    done

                    let mut Map<int,int> ints = new Map<int,int>();
                    ints.put(-1, 11);
                    ints.put(9223372036854775807, 12);
                    stdio.stdout.write("|");
                    stdio.stdout.write(ints.get(-1).unwrap());
                    stdio.stdout.write(":");
                    stdio.stdout.write(ints.get(9223372036854775807).unwrap());
                    stdio.stdout.write(":");
                    stdio.stdout.write(values == values);
                    let mut Map<string,int> other = new Map<string,int>();
                    other.put("b", 9);
                    other.put("a", 7);
                    stdio.stdout.write(":");
                    stdio.stdout.write(values == other);
                    stdio.stdout.write(":");
                    stdio.stdout.write(values is other);
                    values.put("a", 8);
                    stdio.stdout.write(":");
                    stdio.stdout.write(values.size);
                    stdio.stdout.write(":");
                    stdio.stdout.write(values.get("a").unwrap());
                    return;
                  }
                end
                """;
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(sourceText)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));

        var out = new ByteArrayOutputStream();
        var source = org.graalvm.polyglot.Source.newBuilder(
                OresLanguage.ID, sourceText, "structural-map-v3.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (var context = org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        assertEquals("2:7:true|a=7|b=9|11:12:true:true:false:2:8",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));

        var mapAlias = assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse("""
                        define type Scores = Map<string,int>
                        fnc make(): Scores {
                          let mut Map<String,i64> values = new Map<String,i64>();
                          values.put("x", 1);
                          return values;
                        }
                        """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(mapAlias));
    }

    @Test
    void mapConstructionKeysAndTransportRemainFailClosed() {
        IllegalArgumentException unstableKey = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val Map<float,int> values = new Map<float,int>();
                          return;
                        }
                        """)));
        assertTrue(unstableKey.getMessage().contains("stable hash/equality"),
                unstableKey::getMessage);

        IllegalArgumentException constructorArgs = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val Map<string,int> values = new Map<string,int>(1);
                          return;
                        }
                        """)));
        assertTrue(constructorArgs.getMessage().contains("takes no positional arguments"),
                constructorArgs::getMessage);

        IllegalArgumentException channelMap = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Channel<Map<string,int>> values): void { return; }
                        """)));
        assertTrue(channelMap.getMessage().contains("cannot transport Map<K,V>"),
                channelMap::getMessage);

        IllegalArgumentException nestedChannelMap = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Channel<Option<Map<string,int>>> values): void { return; }
                        """)));
        assertTrue(nestedChannelMap.getMessage().contains("cannot transport Map<K,V>"),
                nestedChannelMap::getMessage);

        IllegalArgumentException actorMap = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(Map<string,int> values): void { return; }
                        """)));
        assertTrue(actorMap.getMessage().contains("actor-boundary"),
                actorMap::getMessage);

        IllegalArgumentException asyncMap = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc bad(Map<string,int> values): void { return; }
                        """)));
        assertTrue(asyncMap.getMessage().contains("task-safe"),
                asyncMap::getMessage);

        IllegalArgumentException sharedMap = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(SharedMutex<Map<string,int>> values): void { return; }
                        """)));
        assertTrue(sharedMap.getMessage().contains("shared-safe"),
                sharedMap::getMessage);

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(Option<Map<string,int>> values): void {
                          return;
                        }
                        """)));

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(SharedMutex<Option<Map<string,int>>> values): void {
                          return;
                        }
                        """)));

        var immutableBorrowProgram = assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(&Map<string,int> values): void {
                          values.put("x", 1);
                          return;
                        }
                        """)));
        IllegalArgumentException immutableBorrowMutation = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(immutableBorrowProgram));
        assertTrue(immutableBorrowMutation.getMessage().contains("cannot mutate"),
                immutableBorrowMutation::getMessage);

        var moveIntoMapProgram = assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          let mut Map<string,List<int>> values = new Map<string,List<int>>();
                          let mut List<int> payload = new List<int>();
                          values.put("x", payload);
                          return;
                        }
                        """)));
        IllegalArgumentException movedPayload = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(moveIntoMapProgram));
        assertTrue(movedPayload.getMessage().contains("requires copy-safe value type"),
                movedPayload::getMessage);

        var moveOnlyLookupProgram = assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Map<string,List<int>> values): void {
                          values.get("x");
                          return;
                        }
                        """)));
        IllegalArgumentException moveOnlyLookup = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(moveOnlyLookupProgram));
        assertTrue(moveOnlyLookup.getMessage().contains("Map.get cannot copy move-only"),
                moveOnlyLookup::getMessage);

        var borrowedPayloadProgram = assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(&int source): void {
                          let mut Map<string,&int> values = new Map<string,&int>();
                          values.put("x", source);
                          return;
                        }
                        """)));
        IllegalArgumentException borrowedPayload = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(borrowedPayloadProgram));
        assertTrue(borrowedPayload.getMessage().contains("cannot store a borrowed value"),
                borrowedPayload::getMessage);
    }

    @Test
    void collectionBoundMethodsCannotEscapeOwnershipChecks() {
        IllegalArgumentException mapPut = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          let mut Map<string,int> values = new Map<string,int>();
                          val putter = values.put;
                          putter("x", 1);
                          return;
                        }
                        """)));
        assertTrue(mapPut.getMessage().contains("direct-call-only"),
                mapPut::getMessage);

        IllegalArgumentException mapGet = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          let mut Map<string,List<int>> values = new Map<string,List<int>>();
                          val getter = values.get;
                          getter("x");
                          return;
                        }
                        """)));
        assertTrue(mapGet.getMessage().contains("direct-call-only"),
                mapGet::getMessage);

        IllegalArgumentException structuralGet = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(structural Map<string,int> values): void {
                          val getter = values.get;
                          getter("x");
                          return;
                        }
                        """)));
        assertTrue(structuralGet.getMessage().contains("direct-call-only"),
                structuralGet::getMessage);

        IllegalArgumentException listMutation = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          let mut List<int> values = new List<int>();
                          val adder = values.add;
                          adder(1);
                          return;
                        }
                        """)));
        assertTrue(listMutation.getMessage().contains("direct-call-only"),
                listMutation::getMessage);
    }

    @Test
    void contractRejectsShapeOnlyOverloadAndDynamicSpread() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc classify(structural Array<int> values): int { return 1; }
                  fnc classify(structural List<int> values): int { return 2; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc add(int left, int right): int { return left + right; }
                  fnc bad(List<int> values): int {
                    return add(...values);
                  }
                end
                """)));
    }

    @Test
    void zeroWidthFixedMetadataCannotHideUnsafePayloadTypes() {
        IllegalArgumentException channelBorrow = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Channel<FixedArray[0 of &int]> payload): void {
                          return;
                        }
                        """)));
        assertTrue(channelBorrow.getMessage().contains("cannot transport borrowed payloads"),
                channelBorrow::getMessage);

        IllegalArgumentException channelListBorrow = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Channel<FixedList[0 of &int]> payload): void {
                          return;
                        }
                        """)));
        assertTrue(channelListBorrow.getMessage().contains("cannot transport borrowed payloads"),
                channelListBorrow::getMessage);

        IllegalArgumentException actorFuture = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(FixedArray[0 of Future<int>] payload): void {
                          return;
                        }
                        """)));
        assertTrue(actorFuture.getMessage().contains("cannot use Future")
                        && actorFuture.getMessage().contains("actor boundary"),
                actorFuture::getMessage);

        IllegalArgumentException genericZeroWidth = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad<T>(structural FixedArray[0 of T] payload): int {
                          return payload.length;
                        }
                        """)));
        assertTrue(genericZeroWidth.getMessage().contains("closed readonly-safe"),
                genericZeroWidth::getMessage);

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc ok(FixedArray[0 of int] payload): void {
                  return;
                }
                """)));
    }

    @Test
    void structuralCollectionLoanCannotCrossSuspendBoundary() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  suspends fnc bad(structural List<int> values): int {
                    return values[0];
                  }
                end
                """)));
    }
}
