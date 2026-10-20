package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MlScalarPublicationGuestLoweringTest {
    private static final Path FIXTURE =
            Path.of("tests", "fixtures", "ml-scalar-publication-runtime.ores");

    private static void verify(String source) {
        Ast.Program program = Parser.parse(source);
        ActorStateContract.verify(program);
        DeclaredEffectVerifier.verify(program);
        TypeChecker.checkTypes(program);
        OwnershipChecker.check(program);
    }

    @Test void realGuestLowersSnapshotPrepareAndAtomicPublish() throws Exception {
        String source = Files.readString(FIXTURE);
        assertDoesNotThrow(() -> verify(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            Source executable = Source.newBuilder(
                    OresLanguage.ID,
                    source,
                    "ml-scalar-publication-runtime.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build();
            assertDoesNotThrow(() -> context.eval(executable));
        }
        assertEquals("true\n4\n2\n1\n",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test void actorLifecycleBarriersAreCanonicalZeroArgMethods() throws Exception {
        String source = Files.readString(FIXTURE);
        assertRejected(source.replace(
                "worker.get_ready()",
                "worker.get_ready(1)"));
        assertRejected(source.replace(
                "worker.get_done()",
                "worker.get_done(1)"));
    }

    @Test void snapshotMustUseActiveLoanAndCapturedTypedFields() {
        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    val snapshot = state_snapshot(self, "version", "weight");
                    return snapshot_int(snapshot, "weight");
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; enabled: bool = true; }
                private bad(): int with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "enabled");
                    });
                    return snapshot_int(snapshot, "enabled");
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version");
                    });
                    return 1;
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "version");
                    });
                    return 1;
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    return self.handle_state(critical read |s| -> {
                        val snapshot = state_snapshot(s, "version", "weight");
                        return snapshot_int(snapshot, "missing");
                    });
                }
            end
            """);
    }

    @Test void preparationRequiresReleasedLoanAndSnapshotBackedFields() {
        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; optimizer: int = 0; }
                private bad(): bool with state.read, state.write, alloc {
                    return self.handle_state(critical write |s| -> {
                        val snapshot = state_snapshot(s, "version", "weight");
                        val publication = prepare_scalar_publication(
                            snapshot,
                            "version", 2,
                            "weight", 4
                        );
                        return publish_scalar_publication(s, publication);
                    });
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; optimizer: int = 0; }
                private bad(): bool with state.read, state.write, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "weight");
                    });
                    val publication = prepare_scalar_publication(
                        snapshot,
                        "version", 2,
                        "optimizer", 1
                    );
                    return true;
                }
            end
            """);
    }

    @Test void publicationRequiresCriticalWriteLoanAndSingleUseCapabilityType() {
        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): bool with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "weight");
                    });
                    val publication = prepare_scalar_publication(
                        snapshot,
                        "version", 2,
                        "weight", 4
                    );
                    return self.handle_state(critical read |s| -> {
                        return publish_scalar_publication(s, publication);
                    });
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): bool with state.read, state.write {
                    return self.handle_state(critical write |s| -> {
                        return publish_scalar_publication(s, 7);
                    });
                }
            end
            """);
    }

    @Test void scalarCapabilitiesCannotBeLaunderedThroughOwnedSumValues() {
        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "weight");
                    });
                    val wrapped = Some(snapshot);
                    return 1;
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "weight");
                    });
                    val publication = prepare_scalar_publication(
                        snapshot,
                        "version", 2,
                        "weight", 4
                    );
                    val wrapped = Ok(publication);
                    return 1;
                }
            end
            """);
    }

    @Test void scalarCapabilitiesCannotUseGenericRuntimeOrIoSurfaces() {
        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "weight");
                    });
                    val duplicate = rt copy snapshot;
                    return 1;
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "weight");
                    });
                    stdio.println(snapshot);
                    return 1;
                }
            end
            """);

        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): bool with state.read, alloc {
                    val snapshot = self.handle_state(critical read |s| -> {
                        return state_snapshot(s, "version", "weight");
                    });
                    return snapshot == snapshot;
                }
            end
            """);
    }

    @Test void sealedIntrinsicNamesCannotBeReplacedByModuleValues() {
        assertRejected("""
            pub fnc state_snapshot(int x): int {
                return x;
            }
            """);
        assertRejected("""
            pub fnc bad(): int {
                val int prepare_scalar_publication = 3;
                return prepare_scalar_publication;
            }
            """);
        assertRejected("""
            pub fnc bad(int state_snapshot): int {
                return state_snapshot;
            }
            """);
        assertRejected("""
            define actor Bad as
                state { version: int = 1; weight: int = 5; }
                private bad(): int with state.read, alloc {
                    return self.handle_state(critical read |state_snapshot| -> {
                        return 1;
                    });
                }
            end
            """);
    }

    private static void assertRejected(String source) {
        assertDoesNotThrow(() -> Parser.parse(source),
                "negative fixture must reach semantic analysis");
        assertThrows(IllegalArgumentException.class, () -> verify(source));
    }
}
