package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

final class StdErrorCatalogTest {
    @TempDir Path temp;

    // Keep this deliberately simple/explicit: no host reflection or Java
    // exception classes may silently define guest error type identity.
    private static final Pattern TYPE = Pattern.compile(
            "(?ms)^pub define (abstract )?class (\\w+)(?: extends (\\w+))?(?: implements (\\w+(?:, \\w+)*))? as\\R(.*?)^end$");
    private static final Pattern CODE = Pattern.compile(
            "pub code\\(\\): int \\{ return (\\d+); \\}");
    private static final Pattern CATALOG_ENTRY = Pattern.compile(
            "\"name\"\\s*:\\s*\"(\\w+)\"\\s*,\\s*\"parent\"\\s*:\\s*(null|\"\\w+\")"
            + "\\s*,\\s*\"code\"\\s*:\\s*(\\d+)\\s*,\\s*\"abstract\"\\s*:\\s*(true|false)"
            + "\\s*,\\s*\"interfaces\"\\s*:\\s*\\[([^\\]]*)\\]");

    record Entry(String parent, int code, boolean abstractClass, String iface) { }

    @Test
    void packagedCatalogMatchesStableManifestAndIsAcyclic() throws Exception {
        String source = CoreLibrarySources.source("std/error");
        assertEquals(source, CoreLibrarySources.source("std/error.ores"));
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("error.ores"));
        assertFalse(source.contains("java:"));
        assertFalse(Pattern.compile("(?m)^\\s*rt\\s").matcher(source).find());
        assertFalse(Pattern.compile("(?m)^\\s*import\\s").matcher(source).find());
        assertTrue(source.contains("pub define interface CodedError as"));
        assertTrue(source.contains("pub define interface RetryAdvisory extends CodedError as"));
        assertTrue(source.contains("pub define interface ActorApiError extends CodedError as"));
        assertTrue(source.contains("pub define interface Serializable as"));
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("tracing.ores"));
        TypeChecker.check(Parser.parse(CoreLibrarySources.source("std/tracing")));
        TypeChecker.check(Parser.parse(source));

        Map<String, Entry> parsed = new HashMap<>();
        Set<Integer> codes = new HashSet<>();
        Matcher declarations = TYPE.matcher(source);
        while (declarations.find()) {
            String name = declarations.group(2);
            String parent = declarations.group(3);
            Matcher implementation = CODE.matcher(declarations.group(5));
            assertTrue(implementation.find(), "missing code() method: " + name);
            int code = Integer.parseInt(implementation.group(1));
            boolean abstractClass = declarations.group(1) != null;
            String iface = declarations.group(4) == null ? null
                    : declarations.group(4).replace(" ", "");
            assertNull(parsed.put(name, new Entry(parent, code, abstractClass, iface)), name);
            assertTrue(codes.add(code), "duplicate error code " + code);
        }
        assertEquals(99, parsed.size(), "every catalog type must have an executable Oreslang code method");
        assertEquals(new Entry(null, 0, false, "CodedError,Serializable"), parsed.get("Error"));
        assertEquals(new Entry("IOError", 3090, true, null), parsed.get("FileSystemError"));
        assertEquals(new Entry("IOError", 4000, true, null), parsed.get("NetworkError"));
        assertEquals(new Entry("Error", 9000, true, "ActorApiError"), parsed.get("ActorError"));
        // Actor crash, cancellation and supervisor escalation are CONTROL outcomes,
        // not forgeable ordinary std/error classes.
        for (String controlOnly : new String[]{"ActorPanicError", "ActorCancellationError",
                "ActorSupervisorRestartLimitError", "ActorControlEnvelope"}) {
            assertFalse(parsed.containsKey(controlOnly), controlOnly);
        }
        assertEquals(10, parsed.values().stream().filter(Entry::abstractClass).count());
        assertEquals(5, parsed.values().stream().filter(e -> "RetryAdvisory".equals(e.iface())).count());
        long leaves = parsed.keySet().stream().filter(name ->
                parsed.keySet().stream().noneMatch(child -> name.equals(parsed.get(child).parent()))
                ).count();
        assertEquals(88, leaves); // Error is now concrete but not a leaf; descendants unchanged
        for (String name : parsed.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = name;
            while (current != null) {
                assertTrue(visited.add(current), "cycle in error ancestry: " + name);
                Entry entry = parsed.get(current);
                assertNotNull(entry, "missing parent " + current);
                current = entry.parent();
            }
        }

        String manifest = Files.readString(Path.of("contracts/v1/error-catalog.json"));
        assertTrue(manifest.contains("\"schemaVersion\": 1"));
        assertTrue(manifest.contains("\"std/error\""));
        Map<String, Entry> expected = new HashMap<>();
        Matcher catalog = CATALOG_ENTRY.matcher(manifest);
        while (catalog.find()) {
            String parent = catalog.group(2);
            String interfaces = catalog.group(5).trim();
            String normalized = interfaces.replaceAll("[\\\"\\s]", "");
            assertTrue(normalized.isEmpty() ||
                    normalized.matches("[A-Za-z][A-Za-z0-9]*(?:,[A-Za-z][A-Za-z0-9]*)*"), interfaces);
            String iface = normalized.isEmpty() ? null : normalized;
            assertNull(expected.put(catalog.group(1),
                    new Entry(parent.equals("null") ? null : parent.substring(1, parent.length() - 1),
                            Integer.parseInt(catalog.group(3)),
                            Boolean.parseBoolean(catalog.group(4)), iface)));
        }
        assertEquals(parsed, expected, "manifest/source drift or malformed catalog record");
        // Preserve legacy actor-adjacent core codes and subtype ancestry.
        assertEquals(new Entry("StateError", 2012, false, "ActorApiError"), parsed.get("ProcessNotFoundError"));
        assertEquals(new Entry("StateError", 2013, false, "ActorApiError"), parsed.get("MailboxFullError"));
        assertEquals(new Entry("ActorError", 9001, false, null), parsed.get("ActorSpawnRejectedError"));
        assertEquals(new Entry("ActorError", 9002, false, null), parsed.get("ActorInitializationError"));
        assertEquals(new Entry("ActorError", 9003, false, null), parsed.get("ActorTerminatedError"));
        assertEquals(new Entry("ActorError", 9004, false, null), parsed.get("ActorMailboxClosedError"));
        assertEquals(new Entry("ActorError", 9005, false, null), parsed.get("ActorMessageRejectedError"));
        assertEquals(new Entry("ActorError", 9006, false, null), parsed.get("ActorProtocolMismatchError"));
        assertEquals(new Entry("ActorError", 9007, false, null), parsed.get("ActorAskTimeoutError"));
        assertEquals(new Entry("ActorError", 9008, false, null), parsed.get("ActorReplyUnavailableError"));
        assertEquals(new Entry("ActorError", 9009, false, null), parsed.get("ActorReferenceExpiredError"));
        assertEquals(new Entry("ActorError", 9010, false, null), parsed.get("ActorNodeUnreachableError"));
        assertEquals(new Entry("ActorError", 9011, false, null), parsed.get("ActorNameConflictError"));
        assertEquals(new Entry("ActorError", 9012, false, null), parsed.get("ActorSpawnQuotaExceededError"));
        assertEquals(new Entry("ActorError", 9013, false, null), parsed.get("ActorSupervisorUnavailableError"));
        assertEquals(new Entry("ActorError", 9014, false, null), parsed.get("ActorOutputClosedError"));

        // Lock down established core error identities while extending the catalog.
        assertEquals(1006, parsed.get("CastError").code());
        assertEquals(2008, parsed.get("StaleHandleError").code());
        assertEquals(3001, parsed.get("FileNotFoundError").code());
        assertEquals(4002, parsed.get("ConnectionRefusedError").code());
        assertEquals(5001, parsed.get("TimeoutError").code());
        assertEquals(6008, parsed.get("SchemaMismatchError").code());
        assertEquals(7004, parsed.get("CapabilityDeniedError").code());
        assertEquals(8008, parsed.get("DependencyFailureError").code());
        assertEquals(new Entry("DataError", 6009, false, null), parsed.get("KeyNotFoundError"));
        assertEquals(new Entry("DataError", 6010, false, null), parsed.get("DuplicateKeyError"));
        assertEquals(new Entry("DataError", 6011, false, null), parsed.get("InvalidFormatError"));
        assertEquals(new Entry("StateError", 2009, false, null), parsed.get("EmptyCollectionError"));
        assertEquals(new Entry("StateError", 2010, false, null), parsed.get("IteratorExhaustedError"));
        assertEquals(new Entry("StateError", 2011, false, null), parsed.get("DeadlockDetectedError"));


        assertEquals(new Entry("OperationError", 8009, false, null), parsed.get("NotImplementedError"));
        assertEquals(new Entry("OperationError", 8010, false, null), parsed.get("ResourceExhaustedError"));
        assertThrows(IllegalArgumentException.class, () -> CoreLibrarySources.source("std/error/../core"));
        assertThrows(IllegalArgumentException.class, () -> CoreLibrarySources.source("std/unknown-error"));
    }

    @Test
    void importedSubtypeMatchesBeforeItsSupertypeAndDispatchesLeafCode() throws Exception {
        Path entry = temp.resolve("error-match.ores");
        Files.writeString(entry, """
                import class Error from "std/error";
                import class IOError from "std/error";
                import class FileSystemError from "std/error";
                import class FileNotFoundError from "std/error";
                import class NetworkError from "std/error";
                import class ConnectionRefusedError from "std/error";
                import class ArgumentError from "std/error";
                import class InvalidArgumentError from "std/error";
                import class TimeoutError from "std/error";
                import class RateLimitedError from "std/error";
                import class ConnectionResetError from "std/error";
                import interface RetryAdvisory from "std/error";

                define class Imposter extends Error as
                  pub code(): int { return 9002; }
                  pub retry_hint(): bool { return true; }
                end

                define class CustomAppError extends Error as
                  pub code(): int { return 9001; }
                end

                fnc classify(Error error): int {
                  match error over
                    on FileNotFoundError specific -> { return specific.code(); }
                    on FileSystemError file -> { return -10; }
                    on ConnectionRefusedError refused -> { return refused.code(); }
                    on NetworkError network -> { return -11; }
                    on IOError io -> { return -20; }
                    on InvalidArgumentError invalid -> { return invalid.code(); }
                    on ArgumentError argument -> { return -12; }
                    on Error other -> { return -30; }
                  end
                }

                fnc advisory(Error error): int {
                  match error over
                    on RetryAdvisory hint -> { return hint.code(); }
                    on Error other -> { return -1; }
                  end
                }

                pub routine main(): void {
                  stdio.stdout.write(classify(new FileNotFoundError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ConnectionRefusedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new InvalidArgumentError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new CustomAppError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(advisory(new TimeoutError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(advisory(new RateLimitedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(advisory(new ConnectionResetError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(advisory(new Imposter()));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals("3001|4002|1001|-30|5001|5006|4003|-1", out.toString(StandardCharsets.UTF_8));
    }


    @Test
    void expandedCoreErrorsImportAndMatchUsingNominalParents() throws Exception {
        Path entry = temp.resolve("expanded-errors.ores");
        Files.writeString(entry, """
                import class Error from "std/error";
                import class KeyNotFoundError from "std/error";
                import class DuplicateKeyError from "std/error";
                import class InvalidFormatError from "std/error";
                import class EmptyCollectionError from "std/error";
                import class IteratorExhaustedError from "std/error";
                import class DeadlockDetectedError from "std/error";
                import class ProcessNotFoundError from "std/error";
                import class MailboxFullError from "std/error";
                import class NotImplementedError from "std/error";
                import class ResourceExhaustedError from "std/error";

                fnc classify(Error error): int {
                  match error over
                    on KeyNotFoundError e -> { return e.code(); }
                    on DuplicateKeyError e -> { return e.code(); }
                    on InvalidFormatError e -> { return e.code(); }
                    on EmptyCollectionError e -> { return e.code(); }
                    on IteratorExhaustedError e -> { return e.code(); }
                    on DeadlockDetectedError e -> { return e.code(); }
                    on ProcessNotFoundError e -> { return e.code(); }
                    on MailboxFullError e -> { return e.code(); }
                    on NotImplementedError e -> { return e.code(); }
                    on ResourceExhaustedError e -> { return e.code(); }
                    on Error other -> { return -1; }
                  end
                }

                pub routine main(): void {
                  stdio.stdout.write(classify(new KeyNotFoundError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new DuplicateKeyError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new InvalidFormatError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new EmptyCollectionError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new IteratorExhaustedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new DeadlockDetectedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ProcessNotFoundError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new MailboxFullError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new NotImplementedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ResourceExhaustedError()));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals("6009|6010|6011|2009|2010|2011|2012|2013|8009|8010", out.toString(StandardCharsets.UTF_8));
    }


    @Test
    void actorApiErrorsHaveNominalCrossFamilyMatcherWithoutCollapsingSupervisorControl()
            throws Exception {
        Path entry = temp.resolve("actor-api-errors.ores");
        Files.writeString(entry, """
                import class Error from "std/error";
                import class ActorError from "std/error";
                import class StateError from "std/error";
                import class ProcessNotFoundError from "std/error";
                import class MailboxFullError from "std/error";
                import interface ActorApiError from "std/error";
                import class ActorSpawnRejectedError from "std/error";
                import class ActorInitializationError from "std/error";
                import class ActorTerminatedError from "std/error";
                import class ActorMailboxClosedError from "std/error";
                import class ActorMessageRejectedError from "std/error";
                import class ActorProtocolMismatchError from "std/error";
                import class ActorAskTimeoutError from "std/error";
                import class ActorReplyUnavailableError from "std/error";
                import class ActorReferenceExpiredError from "std/error";
                import class ActorNodeUnreachableError from "std/error";
                import class ActorNameConflictError from "std/error";
                import class ActorSpawnQuotaExceededError from "std/error";
                import class ActorSupervisorUnavailableError from "std/error";
                import class ActorOutputClosedError from "std/error";

                fnc classify(Error error): int {
                  match error over
                    on ActorSpawnRejectedError specific -> { return specific.code(); }
                    on ActorInitializationError specific -> { return specific.code(); }
                    on ActorTerminatedError specific -> { return specific.code(); }
                    on ActorMailboxClosedError specific -> { return specific.code(); }
                    on ActorMessageRejectedError specific -> { return specific.code(); }
                    on ActorProtocolMismatchError specific -> { return specific.code(); }
                    on ActorAskTimeoutError specific -> { return specific.code(); }
                    on ActorReplyUnavailableError specific -> { return specific.code(); }
                    on ActorReferenceExpiredError specific -> { return specific.code(); }
                    on ActorNodeUnreachableError specific -> { return specific.code(); }
                    on ActorNameConflictError specific -> { return specific.code(); }
                    on ActorSpawnQuotaExceededError specific -> { return specific.code(); }
                    on ActorSupervisorUnavailableError specific -> { return specific.code(); }
                    on ActorOutputClosedError specific -> { return specific.code(); }
                    on ActorError otherActor -> { return -90; }
                    on ProcessNotFoundError missing -> { return missing.code(); }
                    on MailboxFullError full -> { return full.code(); }
                    on ActorApiError actorApi -> { return -80; }
                    on Error other -> { return -1; }
                  end
                }

                pub routine main(): void {
                  stdio.stdout.write(classify(new ActorSpawnRejectedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorInitializationError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorTerminatedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorMailboxClosedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorMessageRejectedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorProtocolMismatchError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorAskTimeoutError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorReplyUnavailableError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorReferenceExpiredError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorNodeUnreachableError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorNameConflictError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorSpawnQuotaExceededError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorSupervisorUnavailableError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ActorOutputClosedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new ProcessNotFoundError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(classify(new MailboxFullError()));
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), output,
                new ByteArrayOutputStream());
        assertEquals("9001|9002|9003|9004|9005|9006|9007|9008|9009|9010|9011|9012|9013|9014|2012|2013",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void actorApiErrorInterfaceIsNominalAndCannotBeForgedByMethodShape() throws Exception {
        Path entry = temp.resolve("actor-api-impostor.ores");
        Files.writeString(entry, """
                import class Error from "std/error";
                import class ActorTerminatedError from "std/error";
                import class MailboxFullError from "std/error";
                import interface ActorApiError from "std/error";

                define class Impostor extends Error as
                  pub code(): int { return 99123; }
                end

                fnc check(Error failure): int {
                  match failure over
                    on ActorApiError actor -> { return actor.code(); }
                    on Error other -> { return -1; }
                  end
                }

                pub routine main(): void {
                  stdio.stdout.write(check(new ActorTerminatedError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(check(new MailboxFullError()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(check(new Impostor()));
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), output,
                new ByteArrayOutputStream());
        assertEquals("9003|2013|-1", output.toString(StandardCharsets.UTF_8));
    }


    @Test
    void errorConstructionIsTraceFreeAndJsonCompactIsExplicit() throws Exception {
        Path entry = temp.resolve("error-serialization.ores");
        Files.writeString(entry, """
                import class Error from "std/error";
                import class ActorAskTimeoutError from "std/error";

                pub routine main(): void {
                  val e = new Error();
                  stdio.stdout.write(e.to_json());
                  stdio.stdout.write("|");
                  stdio.stdout.write(new Error(false, false, "private").to_json());
                  stdio.stdout.write("|");
                  stdio.stdout.write(new ActorAskTimeoutError(true, true, "delayed").to_json());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals(
                "{\"schema\":1,\"code\":0,\"name\":\"Error\",\"message\":\"\",\"stack\":[]}"
                + "|{\"schema\":1,\"code\":0,\"name\":\"Error\"}"
                + "|{\"schema\":1,\"code\":9007,\"name\":\"ActorAskTimeoutError\",\"message\":\"delayed\",\"stack\":[]}",
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void ancestorFirstMatchingIsRejectedRatherThanSilentlyShadowingLeaf() throws Exception {
        String source = CoreLibrarySources.source("std/error");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source + """
                    
                    fnc classify(Error error): int {
                      match error over
                        on Error broad -> { return 1; }
                        on FileNotFoundError dead -> { return 2; }
                      end
                    }
                    """)));
        assertTrue(failure.getMessage().contains("unreachable match arm"), failure::getMessage);
    }

    @Test
    void openErrorFamiliesNeedAProvableFallback() throws Exception {
        String source = CoreLibrarySources.source("std/error");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source + """
                    
                    fnc incomplete(Error failure): int {
                      match failure over
                        on FileNotFoundError missing -> { return 1; }
                        on NetworkError network -> { return 2; }
                      end
                    }
                    """)));
        assertTrue(failure.getMessage().contains("non-exhaustive match"), failure::getMessage);
    }

    @Test
    void abstractErrorCategoriesCannotBeConstructed() throws Exception {
        String source = CoreLibrarySources.source("std/error");
        for (String category : new String[]{"ArgumentError", "IOError",
                "FileSystemError", "NetworkError", "TimingError", "ActorError"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source + "\nfnc bad(): void { val x = new "
                            + category + "(); return; }")));
            assertTrue(failure.getMessage().contains("cannot instantiate abstract class"), failure::getMessage);
        }
    }

    @Test
    void concreteBaseErrorAllowsInheritedCodeAndSerializableMethods() throws Exception {
        String source = CoreLibrarySources.source("std/error");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source + """
                
                define class UserError extends Error as
                end
                fnc example(): int {
                  val UserError e = new UserError();
                  return e.code();
                }
                """)));
    }

    @Test
    void retryAdvisoryRequiresMethodContractAndNeverUsesStructuralDuckTyping() throws Exception {
        String source = CoreLibrarySources.source("std/error");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source + """
                    
                    define class MalformedHint extends Error implements RetryAdvisory as
                      pub code(): int { return 9003; }
                    end
                    """)));
        assertTrue(failure.getMessage().contains("does not implement interface"), failure::getMessage);
    }

    @Test
    void effectSyntaxTypeChecksBeforeFailClosedGuestLowering() {
        for (String keyword : new String[]{"throw", "raise", "panic"}) {
            Ast.Program program = Parser.parse("fnc f(): void { " + keyword + " 1; return; }");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(program));
            assertTrue(failure.getMessage().contains("requires a nominal std/error.Error value"),
                    failure::getMessage);
        }
        IllegalArgumentException recover = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f(): void { recover 1; return; }"));
        assertTrue(recover.getMessage().contains("not yet enabled"), recover::getMessage);
    }
}
