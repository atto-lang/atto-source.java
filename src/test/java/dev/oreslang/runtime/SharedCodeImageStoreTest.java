package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SharedCodeImageStoreTest {

    @Test
    void sharedPrivateAndUntrustedReferencesObserveOneCodeImage() {
        Ast.Program parsedOnce = Parser.parse("""
                pub routine main(): void { return; }
                """);

        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            SharedCodeImageStore.CodeImage initial =
                    store.publish("app/main.ores", parsedOnce);
            assertSame(parsedOnce, initial.program());

            // These are logical actor-kind observers, not independent code
            // copies or claims about compiled native instructions/processes.
            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                SharedCodeImageStore.CodeImage observer = store.get("app/main.ores");
                assertSame(initial, observer, "actor kind " + kind);
                assertSame(parsedOnce, observer.program(), "actor kind " + kind);
            }
            assertSame(initial, store.publish("app/main.ores", parsedOnce));
            assertEquals(1, store.imageCount());
        }
    }

    @Test
    void checkedSymbolLiteralsReuseTheSameImmutableImageAcrossCodeObservers() {
        String source = """
                pub routine main(): void {
                  const Symbol a = #ready;
                  const Symbol b = #"ready foo";
                  return;
                }
                """;
        Ast.Program program = Parser.parse(source);
        List<OresSymbol> literals = OresSymbolLiteralSource.collect(source);
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            var image = store.publish("app/tag.atto", program, literals);
            OresSymbol literalA = image.staticLiteralValue(OresSymbol.process("ready"));
            OresSymbol literalB = image.staticLiteralValue(OresSymbol.process("ready"));
            assertSame(literalA, literalB);
            assertSame(literalA, store.get("app/tag.atto")
                    .staticLiteralValue(OresSymbol.process("ready")));
            assertSame(literalA, image.literalForActor(
                    OresSymbol.process("ready"), ActorRuntime.ActorKind.SHARED));
            OresSymbol privateValue = image.literalForActor(
                    OresSymbol.process("ready"), ActorRuntime.ActorKind.PRIVATE);
            assertEquals(literalA, privateValue);
            assertNotSame(literalA, privateValue,
                    "PRIVATE actors must not retain shared-region scalar addresses");
            OresSymbol untrustedValue = image.literalForActor(
                    OresSymbol.process("ready"), ActorRuntime.ActorKind.UNTRUSTED);
            assertEquals(literalA, untrustedValue);
            assertNotSame(literalA, untrustedValue,
                    "UNTRUSTED actors must not retain shared-region scalar addresses");
            assertThrows(SecurityException.class, () -> image.literalForActor(
                    OresSymbol.process("ready foo"), ActorRuntime.ActorKind.UNTRUSTED));
            assertThrows(SecurityException.class, () -> image.literalForActor(
                    OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000"),
                    ActorRuntime.ActorKind.UNTRUSTED));
            assertEquals(OresSymbol.process("ready foo"),
                    image.staticLiteralValue(OresSymbol.process("ready foo")));
            OresSymbol dynamic = OresSymbol.process("not-in-source");
            assertSame(dynamic, image.staticLiteralValue(dynamic),
                    "dynamic symbols must never be added to a permanent pool");
            assertSame(image, store.publish("app/tag.atto", program, literals));
            assertThrows(IllegalStateException.class,
                    () -> store.publish("app/tag.atto", program, List.of()));
        }
    }

    @Test
    void codeUnitsDoNotEvictEachOthersSymbolsAtEightGenerationLimit() {
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            for (int i = 0; i < 12; i++) {
                Ast.Program program = Parser.parse(
                        "pub routine main(): void { const Symbol a = #ready; return; }");
                var image = store.publish("unit" + i + ".atto", program,
                        List.of(OresSymbol.process("ready")));
                assertEquals(OresSymbol.process("ready"),
                        image.staticLiteralValue(OresSymbol.process("ready")));
            }
            assertEquals(12, store.imageCount());
        }
    }

    @Test
    void hostileCatalogCannotRecursivelyPublishAnUnreviewedCodeUnit() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            var baseline = store.publish("trusted.atto", program,
                    List.of(OresSymbol.process("ready")));
            List<OresSymbol> malicious = new java.util.AbstractList<>() {
                @Override public int size() { return 1; }
                @Override public OresSymbol get(int i) {
                    throw new AssertionError("iterator, not indexed access, is required");
                }
                @Override public java.util.Iterator<OresSymbol> iterator() {
                    return new java.util.Iterator<>() {
                        boolean next = true;
                        @Override public boolean hasNext() { return next; }
                        @Override public OresSymbol next() {
                            next = false;
                            store.publish("injected.atto", program,
                                    List.of(OresSymbol.process("injected")));
                            return OresSymbol.process("attacker");
                        }
                    };
                }
            };
            var failure = assertThrows(IllegalStateException.class,
                    () -> store.publish("outer.atto", program, malicious));
            assertTrue(failure.getMessage().contains("recursive"));
            assertEquals(1, store.imageCount(), "nested publication must not commit");
            assertNull(store.get("injected.atto"));
            assertNull(store.get("outer.atto"));
            assertSame(baseline, store.get("trusted.atto"));
            // Exceptions from user collection callbacks cannot poison a VM
            // carrier's ThreadLocal publishing guard on its next operation.
            var recovered = store.publish("recovered.atto", program,
                    List.of(OresSymbol.process("recovered")));
            assertEquals(OresSymbol.process("recovered"),
                    recovered.requireGuestStaticLiteral(
                            OresSymbol.process("recovered"), ActorRuntime.ActorKind.SHARED));
            assertEquals(2, store.imageCount());
        }
    }

    @Test
    void swallowedReentrantCallbackStillCannotInjectAnotherCodeUnit() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            List<OresSymbol> attempted = new java.util.AbstractList<>() {
                @Override public int size() { return 1; }
                @Override public OresSymbol get(int i) { return OresSymbol.process("ready"); }
                @Override public java.util.Iterator<OresSymbol> iterator() {
                    return new java.util.Iterator<>() {
                        boolean next = true;
                        @Override public boolean hasNext() { return next; }
                        @Override public OresSymbol next() {
                            next = false;
                            assertThrows(IllegalStateException.class,
                                    () -> store.publish("injected.atto", program,
                                            List.of(OresSymbol.process("forbidden"))));
                            return OresSymbol.process("ready");
                        }
                    };
                }
            };
            var image = store.publish("checked.atto", program, attempted);
            assertEquals(OresSymbol.process("ready"),
                    image.requireGuestStaticLiteral(
                            OresSymbol.process("ready"), ActorRuntime.ActorKind.SHARED));
            assertNull(store.get("injected.atto"));
            assertEquals(1, store.imageCount());
        }
    }

    @Test
    void hostileCatalogCannotHoldCodeRegistryLockOrResurrectClosedStore() throws Exception {
        Ast.Program slowProgram = Parser.parse("pub routine main(): void { return; }");
        Ast.Program fastProgram = Parser.parse("pub routine other(): void { return; }");
        var scanning = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        List<OresSymbol> slowCatalog = new java.util.AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public OresSymbol get(int i) { return OresSymbol.process("slow"); }
            @Override public java.util.Iterator<OresSymbol> iterator() {
                return new java.util.Iterator<>() {
                    boolean consumed;
                    @Override public boolean hasNext() { return !consumed; }
                    @Override public OresSymbol next() {
                        consumed = true;
                        scanning.countDown();
                        try {
                            if (!finish.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test catalog iterator timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return OresSymbol.process("slow");
                    }
                };
            }
        };
        SharedCodeImageStore store = new SharedCodeImageStore();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var slow = workers.submit(() -> store.publish("slow.atto", slowProgram, slowCatalog));
            try {
                assertTrue(scanning.await(3, TimeUnit.SECONDS));
                var fast = workers.submit(() ->
                        store.publish("fast.atto", fastProgram,
                                List.of(OresSymbol.process("fast"))));
                var fastImage = fast.get(2, TimeUnit.SECONDS);
                assertEquals(OresSymbol.process("fast"),
                        fastImage.staticLiteralValue(OresSymbol.process("fast")));
                store.close();
            } finally {
                finish.countDown();
            }
            var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> slow.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, rejected.getCause());
            assertEquals(0, store.imageCount());
        } finally {
            store.close();
        }
    }

    @Test
    void lyingSourceCatalogSizeStillMeetsRealEntryQuota() {
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            Ast.Program program = Parser.parse("pub routine main(): void { return; }");
            List<OresSymbol> overflowing = new java.util.AbstractList<>() {
                @Override public int size() { return 0; }
                @Override public OresSymbol get(int i) {
                    return OresSymbol.process("name_" + i);
                }
                @Override public java.util.Iterator<OresSymbol> iterator() {
                    return new java.util.Iterator<>() {
                        int at;
                        @Override public boolean hasNext() {
                            return at <= OresSymbolLiteralImageStore.MAX_LITERALS;
                        }
                        @Override public OresSymbol next() {
                            return OresSymbol.process("name_" + at++);
                        }
                    };
                }
            };
            assertThrows(IllegalArgumentException.class, () ->
                    store.publish("too-many.atto", program, overflowing));
            assertEquals(0, store.imageCount());
        }
    }

    @Test
    void compiledLiteralAbsentFromCheckedImageFailsClosedOnEveryActorKind() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            var image = store.publish("strict.atto", program,
                    List.of(OresSymbol.process("ready")));
            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                assertThrows(IllegalStateException.class,
                        () -> image.requireGuestStaticLiteral(
                                OresSymbol.process("hidden"), kind));
            }
            assertEquals(OresSymbol.process("ready"),
                    image.requireGuestStaticLiteral(
                            OresSymbol.process("ready"), ActorRuntime.ActorKind.SHARED));
        }
    }

    @Test
    void canonicalEquivalentSourceLiteralIsResolvedWithoutSlotBypass() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            var image = store.publish("nfc.atto", program,
                    List.of(OresSymbol.process("caf\u00e9")));
            OresSymbol symbol = image.requireGuestStaticLiteral(
                    OresSymbol.process("cafe\u0301"), ActorRuntime.ActorKind.SHARED);
            assertSame(image.staticLiteralValue(OresSymbol.process("caf\u00e9")), symbol);
        }
    }

    @Test
    void untrustedStaticImageAllowsOnlyDeclaredCoreSymbols() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        var all = List.of(OresSymbol.process("ready"), OresSymbol.process("error"),
                OresSymbol.process("Symbol.iterator"), OresSymbol.process("internal"),
                OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000"));
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            var image = store.publish("policy.atto", program, all);
            for (String name : List.of("ready", "error", "Symbol.iterator")) {
                var pooled = image.requireGuestStaticLiteral(
                        OresSymbol.process(name), ActorRuntime.ActorKind.SHARED);
                var detached = image.requireGuestStaticLiteral(
                        OresSymbol.process(name), ActorRuntime.ActorKind.UNTRUSTED);
                assertEquals(pooled, detached);
                assertNotSame(pooled, detached);
            }
            assertThrows(SecurityException.class, () -> image.requireGuestStaticLiteral(
                    OresSymbol.process("internal"), ActorRuntime.ActorKind.UNTRUSTED));
            assertThrows(SecurityException.class, () -> image.requireGuestStaticLiteral(
                    all.get(4), ActorRuntime.ActorKind.UNTRUSTED));
        }
    }

    @Test
    void duplicateCanonicalInputIsRejectedBeforePublicationWithoutLeakingImage() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            assertThrows(IllegalArgumentException.class, () -> store.publish(
                    "duplicate.atto", program, List.of(
                            OresSymbol.process("cafe\u0301"),
                            OresSymbol.process("caf\u00e9"))));
            assertEquals(0, store.imageCount());
            assertNull(store.get("duplicate.atto"));
        }
    }

    @Test
    void failedReplacementCannotReplaceOrRevokeAnExistingGuestImage() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            var original = store.publish("stable.atto", program,
                    List.of(OresSymbol.process("ready")));
            assertThrows(IllegalStateException.class, () -> store.publish(
                    "stable.atto", program, List.of(OresSymbol.process("different"))));
            assertSame(original, store.get("stable.atto"));
            assertEquals(OresSymbol.process("ready"), original.requireGuestStaticLiteral(
                    OresSymbol.process("ready"), ActorRuntime.ActorKind.SHARED));
            assertEquals(1, store.imageCount());
        }
    }

    @Test
    void releasedCodeImagesCannotBeUsedAsActiveGuestImageLeases() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        SharedCodeImageStore store = new SharedCodeImageStore();
        var image = store.publish("retired.atto", program,
                List.of(OresSymbol.process("ready")));
        store.close();
        assertThrows(IllegalStateException.class, () -> image.requireGuestStaticLiteral(
                OresSymbol.process("ready"), ActorRuntime.ActorKind.SHARED));
        store.close();
        assertEquals(0, store.imageCount());
    }

    @Test
    void concurrentReadersNeverConstructANewCodeImage() throws Exception {
        Ast.Program parsedOnce = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            SharedCodeImageStore.CodeImage expected =
                    store.publish("shared/main.ores", parsedOnce);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(4)) {
                List<java.util.concurrent.Future<SharedCodeImageStore.CodeImage>> tasks =
                        new ArrayList<>();
                for (int i = 0; i < 48; i++) {
                    tasks.add(pool.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return store.get("shared/main.ores");
                    }));
                }
                start.countDown();
                for (var task : tasks) {
                    assertSame(expected, task.get(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void vmCodeImageCountQuotaRejectsNewUnitsButAllowsExactExistingReuse() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore(2, 100)) {
            var first = store.publish("first.atto", program, List.of(OresSymbol.process("ready")));
            store.publish("second.atto", program, List.of(OresSymbol.process("error")));
            assertEquals(2, store.imageCount());
            assertEquals(10L, store.activeLiteralPayloadBytes());
            assertSame(first, store.publish("first.atto", program,
                    List.of(OresSymbol.process("ready"))));
            assertThrows(IllegalStateException.class, () ->
                    store.publish("third.atto", program, List.of()));
            assertNull(store.get("third.atto"));
            assertEquals(2, store.imageCount());
            assertEquals(10L, store.activeLiteralPayloadBytes());
        }
    }

    @Test
    void vmAggregateStaticSymbolPayloadQuotaIsTransactionalAndReclaimsOnClose() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        SharedCodeImageStore store = new SharedCodeImageStore(5, 8);
        var first = store.publish("one.atto", program, List.of(OresSymbol.process("ready")));
        assertEquals(5L, store.activeLiteralPayloadBytes());
        assertThrows(IllegalStateException.class, () ->
                store.publish("two.atto", program, List.of(OresSymbol.process("error"))));
        assertEquals(1, store.imageCount());
        assertNull(store.get("two.atto"));
        assertEquals(5L, store.activeLiteralPayloadBytes());
        assertSame(first, store.publish("one.atto", program,
                List.of(OresSymbol.process("ready"))));
        var second = store.publish("three.atto", program, List.of(OresSymbol.process("abc")));
        assertEquals(8L, store.activeLiteralPayloadBytes());
        assertEquals(OresSymbol.process("abc"), second.requireGuestStaticLiteral(
                OresSymbol.process("abc"), ActorRuntime.ActorKind.SHARED));
        store.close();
        assertEquals(0L, store.activeLiteralPayloadBytes());
        assertEquals(0, store.imageCount());
        assertThrows(IllegalStateException.class,
                () -> first.requireGuestStaticLiteral(
                        OresSymbol.process("ready"), ActorRuntime.ActorKind.SHARED));
    }

    @Test
    void zeroBudgetAllowsCodeOnlyImageButRejectsAnySymbolPayload() {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore(2, 0)) {
            var codeOnly = store.publish("empty.atto", program, List.of());
            assertEquals(0, store.activeLiteralPayloadBytes());
            assertSame(codeOnly, store.publish("empty.atto", program, List.of()));
            assertThrows(IllegalStateException.class, () ->
                    store.publish("symbol.atto", program,
                            List.of(OresSymbol.process("ready"))));
            assertEquals(1, store.imageCount());
            assertEquals(0L, store.activeLiteralPayloadBytes());
        }
    }

    @Test
    void invalidVmSymbolQuotasFailBeforeAnyImagePublication() {
        assertThrows(IllegalArgumentException.class,
                () -> new SharedCodeImageStore(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SharedCodeImageStore(SharedCodeImageStore.MAX_CODE_IMAGES + 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SharedCodeImageStore(1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new SharedCodeImageStore(1, SharedCodeImageStore.MAX_TOTAL_LITERAL_BYTES + 1));
    }

    @Test
    void competingPublishersCannotExceedAggregateVmBudgets() throws Exception {
        Ast.Program program = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore(3, 6);
             var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                final int ordinal = i;
                attempts.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        store.publish("unit" + ordinal + ".atto", program,
                                List.of(OresSymbol.process("s" + ordinal)));
                        return true;
                    } catch (IllegalStateException quota) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successful = 0;
            for (var attempt : attempts) if (attempt.get(8, TimeUnit.SECONDS)) successful++;
            assertEquals(3, successful);
            assertEquals(3, store.imageCount());
            assertEquals(6L, store.activeLiteralPayloadBytes());
        }
    }

    @Test
    void cannotOverwriteActiveCodeUnitOrUseStoreAfterClose() {
        SharedCodeImageStore store = new SharedCodeImageStore();
        Ast.Program one = Parser.parse("pub routine main(): void { return; }");
        Ast.Program independentCopy = Parser.parse("pub routine main(): void { return; }");
        store.publish("same/main.ores", one);

        assertThrows(IllegalStateException.class,
                () -> store.publish("same/main.ores", independentCopy),
                "new generations require a new OresVM/code store");
        assertSame(one, store.get("same/main.ores").program());

        store.close();
        assertEquals(0, store.imageCount());
        assertThrows(IllegalStateException.class, () -> store.get("same/main.ores"));
        assertThrows(IllegalStateException.class,
                () -> store.publish("same/main.ores", one));
    }

    @Test
    void codeImagesCannotBeMintedOutsideTheStore() {
        assertEquals(
                0,
                SharedCodeImageStore.CodeImage.class.getConstructors().length,
                "CodeImage must remain an opaque store-minted capability");
    }


    @Test
    void parsedCodeImageAstContainersAreTransitivelyReadOnly() {
        Ast.Program program = Parser.parse("""
                define actor Worker as
                  let int count = 0;

                  receive(ActorMail<String> mail): void {
                    self.count = self.count + 1;
                    self.end();
                    return;
                  }
                end
                """);

        Ast.ModuleDecl root = program.modules().getFirst();
        Ast.ClassDecl worker = root.declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.MethodDecl receive = worker.methods().stream()
                .filter(method -> method.name().equals("receive"))
                .findFirst()
                .orElseThrow();

        assertThrows(UnsupportedOperationException.class,
                () -> program.modules().add(root));
        assertThrows(UnsupportedOperationException.class,
                () -> root.declarations().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> worker.fields().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> worker.methods().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> receive.body().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> receive.parameters().clear());
    }

}
