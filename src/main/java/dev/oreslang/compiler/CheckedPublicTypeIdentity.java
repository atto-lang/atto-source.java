package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.TypeChecker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in, checked local-unit type identity entry point.
 *
 * Unlike CanonicalTypeIdentity.of's low-level API, public symbol origins
 * here come directly from TypeChecker's validated nominal symbol table.
 * This deliberately rejects imports, unresolved aliases, private layouts,
 * and overloads not uniquely identified by module/name/arity.
 *
 * The caller-supplied environment header still requires trusted compiler
 * provenance before these IDs can authorize persistent cache reuse.
 */
public final class CheckedPublicTypeIdentity {
    private CheckedPublicTypeIdentity() { }

    public static CanonicalTypeIdentity.TypeId publicFunctionReturnType(
            Ast.Program source, String unitId,
            ExportInterfaceCodec.Header header, String moduleName,
            String functionName, int arity) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(header, "header");
        if (unitId == null || unitId.isBlank() || moduleName == null || moduleName.isBlank()
                || functionName == null || functionName.isBlank() || arity < 0) {
            throw new IllegalArgumentException("invalid exported callable selector");
        }
        TypeChecker.CheckedPublicNominals checked =
                TypeChecker.checkTypesAndSnapshotPublicNominals(source);
        Ast.Program program = checked.program();
        // Reject private/native layout and incomplete public interfaces before
        // committing its ABI to any nominal type identity.
        ExportInterfaceCodec.InterfaceFile publicApi =
                ExportInterfaceCollector.collect(program, header);
        Map<String, CanonicalTypeIdentity.Nominal> nominals = new HashMap<>();
        for (Map.Entry<String, TypeChecker.PublicNominalOrigin> symbol
                : checked.resolvedNames().entrySet()) {
            TypeChecker.PublicNominalOrigin declaration = symbol.getValue();
            nominals.put(symbol.getKey(), new CanonicalTypeIdentity.Nominal(
                    unitId, header.namespace(),
                    declaration.module() + "." + declaration.symbol(),
                    publicApi.sha256()));
        }
        Ast.FunctionDecl matched = null;
        for (Ast.ModuleDecl module : program.modules()) {
            if (!module.name().equals(moduleName)) continue;
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function
                        && function.name().equals(functionName)
                        && function.parameters().size() == arity
                        && function.visibility() == Ast.Visibility.PUBLIC) {
                    if (matched != null) {
                        throw new IllegalArgumentException("ambiguous exported callable");
                    }
                    matched = function;
                }
            }
        }
        if (matched == null) {
            throw new IllegalArgumentException("public callable not found");
        }
        return CanonicalTypeIdentity.of(
                matched.returnType(), nominals, matched.genericParameters());
    }
}
