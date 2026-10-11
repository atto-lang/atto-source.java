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

    @Test
    void uninitializedLanguageRejectsConcurrentForeignThread() throws Exception {
        OresLanguage language = new OresLanguage();
        Thread external = Thread.ofPlatform().unstarted(() -> { });
        assertFalse(language.isThreadAccessAllowed(external, false),
                "uninitialized language must not invent trusted host authority");
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
        assertFalse(language.isThreadAccessAllowed(trusted, false),
                "strict/adversarial context must not inherit host privilege");
    }

    @Test
    void schedulerCarrierNeverAuthorizesUnrelatedRequestedThread()
            throws Exception {
        OresLanguage language = new OresLanguage();
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
    void exhaustiveRequestedCallerAndCarrierCombinationsRespectIdentityFence() {
        Thread calling = Thread.currentThread();
        Thread other = Thread.ofPlatform().unstarted(() -> { });
        // Enumerate every configuration of the single-thread flag, three
        // independent carrier kinds, and all requested/host identities.
        int transitionsChecked = 0;
        Thread[] requested = {calling, other, null};
        Thread[] hosts = {calling, other, null};
        for (Thread request : requested) {
            for (Thread host : hosts) {
                for (int mask = 0; mask < 16; mask++) {
                    boolean single = (mask & 1) != 0;
                    boolean actor = (mask & 2) != 0;
                    boolean async = (mask & 4) != 0;
                    boolean scheduler = (mask & 8) != 0;
                    boolean actual = OresLanguage.threadAccessDecision(
                            request, calling, host, single, actor, async, scheduler);
                    boolean authorizedOwner = request != null && request == host;
                    boolean authorizedCarrier = request != null
                            && request == calling && (actor || async || scheduler);
                    assertEquals(request != null
                                    && (single || authorizedOwner || authorizedCarrier),
                            actual, "bad thread admission state " + mask);
                    if (request != calling && request != host && !single) {
                        assertFalse(actual,
                                "carrier privilege cannot transfer to a foreign thread");
                    }
                    transitionsChecked++;
                }
            }
        }
        assertEquals(144, transitionsChecked);
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
        assertFalse(language.isThreadAccessAllowed(host, false));
    }
}
