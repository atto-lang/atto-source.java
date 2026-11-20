package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.OresKeyedMap;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exact guest/compiler checks for the reconciled affine Map V1 contract. */
final class AttoAffineMapOwnershipV1Test {
    private static int execute(String text) throws Exception {
        OresCompiler.parseAndTypeCheck(text);
        Source source = Source.newBuilder(OresLanguage.ID, text, "affine-map-v1.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(source).asInt();
        }
    }

    private static void reject(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void indexedAssignmentAndPutShareRuntimeAndReportPreviousValue() throws Exception {
        assertEquals(42, execute("""
                pub fnc main(): int {
                  let Map<string,int> m = ~map<string,int>();
                  m["answer"] = 40;
                  val previous = m.put("answer", 42);
                  if previous.is_none() then return 1; fi
                  if previous.unwrap() != 40 then return 2; fi
                  return m["answer"];
                }
                """));
    }

    @Test
    void failedPutAndIndexedMutationLeaveExistingMapUnchanged() {
        OresKeyedMap<String, Long> map = OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.put("answer", 42L);
        assertThrows(IllegalArgumentException.class, () -> map.put("answer", (Long) null));
        assertThrows(IllegalArgumentException.class, () -> map.put("different", -1L == 0 ? 1L : null));
        assertEquals(42L, map.getRequired("answer"));
        assertEquals(1, map.size());
    }

    @Test
    void ownedParameterAndReturnTransferHaveOneOwner() throws Exception {
        assertEquals(42, execute("""
                fnc produce(): Map<string,int> {
                  let Map<string,int> local = ~map<string,int>();
                  local.put("answer", 42);
                  return rt take local;
                }
                fnc consume(Map<string,int> input): int {
                  return input["answer"];
                }
                pub fnc main(): int {
                  let Map<string,int> original = produce();
                  return consume(rt take original);
                }
                """));
    }

    @Test
    void sameWriteAuthorityRejectionForBothSpellings() {
        reject("pub fnc main(): void { const Map<string,int> m = ~map<string,int>(); m[\"x\"] = 1; return; }");
        reject("pub fnc main(): void { const Map<string,int> m = ~map<string,int>(); m.put(\"x\",1); return; }");
        reject("pub fnc main(): void { let Map<string,int> m = ~map<string,int>(); rt take m; m[\"x\"] = 1; return; }");
        reject("pub fnc main(): void { let Map<string,int> m = ~map<string,int>(); rt take m; m.put(\"x\",1); return; }");
    }

    @Test
    void disallowAliasCopyShareAndImplicitOwnedTransfer() {
        reject("pub fnc main(): void { let Map<string,int> m = ~map<string,int>(); val alias = m; return; }");
        reject("pub fnc main(): void { let Map<string,int> m = ~map<string,int>(); val alias = rt copy m; return; }");
        reject("pub fnc main(): void { let Map<string,int> m = ~map<string,int>(); val alias = rt share m; return; }");
        reject("""
                fnc consume(Map<string,int> m): void { return; }
                pub fnc main(): void {
                  let Map<string,int> m = ~map<string,int>();
                  consume(m);
                }
                """);
        reject("""
                fnc produce(): Map<string,int> {
                  let Map<string,int> m = ~map<string,int>();
                  return m;
                }
                """);
    }

    @Test
    void denyActorBoundaryAndBorrowAliasEscapes() {
        reject("actor fnc forbidden(Map<string,int> m): void { return; }");
        reject("""
                pub fnc main(): void {
                  let Map<string,int> m = ~map<string,int>();
                  val loan = rt borrow m;
                  rt take m;
                  return;
                }
                """);
    }
}
