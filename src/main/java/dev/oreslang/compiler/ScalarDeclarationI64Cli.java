package dev.oreslang.compiler;

import dev.oreslang.runtime.LocalVirtualActorI64;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Experimental executable i64 declaration runner. Not the production
 * oreslang compiler; supports only the explicit restricted declaration form
 * admitted by ProtectedScalarMirV1 from the production Parser AST.
 *
 * Usage: ScalarDeclarationI64Cli <source.atto> <signed-i64-delta> ...
 */
public final class ScalarDeclarationI64Cli {
    private ScalarDeclarationI64Cli() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 65) {
            System.err.println("usage: ScalarDeclarationI64Cli <source.atto> <i64-delta>...");
            System.exit(2);
            return;
        }
        Path file = Path.of(args[0]);
        if (!Files.isRegularFile(file) || Files.size(file) > 8192) {
            throw new IllegalArgumentException("restricted guest source must be a regular file <=8192 bytes");
        }
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var declaration = ProtectedScalarMirV1.lower(source);
        long[] deltas = new long[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            deltas[i - 1] = Long.parseLong(args[i]);
        }

        switch (declaration.kind()) {
            case STATE_CONTAINER -> {
                try (var instance = declaration.new_state_container()) {
                    for (long delta : deltas) declaration.invoke(instance, delta);
                    System.out.println(instance.tryRead().orElseThrow());
                }
            }
            case VIRTUAL_ACTOR -> {
                var controls = new ArrayList<LocalVirtualActorI64.Notice>();
                int processed = 0;
                try (var runtime = declaration.new_virtual_runtime("cli-local", 1, 1, controls::add)) {
                    var reference = runtime.resolve("cli-default");
                    for (long delta : deltas) {
                        var admission = runtime.try_send(reference, delta);
                        if (!admission.admitted())
                            throw new IllegalStateException("virtual actor admission denied: " + admission.kind());
                        if (!runtime.run_one(reference))
                            throw new IllegalStateException("virtual actor turn did not execute");
                        var outcome = admission.completion().join();
                        if (outcome == LocalVirtualActorI64.Outcome.PROCESSED) {
                            processed++;
                        } else {
                            throw new IllegalStateException("virtual actor message failed: " + outcome);
                        }
                    }
                }
                if (!controls.isEmpty())
                    throw new IllegalStateException("virtual actor supervisor notices emitted");
                System.out.println("processed=" + processed);
            }
        }
    }
}
