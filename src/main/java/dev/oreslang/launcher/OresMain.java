package dev.oreslang.launcher;

import dev.oreslang.OresLanguage;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OresMain {
    private OresMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args.length > 2) {
            System.err.println("usage: ores [--strict-isolate] <file.ores>");
            System.exit(2);
        }

        boolean strict = args.length == 2 && args[0].equals("--strict-isolate");
        String filename = strict ? args[1] : args[0];
        Path path = Path.of(filename);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("not a file: " + path);

        Context.Builder builder = strict
                ? IsolatePolicy.strictFaas().restrictedContextBuilder()
                : Context.newBuilder(OresLanguage.ID).allowAllAccess(false);

        Source source = Source.newBuilder(OresLanguage.ID, new File(filename))
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = builder.build()) {
            context.eval(source);
        }
    }
}
