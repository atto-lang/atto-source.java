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
        assertFalse(containsRule(result, "ORES-CB-006"),
                "wildcard subscription must not establish a definite installed listener");
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
}
