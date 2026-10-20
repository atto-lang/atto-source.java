package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CallbackOrderingAnalyzerTest {
    private static List<StaticSafetyAnalyzer.Finding> scan(String source) {
        // Parser-only testing deliberately avoids claiming type/effect proof.
        // The production OresCompiler entry point also performs ownership admission.
        return StaticSafetyAnalyzer.analyze(Parser.parse(source));
    }

    private static boolean containsRule(List<StaticSafetyAnalyzer.Finding> findings, String rule) {
        return findings.stream().anyMatch(f -> f.rule().equals(rule));
    }

    @Test
    void mixedTimingCallbackBeforeRegistrationIsZalgo() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    doMore(|| -> {
                        randie.emit("done", true);
                    });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
        assertTrue(result.stream().filter(f -> f.rule().equals("ORES-CB-002"))
                .allMatch(f -> f.confidence().equals("potential")));
    }

    @Test
    void listenerInstalledBeforePublishingCallbackAvoidsOrderingWarning() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.on("done", cb);
                    doMore(|| -> {
                        randie.emit("done", true);
                    });
                }
                """);
        assertFalse(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void merelyRegisteringListenerDoesNotInvokeIt() {
        var result = scan("""
                pub fnc foo(): void {
                    let randie = bar();
                    randie.on("done", || -> {
                        randie.emit("done", true);
                    });
                }
                """);
        assertFalse(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void registeringFirstRequiresVerifiedListenerInstallationForStrictAdmission() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.on("done", cb);
                    doMore(|| -> { randie.emit("done", true); });
                }
                """);
        assertFalse(containsRule(result, "ORES-CB-002"), result.toString());
        assertTrue(containsRule(result, "ORES-CB-006"),
                "unverified installation must not be misrepresented as proven safe");
    }

    @Test
    void wildcardPriorSubscriptionCannotProveDefiniteRegistration() {
        var result = scan("""
                pub fnc foo(Callback cb, string eventName): void {
                    let randie = bar();
                    randie.on(eventName, cb);
                    doMore(|| -> { randie.emit("done", true); });
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-006"),
                "wildcard subscription must remain unverified rather than appear safe");
    }

    @Test
    void eventNameMustMatchUnlessDynamic() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    doMore(|| -> { randie.emit("ready", true); });
                    randie.on("done", cb);
                }
                """);
        assertFalse(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void detectsDirectLocalAliasOfEmitter() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    let local = randie;
                    doMore(|| -> { local.emit("done", true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void detectsLambdaStoredBeforeCallbackPublication() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    let fire = || -> { randie.emit("done", true); };
                    doMore(fire);
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void conditionalCallbackPublicationBeforeListenerIsSuspicious() {
        var result = scan("""
                pub fnc foo(Callback cb, bool flag): void {
                    let randie = bar();
                    if flag {
                        doMore(|| -> { randie.emit("done", true); });
                    } fi
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void dynamicEventMayAliasListenerName() {
        var result = scan("""
                pub fnc foo(Callback cb, string eventName): void {
                    let randie = bar();
                    doMore(|| -> { randie.emit(eventName, true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void removingListenerInvalidatesSyntacticRegistrationEvidence() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.on("done", cb);
                    randie.off("done", cb);
                    doMore(|| -> { randie.emit("done", true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"),
                "a removed listener cannot make later callback publication safe");
    }

    @Test
    void removeAllListenersInvalidatesRegistrationEvidence() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.on("done", cb);
                    randie.removeAllListeners();
                    doMore(|| -> { randie.emit("done", true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void dynamicRemovalInvalidatesPotentiallyMatchingRegistration() {
        var result = scan("""
                pub fnc foo(Callback cb, string dynamicEvent): void {
                    let randie = bar();
                    let alias = randie;
                    randie.on("done", cb);
                    alias.removeListener(dynamicEvent, cb);
                    doMore(|| -> { randie.emit("done", true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void removalOfOtherKnownEventRetainsDistinctSyntacticRegistration() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.on("done", cb);
                    randie.off("other", cb);
                    doMore(|| -> { randie.emit("done", true); });
                }
                """);
        assertFalse(containsRule(result, "ORES-CB-002"), result.toString());
        assertTrue(containsRule(result, "ORES-CB-006"),
                "retained registration remains syntactic, not a proven lease");
    }

    @Test
    void onceSubscriptionConsumedBeforeCallbackPublicationIsNotReady() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.once("done", cb);
                    randie.emit("done", true);
                    doMore(|| -> { randie.emit("done", true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"),
                "a consumed once listener must not satisfy future callback publication");
    }

    @Test
    void potentialInlineCallbackConsumesOneShotReadinessConservatively() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.once("done", cb);
                    doMore(|| -> { randie.emit("done", true); });
                    doMore(|| -> { randie.emit("done", true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"),
                "first unknown-timing callback may consume a one-shot listener");
    }

    @Test
    void durableListenerSurvivesOneShotEmission() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.on("done", cb);
                    randie.once("done", cb);
                    randie.emit("done", true);
                    doMore(|| -> { randie.emit("done", true); });
                }
                """);
        assertFalse(containsRule(result, "ORES-CB-002"), result.toString());
        assertTrue(containsRule(result, "ORES-CB-006"),
                "source-level durable subscription still lacks a verified installation lease");
    }

    @Test
    void onceUnknownEventConsumesMatchingKnownEvent() {
        var result = scan("""
                pub fnc foo(Callback cb, string evt): void {
                    let randie = bar();
                    randie.once("done", cb);
                    randie.emit(evt, true);
                    doMore(|| -> { randie.emit("done", true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"),
                "dynamic emit may match and consume once registration");
    }

    @Test
    void onceDetachedBeforePublicationIsNotReady() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    randie.once("done", cb);
                    randie.off("done", cb);
                    doMore(|| -> { randie.emit("done", true); });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void callbackEmissionInsideListExpressionCannotHideFromZalgoScanner() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    doMore(|| -> {
                        let values = [randie.emit("done", true)];
                    });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }

    @Test
    void callbackEmissionInsideConditionalGuardCannotHideFromScanner() {
        var result = scan("""
                pub fnc foo(Callback cb): void {
                    let randie = bar();
                    doMore(|| -> {
                        if randie.emit("done", true) {
                            let marker = 1;
                        } fi
                    });
                    randie.on("done", cb);
                }
                """);
        assertTrue(containsRule(result, "ORES-CB-002"), result.toString());
    }
}
