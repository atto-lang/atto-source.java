package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Copy policy v1: source compiler checks, not host-language object copying. */
final class CopyPolicyCompilerTest {

    private static final String HEADER = """
            define class Vault as
              @NoCopy
              pub val int token = 42;
            end
            """;

    @Test
    void classPolicySurvivesParsingAndAnnotationExpansion() {
        String source = """
                @NoCopy
                define class Connection as
                  pub val int id = 7;
                end
                """;
        Ast.Program parsed = Parser.parse(source);
        Ast.ClassDecl initial = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        assertEquals("NoCopy", initial.annotations().getFirst().name());
        Ast.Program checked = TypeChecker.checkTypes(parsed);
        Ast.ClassDecl expanded = (Ast.ClassDecl) checked.modules().getFirst().declarations().getFirst();
        assertEquals(initial.annotations(), expanded.annotations());
    }

    @Test
    void nonCopyableAliasUsesCanonicalHardPolicy() {
        for (String operator : java.util.List.of("copy!", "copy!!", "rt copy")) {
            String source = """
                    @NonCopyable
                    define class Expensive as
                      pub val int value = 42;
                    end
                    fnc bad(Expensive original): Expensive {
                      return %s original;
                    }
                    """.formatted(operator);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source));
            assertTrue(failure.getMessage().contains("E_COPY_FORBIDDEN"), failure::toString);
        }
        Ast.Program parsed = Parser.parse("@NonCopyable define class Expensive as end");
        var klass = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        assertEquals("NoCopy", klass.annotations().getFirst().name());
    }

    @Test
    void nonCopyableAliasRejectsDuplicatesAndConflicts() {
        for (String markers : java.util.List.of(
                "@NoCopy @NonCopyable", "@NonCopyable @Copyable",
                "@NonCopyable @WarnOnCopy", "@NonCopyable(level=2)")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(markers + " define class Expensive as end"));
            assertTrue(error.getMessage().contains("E_COPY_ANNOTATION"), error::toString);
        }
    }

    @Test
    void nonCopyableChildCannotBeHiddenByEmptyTypedArray() {
        String source = """
                @NonCopyable
                define class Expensive as
                  pub val int value = 42;
                end
                pub fnc main(): void {
                  val Array<Expensive> items = new Array<Expensive>();
                  val Array<Expensive> cloned = copy!! items;
                  return;
                }
                """;
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("E_COPY_FORBIDDEN"), error::toString);
    }

    @Test
    void noCopyClassCannotBeExplicitlyCopied() {
        String source = """
                @NoCopy
                define class Connection as
                  pub val int id = 7;
                end
                fnc bad(Connection connection): Connection {
                  return rt copy connection;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void noCopyFieldCannotBeExplicitlyCopied() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt copy vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Vault.token"), ex.getMessage());
    }

    @Test
    void noCopyFieldCannotBeExtractedIntoNewOwnedValue() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void borrowOfNoCopyFieldIsPermitted() {
        String source = HEADER + """
                fnc good(Vault vault): void {
                  val tokenView = rt borrow vault.token;
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void warnOnCopyIsAvailableInTheCompilerDiagnosticsApi() {
        String source = """
                define class Config as
                  @WarnOnCopy
                  pub val int version = 1;
                end
                fnc copyVersion(Config config): int {
                  return rt copy config.version;
                }
                """;
        OresCompiler.AnalysisResult result =
                assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckWithDiagnostics(source));
        assertEquals(1, result.warnings().stream()
                .filter(warning -> warning.contains("W_COPY_DISCOURAGED")).count());
        assertTrue(result.warnings().stream()
                .anyMatch(warning -> warning.contains("Config.version")));
    }

    @Test
    void shallowCopyableAnnotationDoesNotRequireDeepConsent() {
        String source = """
                @Copyable
                define class Config as
                  pub val int version = 1;
                end
                fnc duplicate(Config original): Config {
                  return copy! original;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void copyableAnnotationDoesNotBypassUncopyableResourceFields() {
        String source = """
                @Copyable
                define class Config as
                  pub let Channel<int> connection;
                end
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_TRANSITIVE"), ex.getMessage());
    }

    @Test
    void conflictingCopyFieldAnnotationsAreRejected() {
        String source = """
                define class Config as
                  @NoCopy
                  @WarnOnCopy
                  pub val int version = 1;
                end
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_ANNOTATION"), ex.getMessage());
    }

    @Test
    void unknownClassAnnotationsStillFailClosed() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("@Audited define class Config as end"));
        assertTrue(ex.getMessage().contains("silently discarded"), ex.getMessage());
    }

    @Test
    void unsupportedCopyPolicyArgumentsAreRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("@NoCopy(level=2) define class Config as end"));
        assertTrue(ex.getMessage().contains("E_COPY_ANNOTATION"), ex.getMessage());
    }

    @Test
    void nestedNoCopyFieldCannotBeLaunderedThroughProjection() {
        String source = """
                define class Inner as
                  @NoCopy
                  pub val int secret = 4;
                end
                define class Outer as
                  pub val Inner inner = new Inner();
                end
                fnc bad(Outer outer): int {
                  return rt copy outer.inner.secret;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Inner.secret"), ex.getMessage());
    }

    @Test
    void legacyShareCannotBypassNoCopyFieldPolicy() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt share vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void ordinaryFieldReadDoesNotTriggerExplicitCopyWarning() {
        String source = """
                define class Config as
                  @WarnOnCopy
                  pub val int version = 1;
                end
                fnc readVersion(Config config): int {
                  return config.version;
                }
                """;
        OresCompiler.AnalysisResult result =
                assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckWithDiagnostics(source));
        assertTrue(result.warnings().stream().noneMatch(
                warning -> warning.contains("W_COPY_DISCOURAGED")), result.warnings().toString());
    }

    @Test
    void copyPolicyOnModuleOrFunctionIsRejected() {
        for (String source : new String[] {
                "@NoCopy define module stuff as end",
                "@NoCopy fnc f(): void { return; }"
        }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source));
            assertTrue(ex.getMessage().contains("E_COPY_ANNOTATION"), ex.getMessage());
        }
    }

    @Test
    void noCopyClassCanTransferWholeOwnerWithRtTake() {
        String source = """
                @NoCopy
                define class Connection as
                  pub val int id = 7;
                end
                fnc transfer(Connection connection): Connection {
                  return rt take connection;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void rtTakeInvalidatesNoCopySourceOwner() {
        String source = """
                @NoCopy
                define class Connection as
                  pub val int id = 7;
                end
                fnc bad(Connection connection): int {
                  val Connection moved = rt take connection;
                  return connection.id;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("moved"), ex.getMessage());
    }

    @Test
    void partialMoveOfNoCopyFieldFailsWithMoveProofErrorNotCopyError() {
        String source = """
                define class Vault as
                  @NoCopy
                  pub let int token = 42;
                end
                fnc bad(Vault vault): int {
                  return rt take vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_MOVE_UNPROVEN"), ex.getMessage());
        assertFalse(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void protectedFieldCannotEscapeThroughExplicitCastToOwnedValue() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt copy (vault.token as int);
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void dynamicIndexedAccessCannotSelectProtectedClassFieldForCopy() {
        String source = HEADER + """
                fnc bad(Vault vault, String key): int {
                  return rt copy vault[key];
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void protectedFieldCannotBeCopiedThroughConstantIndexedAccess() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt copy vault["token"];
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void ordinaryMutableClassFieldMoveFailsWithoutPartialMoveLedger() {
        String source = """
                define class Holder as
                  pub let int id = 42;
                end
                fnc bad(Holder holder): int {
                  return rt take holder.id;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_MOVE_UNPROVEN"), ex.getMessage());
    }

    @Test
    void immutableProtectedFieldMoveCannotBeReclassifiedAsLegalMove() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt take vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("immutable/final"), ex.getMessage());
    }

    @Test
    void castWrappedOrdinaryFieldMoveCannotEvadeLedgerRequirement() {
        String source = """
                define class Holder as
                  pub let int id = 42;
                end
                fnc bad(Holder holder): int {
                  return rt take (holder.id as int);
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_MOVE_UNPROVEN"), ex.getMessage());
    }

    @Test
    void conditionalCannotLaunderProtectedFieldThroughRtCopy() {
        String source = HEADER + """
                fnc bad(Vault vault, bool choose): int {
                  return rt copy (choose ? vault.token : 7);
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_COPY_FORBIDDEN"), failure.getMessage());
    }

    @Test
    void conditionalCannotLaunderOrdinaryPartialMoveThroughRtTake() {
        String source = """
                define class Holder as
                  pub let int id = 42;
                end
                fnc bad(Holder holder, bool choose): int {
                  return rt take (choose ? holder.id : 7);
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_MOVE_UNPROVEN"), failure.getMessage());
    }

    @Test
    void valueMatchCannotLaunderProtectedFieldThroughRtCopy() {
        String source = HEADER + """
                fnc bad(Vault vault, bool choose): int {
                  return rt copy (match choose over {
                    on true { return vault.token; }
                    on false { return 7; }
                  });
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_COPY_FORBIDDEN"), failure.getMessage());
    }

    @Test
    void returningOrdinaryClassFieldAliasDoesNotClone() {
        String source = """
                define class Resource as
                  pub val int token = 8;
                end
                define class Holder as
                  pub val Resource resource = new Resource();
                end
                fnc bad(Holder holder): Resource {
                  return holder.resource;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void passingOrdinaryClassFieldAliasDoesNotClone() {
        String source = """
                define class Resource as
                  pub val int token = 8;
                end
                define class Holder as
                  pub val Resource resource = new Resource();
                end
                fnc consume(Resource resource): void { return; }
                fnc bad(Holder holder): void {
                  consume(holder.resource);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void borrowOfOrdinaryMoveOnlyClassFieldRemainsLegal() {
        String source = """
                define class Resource as
                  pub val int token = 8;
                end
                define class Holder as
                  pub val Resource resource = new Resource();
                end
                fnc good(Holder holder): void {
                  val borrow Resource view = rt borrow holder.resource;
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }
    @Test
    void deepCopyRequiresExplicitConsentEvenThroughLegacyDeepOperator() {
        String source = """
                define class Flat as pub let int value = 42; end
                fnc duplicate(Flat x): Flat { return %s x; }
                """;
        for (String op : java.util.List.of("copy!!", "rt copy")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source.formatted(op)));
            assertTrue(failure.getMessage().contains("E_COPY_OPT_IN_REQUIRED"), failure::toString);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source.formatted("copy!")));
    }

    @Test
    void deepConsentCannotBeInheritedOrSmuggledThroughEmptyContainer() {
        String nested = """
                define class Leaf as pub let int value = 42; end
                define class Root implements DeepCopyable<Root> as
                    pub val Array<Leaf> items = new Array<Leaf>();
                end
                fnc duplicate(Root x): Root { return copy!! x; }
                """;
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(nested));
        assertTrue(error.getMessage().contains("E_COPY_OPT_IN_REQUIRED"), error::toString);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(nested.replace(
                "define class Leaf as", "define class Leaf implements DeepCopyable<Leaf> as")));
        String inherited = """
                define class Base implements DeepCopyable<Base> as pub let int n = 1; end
                define class Child extends Base as pub let int m = 2; end
                fnc duplicate(Child x): Child { return copy!! x; }
                """;
        var inheritedError = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(inherited));
        assertTrue(inheritedError.getMessage().contains("E_COPY_OPT_IN_REQUIRED"), inheritedError::toString);
    }

    @Test
    void coreDeepMarkerCannotBeSpoofedOrNameWrongSelf() {
        for (String source : java.util.List.of(
                "define class DeepCopyable as end",
                "define class Other as end define class Wrong implements DeepCopyable<Other> as end",
                "define class Wrong implements DeepCopyable<int> as end")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source));
            assertTrue(error.getMessage().contains("DeepCopyable"), error::toString);
        }
    }

}
