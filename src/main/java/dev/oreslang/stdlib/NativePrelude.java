package dev.oreslang.stdlib;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes selected Oreslang-owned stdlib namespaces/types part of the prelude.
 * This only assembles parsed .ores declarations; it implements no observable
 * or stream semantics in Java. Existing explicit imports are respected.
 *
 * Loading is demand driven to avoid changing unrelated compiler ASTs and
 * existing source programs merely because a stdlib file was packaged.
 */
public final class NativePrelude {
    private NativePrelude() { }

    public static Ast.Program attach(Ast.Program program, String source) {
        boolean needObservable = source.matches("(?s).*\\bObservable\\b.*")
                || source.matches("(?s).*\\.stream\\s*\\(.*")
                || source.matches("(?s).*\\.tee\\s*\\(.*");
        boolean needStreams = source.matches("(?s).*\\bReadableStream\\b.*")
                || source.matches("(?s).*\\bWritableTee\\b.*")
                || source.matches("(?s).*\\.stream\\s*\\(.*")
                || source.matches("(?s).*\\.tee\\s*\\(.*");
        if (!needObservable && !needStreams) return program;

        Set<String> present = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            present.add(module.name());
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) present.add(klass.name());
            }
        }
        for (Ast.ImportDecl imported : program.imports()) {
            if (!imported.wildcard()) {
                for (String name : imported.names()) {
                    present.add(imported.namespace() == null ? name : imported.namespace());
                }
            }
        }
        List<Ast.ModuleDecl> merged = new ArrayList<>(program.modules());
        if (needObservable && !present.contains("Observable")) {
            Ast.Program stdlib = parsePackaged("std/observable");
            for (Ast.ModuleDecl module : stdlib.modules()) {
                if (module.name().equals("Observable")) {
                    merged.add(module);
                    present.add("Observable");
                }
            }
        }
        if (needStreams) {
            Ast.Program stdlib = parsePackaged("std/streams");
            List<Ast.Decl> additions = new ArrayList<>();
            for (Ast.ModuleDecl module : stdlib.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.ClassDecl klass
                            && present.add(klass.name())) additions.add(declaration);
                }
            }
            if (!additions.isEmpty()) {
                merged.add(new Ast.ModuleDecl("$native_prelude", additions));
            }
        }
        return new Ast.Program(program.namespace(), program.imports(), merged);
    }

    private static Ast.Program parsePackaged(String path) {
        try {
            String source = CoreLibrarySources.source(path);
            // Parse raw: never recursively expand the prelude while loading it.
            return new Parser(new Lexer(source).scan()).parseProgram();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load packaged Oreslang prelude: " + path, failure);
        }
    }
}
