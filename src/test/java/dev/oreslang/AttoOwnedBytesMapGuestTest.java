package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.OresBytes;
import dev.oreslang.runtime.OresKeyedMap;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Both the host contract and a real compiled Atto guest must pass. */
final class AttoOwnedBytesMapGuestTest {

    private static int runInt(String guest) throws Exception {
        OresCompiler.parseAndTypeCheck(guest);
        Source artifact = Source.newBuilder(OresLanguage.ID, guest,
                "atto-owned-bytes-map.ores").mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(artifact).asInt();
        }
    }

    @Test
    void hostSnapshotsContentIdentityAndNoMutableEscape() {
        byte[] original = {0, (byte) 255, 13, 10};
        OresBytes bytes = OresBytes.fromArray(original);
        original[0] = 42;
        assertEquals(0L, bytes.get(0));
        assertEquals(255L, bytes.get(1));
        assertEquals(OresBytes.fromList(List.of(0L, 255L, 13L, 10L)), bytes);
        assertEquals(bytes.hashCode(),
                OresBytes.fromList(List.of(0, 255, 13, 10)).hashCode());
        assertNotEquals(bytes, OresBytes.fromList(List.of(0, 255, 13, 11)));
        List<Object> detached = bytes.toList();
        detached.set(0, 88L);
        assertEquals(0L, bytes.get(0));
        byte[] arrayCopy = bytes.toArrayCopy();
        arrayCopy[1] = 3;
        assertEquals(255L, bytes.get(1));
        assertNotSame(detached, bytes.toList());
    }

    @Test
    void hostRejectsOutOfDomainAndOversizedBytes() {
        for (Object bad : List.of(-1, 256, "1", 1.5d, true)) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresBytes.fromList(List.of(bad)));
        }
        List<Integer> tooLong = new ArrayList<>(
                java.util.Collections.nCopies(OresBytes.MAX_LENGTH + 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> OresBytes.fromList(tooLong));
        assertThrows(IndexOutOfBoundsException.class,
                () -> OresBytes.empty().get(0));
    }

    @Test
    void byteMapKeysAreHashedByImmutableContentNotIdentity() {
        OresKeyedMap<OresBytes, OresBytes> map =
                OresKeyedMap.byteKeys(OresKeyedMap.ValueDomain.BYTES);
        OresBytes key = OresBytes.fromList(List.of(0, 255, 13, 10));
        OresBytes equivalent = OresBytes.fromList(List.of(0L, 255L, 13L, 10L));
        OresBytes value = OresBytes.fromList(List.of(254, 0, 13, 10));
        map.put(key, value);
        assertTrue(map.lookup(equivalent).present());
        assertEquals(value, map.lookup(equivalent).value());
        assertEquals(1, map.size());
        map.put(equivalent, OresBytes.empty());
        assertEquals(1, map.size());
        assertTrue(map.lookup(key).present());
        assertEquals(0, map.lookup(key).value().size());
        assertTrue(map.take(key).present());
        assertFalse(map.lookup(equivalent).present());
        assertThrows(IllegalArgumentException.class,
                () -> ((OresKeyedMap) map).put("not bytes", value));
        assertThrows(IllegalArgumentException.class,
                () -> ((OresKeyedMap) map).put(key, "not bytes"));
    }

    @Test
    void tenThousandDeterministicBinaryMapOperationsMatchIndependentOracle() {
        OresKeyedMap<OresBytes, OresBytes> subject =
                OresKeyedMap.byteKeys(OresKeyedMap.ValueDomain.BYTES);
        java.util.HashMap<OresBytes, OresBytes> oracle = new java.util.HashMap<>();
        java.util.Random rng = new java.util.Random(0xA770B17EL);
        for (int operation = 0; operation < 10_000; operation++) {
            int token = rng.nextInt(96);
            OresBytes key = OresBytes.fromList(
                    List.of(token, 255 - token, 0, 255, 13, 10));
            int choice = rng.nextInt(3);
            if (choice == 0) {
                OresBytes value = OresBytes.fromList(List.of(
                        operation & 255, (operation >> 8) & 255, 0, 255));
                subject.put(key, value);
                oracle.put(key, value);
            } else if (choice == 1) {
                OresKeyedMap.Lookup<OresBytes> actual = subject.lookup(key);
                assertEquals(oracle.containsKey(key), actual.present(),
                        "presence at op " + operation);
                assertEquals(oracle.get(key), actual.value(),
                        "value at op " + operation);
            } else {
                OresKeyedMap.Lookup<OresBytes> removed = subject.take(key);
                OresBytes expected = oracle.remove(key);
                assertEquals(expected != null, removed.present(),
                        "delete presence at op " + operation);
                assertEquals(expected, removed.value(),
                        "delete value at op " + operation);
            }
            assertEquals(oracle.size(), subject.size(), "size after op " + operation);
        }
        // Distinct arrays can collide under the same array-content hash.
        OresBytes collisionA = OresBytes.fromList(List.of(0, 31));
        OresBytes collisionB = OresBytes.fromList(List.of(1, 0));
        assertEquals(collisionA.hashCode(), collisionB.hashCode());
        assertNotEquals(collisionA, collisionB);
        subject.put(collisionA, OresBytes.fromList(List.of(1)));
        subject.put(collisionB, OresBytes.fromList(List.of(2)));
        assertEquals(1L, subject.lookup(collisionA).value().get(0));
        assertEquals(2L, subject.lookup(collisionB).value().get(0));
    }

    @Test
    void compiledGuestBytesSnapshotsAndBinaryMapCRUD() throws Exception {
        String guest = """
                pub fnc main(): int {
                  let mut List<int> input = [0, 255, 13, 10];
                  const Bytes original = Bytes.from_list(input);
                  input.set(0, 42);
                  if original.get(0) != 0 then return 1; fi
                  if original.get(1) != 255 then return 2; fi
                  let Map<Bytes,Bytes> db = ~map<Bytes,Bytes>();
                  const Bytes equivalent = Bytes.from_list([0, 255, 13, 10]);
                  const Bytes binary = Bytes.from_list([254, 0, 13, 10]);
                  db.set(original, binary);
                  if db.size != 1 then return 3; fi
                  val found = db.get_optional(equivalent);
                  if found.is_none() then return 4; fi
                  const Bytes got = found.unwrap();
                  if got.get(0) != 254 then return 5; fi
                  if got.size != 4 then return 6; fi
                  let mut List<int> escaped = got.to_list();
                  escaped.set(0, 42);
                  if got.get(0) != 254 then return 7; fi
                  db.set(equivalent, Bytes.empty());
                  if db.size != 1 then return 8; fi
                  val after = db.get_optional(original);
                  if after.is_none() then return 9; fi
                  if after.unwrap().size != 0 then return 10; fi
                  if !db.delete(equivalent) then return 11; fi
                  if db.delete(original) then return 12; fi
                  if db.get_optional(original).is_some() then return 13; fi
                  return 42;
                }
                """;
        assertEquals(42, runInt(guest));
    }

    @Test
    void staticTypeGateRejectsWrongDomainsAndImmutableMutation() {
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                    const Bytes illegal = Bytes.from_list(["not an int"]);
                    return;
                }
                """));
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                    let Map<int,Bytes> bad = ~map<int,Bytes>();
                    return;
                }
                """));
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                    const Map<Bytes,Bytes> immutable = ~map<Bytes,Bytes>();
                    immutable.set(Bytes.empty(), Bytes.empty());
                    return;
                }
                """));
    }
}
