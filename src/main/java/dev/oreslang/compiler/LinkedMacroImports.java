package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Strictly scoped compile-time macro link metadata for an already resolved
 * source set. This is not a runtime symbol table or executable artifact loader.
 *
 * <p>Only explicit, aliased `import * as alias from "..."` from a supplied
 * local source unit is admitted in M1. No ambient package lookup, direct
 * unchecked namespace qualification or implicit macro capture is possible.
 * Imported macro identity is pinned to the exact parsed declaration and its
 * canonical bounded content digest. The evaluator still decides which macro
 * forms it can safely execute before generating ordinary guest AST.</p>
 */
public final class LinkedMacroImports {
    static final int MAX_IMPORTED_MACROS = 128;

    private LinkedMacroImports() { }

    /** Bound compiler-only metadata, never a caller-constructible credential. */
    public static final class Binding {
        private final String sourceUnitId;
        private final String ownerModule;
        private final Ast.MacroDecl declaration;
        private final String contentSha256;

        private Binding(String sourceUnitId, String ownerModule, Ast.MacroDecl declaration) {
            this.sourceUnitId = MacroSourceUnitIdentity.normalizeUnitId(sourceUnitId);
            this.ownerModule = Objects.requireNonNull(ownerModule, "macro owner module");
            this.declaration = Objects.requireNonNull(declaration, "imported macro declaration");
            MacroExportArtifact artifact =
                    MacroExportArtifact.fromDeclaration(ownerModule, declaration);
            artifact.verifyAgainst(ownerModule, declaration);
            this.contentSha256 = artifact.contentSha256();
        }

        public String sourceUnitId() { return sourceUnitId; }
        public String ownerModule() { return ownerModule; }
        public Ast.MacroDecl declaration() { return declaration; }
        public String contentSha256() { return contentSha256; }

        public String canonicalOwnerIdentity() {
            return sourceUnitId + "::" + ownerModule;
        }

        public void verify() {
            MacroExportArtifact artifact =
                    MacroExportArtifact.fromDeclaration(ownerModule, declaration);
            if (!contentSha256.equals(artifact.contentSha256())) {
                throw new IllegalArgumentException(
                        "imported macro content identity changed after linking; fail closed");
            }
            artifact.verifyAgainst(ownerModule, declaration);
        }
    }

    /**
     * Collect public macros under their explicit import alias and reject
     * ambiguity. No metadata is returned if validation fails partway through.
     */
    static Map<String, Binding> collect(
            String importerUnitId,
            Ast.Program importer,
            Map<String, Ast.Program> sourcePrograms,
            Map<String, Map<String, String>> importResolutions) {
        Objects.requireNonNull(importer, "macro importer program");
        Objects.requireNonNull(sourcePrograms, "linked source programs");
        Objects.requireNonNull(importResolutions, "macro import resolutions");
        LinkedHashMap<String, Binding> linked = new LinkedHashMap<>();
        java.util.HashSet<String> aliases = new java.util.HashSet<>();
        for (Ast.ImportDecl imported : importer.imports()) {
            if (imported.kind() != Ast.ImportKind.ALL || !imported.wildcard()) {
                continue; // M1 requires a qualified, explicit wildcard alias.
            }
            // An alias names one import source, not a merged namespace of
            // unrelated source units or a preexisting local source module.
            String alias = imported.namespace();
            if (!aliases.add(alias)) {
                throw new IllegalArgumentException(
                        "duplicate compile-time macro import alias '" + alias + "'");
            }
            if (importer.modules().stream().anyMatch(module -> module.name().equals(alias))) {
                throw new IllegalArgumentException(
                        "compile-time macro import alias '" + alias
                                + "' collides with a local source module");
            }
            String sourceId = ImportGraph.resolveImportUnitId(
                    importerUnitId, imported, sourcePrograms.keySet(), importResolutions);
            if (sourceId == null) continue; // Opaque external imports grant no macro authority.
            Ast.Program source = sourcePrograms.get(sourceId);
            if (source == null) {
                throw new IllegalArgumentException(
                        "macro import source missing after graph resolution: " + sourceId);
            }
            for (Ast.ModuleDecl module : source.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (!(declaration instanceof Ast.MacroDecl macro)
                            || macro.visibility() != Ast.Visibility.PUBLIC) continue;
                    String key = imported.namespace() + "." + macro.name();
                    Binding binding = new Binding(sourceId, module.name(), macro);
                    if (linked.putIfAbsent(key, binding) != null) {
                        throw new IllegalArgumentException(
                                "ambiguous imported compile-time macro '" + key
                                        + "'; explicit alias/import source required");
                    }
                    if (linked.size() > MAX_IMPORTED_MACROS) {
                        throw new IllegalArgumentException(
                                "imported macro count exceeds " + MAX_IMPORTED_MACROS
                                        + "; runtime fallback forbidden");
                    }
                }
            }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(linked));
    }
}
