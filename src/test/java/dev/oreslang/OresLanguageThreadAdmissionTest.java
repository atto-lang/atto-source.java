package dev.oreslang;

import dev.oreslang.runtime.OresScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The checked thread is not guaranteed to be the calling thread.
 * Never project a scheduler carrier's ThreadLocal privilege to a foreign
 * requested thread, including when the language has no initialized context.
 */
final class OresLanguageThreadAdmissionTest {
    private static void setTrustedHost(OresLanguage language, Thread host)
            throws Exception {
        Field owner = OresLanguage.class.getDeclaredField("initialContextHostThread");
        owner.setAccessible(true);
        owner.set(language, host);
    }

    private static void markContextInitialized(OresLanguage language)
            throws Exception {
        Field initialized = OresLanguage.class.getDeclaredField("contextInitialized");
        initialized.setAccessible(true);
        initialized.setBoolean(language, true);
    }

    @Test
    void uninitializedLanguageRejectsConcurrentForeignThread() throws Exception {
        OresLanguage language = new OresLanguage();
        Thread external = Thread.ofPlatform().unstarted(() -> { });
        assertFalse(language.isThreadAccessAllowed(external, false),
                "pre-context language cannot grant a foreign thread entry");
        assertTrue(language.isThreadAccessAllowed(Thread.currentThread(), false),
                "only the precise language-instantiating thread may enter before context creation");
        assertFalse(language.isThreadAccessAllowed(null, false),
                "absent requested thread must fail closed");
        assertFalse(language.isThreadAccessAllowed(null, true),
                "single-threaded flag must not admit an absent thread");
        assertTrue(language.isThreadAccessAllowed(external, true),
                "Truffle retains responsibility for single-threaded admission");
    }

    @Test
    void trustedHostPermitIsForOneExplicitIdentityAndIsRevocable()
            throws Exception {
        OresLanguage language = new OresLanguage();
        Thread trusted = Thread.currentThread();
        Thread unrelated = Thread.ofPlatform().unstarted(() -> { });
        setTrustedHost(language, trusted);
        assertTrue(language.isThreadAccessAllowed(trusted, false));
        assertFalse(language.isThreadAccessAllowed(unrelated, false));
        setTrustedHost(language, null);
        markContextInitialized(language);
        assertFalse(language.isThreadAccessAllowed(trusted, false),
                "strict/adversarial context must not inherit host privilege");
    }

    @Test
    void schedulerCarrierNeverAuthorizesUnrelatedRequestedThread()
            throws Exception {
        OresLanguage language = new OresLanguage();
        markContextInitialized(language);
        Thread originalHost = Thread.currentThread();
        Thread unrelated = Thread.ofPlatform().unstarted(() -> { });
        try (OresScheduler scheduler = new OresScheduler(1)) {
            Integer result = scheduler.start(resume -> {
                assertTrue(OresScheduler.isSchedulerCarrierThread(),
                        "test must execute on real scheduler-owned carrier");
                assertTrue(language.isThreadAccessAllowed(Thread.currentThread(), false),
                        "the carrier itself remains admitted");
                assertFalse(language.isThreadAccessAllowed(originalHost, false),
                        "carrier ThreadLocal cannot authorize a different thread");
                assertFalse(language.isThreadAccessAllowed(unrelated, false),
                        "carrier ThreadLocal cannot authorize an unrelated host thread");
                assertFalse(language.isThreadAccessAllowed(null, false));
                return OresScheduler.done(42);
            }).get(5, TimeUnit.SECONDS);
            assertEquals(42, result);
        }
    }

    @Test
    void exhaustivePreContextAndThreadAdmissionCombinationsRespectIdentityFence() {
        Thread calling = Thread.currentThread();
        Thread other = Thread.ofPlatform().unstarted(() -> { });
        // Enumerate all requested/calling/host/construction identity and lifecycle
        // states with each carrier flag independently set.
        int transitionsChecked = 0;
        Thread[] requested = {calling, other, null};
        Thread[] hosts = {calling, other, null};
        Thread[] constructors = {calling, other, null};
        for (Thread request : requested) {
            for (Thread host : hosts) {
              for (Thread constructor : constructors) {
               for (boolean initialized : new boolean[]{false, true}) {
                for (int mask = 0; mask < 16; mask++) {
                    boolean single = (mask & 1) != 0;
                    boolean actor = (mask & 2) != 0;
                    boolean async = (mask & 4) != 0;
                    boolean scheduler = (mask & 8) != 0;
                    boolean actual = OresLanguage.threadAccessDecision(
                            request, calling, host, constructor, initialized,
                            single, actor, async, scheduler);
                    boolean authorizedOwner = request != null && request == host;
                    boolean authorizedCarrier = request != null
                            && request == calling && (actor || async || scheduler);
                    boolean provisionalOwner = !initialized && request != null
                            && request == constructor;
                    assertEquals(request != null
                                    && (single || authorizedOwner
                                        || provisionalOwner || authorizedCarrier),
                            actual, "bad thread admission state " + mask);
                    if (request != calling && request != host
                            && (initialized || request != constructor) && !single) {
                        assertFalse(actual,
                                "carrier privilege cannot transfer to a foreign thread");
                    }
                    transitionsChecked++;
                }
               }
              }
            }
        }
        assertEquals(864, transitionsChecked);
    }

    @Test
    void trustedHostAndCarrierKeepDistinctAuthorizedIdentities()
            throws Exception {
        OresLanguage language = new OresLanguage();
        Thread host = Thread.currentThread();
        setTrustedHost(language, host);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            assertEquals(7, scheduler.start(resume -> {
                assertTrue(language.isThreadAccessAllowed(host, false),
                        "authorized host permit must not disappear on another carrier");
                assertTrue(language.isThreadAccessAllowed(Thread.currentThread(), false));
                return OresScheduler.done(7);
            }).get(5, TimeUnit.SECONDS));
        }
        setTrustedHost(language, null);
        markContextInitialized(language);
        assertFalse(language.isThreadAccessAllowed(host, false));
    }
}
