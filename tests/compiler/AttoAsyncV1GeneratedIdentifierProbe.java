package atto.compiler.asyncv1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Native-JDK-only generated-source proof for legal guest identifiers.
 * The restricted compiler must either reject a name or emit Java that javac
 * accepts without warnings. This is NOT async scheduling/cancellation proof.
 */
public final class AttoAsyncV1GeneratedIdentifierProbe {
    private AttoAsyncV1GeneratedIdentifierProbe() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new AssertionError("unexpected probe arguments");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("JDK compiler required");
        String[] accepted = {
            "execute", "main", "args", "math", "system", "string", "object",
            "class_loader", "future", "completable_future", "clone",
            "to_string", "notify", "get_class", "plus_one", "__",
            "susp_data", "sync_data", "native_image"
        };
        String[] rejected = { "_", "Math", "Future", "System" };
        Path root = Files.createTempDirectory("atto-async-v1-generated-names-");
        int compiled = 0;
        int denied = 0;
        try {
            for (String word : accepted) {
                String guest = source(word);
                String generated = AttoAsyncV1Compiler.compile(guest);
                Path dir = root.resolve("case_" + compiled);
                Files.createDirectory(dir);
                Path javaFile = dir.resolve("AttoAsyncV1Guest.java");
                Files.writeString(javaFile, generated, StandardCharsets.UTF_8);
                int code = compiler.run(null, null, null, "-Xlint:all", "-Werror",
                    "-d", dir.toString(), javaFile.toString());
                if (code != 0 || !Files.isRegularFile(dir.resolve("AttoAsyncV1Guest.class"))) {
                    throw new AssertionError("admitted name generated invalid Java: " + word);
                }
                compiled++;
            }
            for (String word : rejected) {
                try {
                    AttoAsyncV1Compiler.compile(source(word));
                    throw new AssertionError("invalid identifier accepted: " + word);
                } catch (AttoAsyncV1Compiler.Reject expected) {
                    denied++;
                }
            }
        } finally {
            // Never leave generated source/class artifacts after a probe.
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        if (compiled != accepted.length || denied != rejected.length) {
            throw new AssertionError("identifier admission matrix incomplete");
        }
        System.out.println("ATTO_ASYNC_V1_GENERATED_NAMES_PASS accepted=" + compiled
            + " rejected=" + denied);
    }

    private static String source(String name) {
        return "fnc " + name + "(int x): int { ^ x + 1; } "
            + "pub susp fnc main(): int { "
            + "val pending = task<int> { return 41; }; "
            + "^ pending |> susp |f| -> await f |> " + name
            + " >> val answer: int; }";
    }
}
