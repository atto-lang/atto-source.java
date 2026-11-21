package dev.oreslang.compiler;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File-granular incremental compiler.
 *
 * Each source file is an independently versioned compilation/code unit.
 * Source edits always rebuild their own unit. Importers rebuild only when the
 * dependency's exported ABI digest changes, so implementation-only edits stay
 * local while public contract changes invalidate the necessary dependents.
 */
public final class IncrementalCompiler {
    private final Map<String, CompiledUnit> cache = new LinkedHashMap<>();

    public synchronized BuildResult compile(Map<String, String> sources) {
        return compile(sources, Map.of());
    }

    public synchronized BuildResult compile(
            Map<String, String> sources,
            Map<String, Map<String, String>> importResolutions) {
        if (importResolutions == null) throw new IllegalArgumentException("import resolutions cannot be null");
        if (sources.isEmpty()) return new BuildResult(Map.of(), Set.of(), Set.of(), List.of());

        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String id = normalizeUnitId(entry.getKey());
            if (normalized.putIfAbsent(id, entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate source unit '" + id + "'");
            }
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, String> abiHashes = new LinkedHashMap<>();
        Map<String, Ast.Program> parsed = new LinkedHashMap<>();

        // Parse the complete source set before resolving imports. This is the
        // first half of cycle tolerance: A may name B while B names A because
        // neither unit is recursively compiled while discovering the other.
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            hashes.put(entry.getKey(), digest(entry.getValue()));
            Ast.Program program = AnnotationExpander.expand(Parser.parse(entry.getValue()));
            parsed.put(entry.getKey(), program);
            abiHashes.put(entry.getKey(), abiDigest(program));
        }

        Map<String, Map<String, String>> normalizedImportResolutions =
                normalizeImportResolutions(importResolutions);
        ImportGraph.validateLinkedImports(parsed, normalizedImportResolutions);
        Map<String, Set<String>> dependencies =
                ImportGraph.resolveDependencies(parsed, normalized.keySet(), normalizedImportResolutions);
        List<List<String>> initializationGroups = ImportGraph.initializationGroups(dependencies);

        LinkedHashSet<String> dirty = new LinkedHashSet<>();
        LinkedHashSet<String> abiChanged = new LinkedHashSet<>();
        for (String id : normalized.keySet()) {
            CompiledUnit previous = cache.get(id);
            boolean sourceChanged = previous == null || !previous.sourceDigest().equals(hashes.get(id));
            boolean depsChanged = previous == null || !previous.dependencies().equals(dependencies.get(id));
            if (sourceChanged || depsChanged) dirty.add(id);
            if (previous == null || !previous.abiDigest().equals(abiHashes.get(id))) abiChanged.add(id);
        }

        /*
         * ABI changes invalidate the full reverse-import closure. This remains
         * conservative until the cross-unit linker tracks exactly which public
         * imported symbols are re-exported by each dependent. Crucially,
         * implementation-only source changes do not enter this queue.
         */
        Map<String, Set<String>> reverse = reverseDependencies(dependencies);
        ArrayDeque<String> abiQueue = new ArrayDeque<>(abiChanged);
        LinkedHashSet<String> abiAffected = new LinkedHashSet<>(abiChanged);
        while (!abiQueue.isEmpty()) {
            String changed = abiQueue.removeFirst();
            for (String dependent : reverse.getOrDefault(changed, Set.of())) {
                dirty.add(dependent);
                if (abiAffected.add(dependent)) abiQueue.addLast(dependent);
            }
        }

        LinkedHashMap<String, CompiledUnit> next = new LinkedHashMap<>();
        LinkedHashSet<String> rebuilt = new LinkedHashSet<>();
        LinkedHashSet<String> reused = new LinkedHashSet<>();

        for (String id : normalized.keySet()) {
            if (!dirty.contains(id) && cache.containsKey(id)) {
                next.put(id, cache.get(id));
                reused.add(id);
                continue;
            }

            Ast.Program checked;
            LinkedClasses linked = linkedPublicClasses(
                    id, parsed.get(id), parsed, normalizedImportResolutions);
            if (linked.classes().isEmpty()) {
                checked = OresCompiler.parseAndTypeCheck(normalized.get(id));
            } else {
                try {
                    // Foreign layouts are used for type and ownership proofs;
                    // their declarations are never executed in the importer.
                    checked = TypeChecker.checkTypesWithImportedClasses(
                            Parser.parse(normalized.get(id)), linked.classes(), linked.aliases());
                    OwnershipChecker.checkWithImportedClasses(checked, linked.classes());
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(
                            "Oreslang source unit '" + id + "': " + invalid.getMessage(), invalid);
                }
            }
            CompiledUnit unit = new CompiledUnit(
                    id,
                    packageId(id, checked),
                    checked.namespace(),
                    hashes.get(id),
                    abiHashes.get(id),
                    dependencies.get(id),
                    normalized.get(id),
                    checked);
            next.put(id, unit);
            rebuilt.add(id);
        }

        cache.keySet().retainAll(normalized.keySet());
        cache.putAll(next);
        return new BuildResult(
                Map.copyOf(next),
                Set.copyOf(rebuilt),
                Set.copyOf(reused),
                initializationGroups);
    }

    private record LinkedClasses(
            Map<String, Ast.ClassDecl> classes,
            Map<String, Map<String, Ast.TypeAliasDecl>> aliases) { }

    private static LinkedClasses linkedPublicClasses(
            String unitId,
            Ast.Program importer,
            Map<String, Ast.Program> parsed,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Ast.ClassDecl> linked = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, Ast.TypeAliasDecl>> aliases = new LinkedHashMap<>();
        for (Ast.ImportDecl imported : importer.imports()) {
            if (imported.kind() != Ast.ImportKind.CLASS || imported.wildcard()) continue;
            String sourceId = ImportGraph.resolveImportUnitId(
                    unitId, imported, parsed.keySet(), importResolutions);
            if (sourceId == null) continue; // external resolution remains opaque
            Ast.Program source = parsed.get(sourceId);
            if (source == null) {
                throw new IllegalArgumentException("missing linked class source '" + sourceId + "'");
            }
            for (String name : imported.names()) {
                Ast.ClassDecl resolved = null;
                Map<String, Ast.TypeAliasDecl> classAliases = Map.of();
                for (Ast.ModuleDecl module : source.modules()) {
                    for (Ast.Decl declaration : module.declarations()) {
                        if (declaration instanceof Ast.ClassDecl klass
                                && klass.name().equals(name)
                                && klass.visibility() == Ast.Visibility.PUBLIC) {
                            if (resolved != null) {
                                throw new IllegalArgumentException(
                                        "ambiguous linked public class '" + name + "'");
                            }
                            resolved = klass;
                            LinkedHashMap<String, Ast.TypeAliasDecl> moduleAliases = new LinkedHashMap<>();
                            for (Ast.Decl companion : module.declarations()) {
                                if (companion instanceof Ast.TypeAliasDecl alias
                                        && moduleAliases.putIfAbsent(alias.name(), alias) != null) {
                                    throw new IllegalArgumentException(
                                            "duplicate type alias in linked class module: " + alias.name());
                                }
                            }
                            classAliases = Map.copyOf(moduleAliases);
                        }
                    }
                }
                if (resolved == null || linked.putIfAbsent(name, resolved) != null) {
                    throw new IllegalArgumentException(
                            "missing or duplicate public linked class '" + name
                                    + "' in source unit '" + unitId + "'");
                }
                aliases.put(name, classAliases);
            }
        }
        return new LinkedClasses(Map.copyOf(linked), Map.copyOf(aliases));
    }

    public synchronized void clear() {
        cache.clear();
    }

    static String abiDigest(Ast.Program program) {
        // Version bump invalidates older cache entries whose ABI omitted
        // the authorization annotations on exported declarations.
        StringBuilder abi = new StringBuilder("ores-abi-v7-cross-module-inherited-role-contracts\n");
        abi.append("namespace=").append(program.namespace() == null ? "" : program.namespace()).append('\n');

        // Reachable private class dependencies may live in a DIFFERENT
        // module of this source unit than the exported public subclass.
        // Resolve the complete program graph before fingerprinting modules.
        Map<String, List<Ast.ClassDecl>> inheritedByModule =
                exportedPrivateAncestors(program);
        for (Ast.ModuleDecl module : program.modules()) {
            abi.append("module ").append(module.name()).append('\n');
            for (Ast.Annotation annotation : module.annotations()) {
                if (annotation.name().equals("AdheresTo")) {
                    abi.append(" module-annotation AdheresTo:");
                    for (Ast.TypeRef arg : annotation.arguments()) abi.append(typeRef(arg)).append(',');
                    abi.append('\n');
                }
            }
            // A private enum is part of the imported ABI ONLY if an exported
            // callable explicitly references its cases in caller-role metadata.
            // Including every private enum causes needless invalidation and
            // exposes unrelated implementation-only schema changes.
            List<Ast.ClassDecl> inheritedPrivateClasses =
                    inheritedByModule.getOrDefault(module.name(), List.of());
            java.util.Set<String> roleEnums =
                    publicRoleEnumDependencies(module, inheritedPrivateClasses);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.EnumDecl e
                        && e.visibility() != Ast.Visibility.PUBLIC
                        && roleEnums.contains(e.name())) {
                    appendEnumAbi(abi, e);
                } else appendAbi(abi, decl);
            }
            // Private parents can export public inherited methods through a
            // public subclass. Their method signatures and caller-role policy
            // are part of that subclass's effective external source ABI.
            for (Ast.ClassDecl ancestor : inheritedPrivateClasses) {
                abi.append("inherited-private-class ").append(ancestor.name()).append('\n');
                for (Ast.TypeRef parent : ancestor.parents()) {
                    abi.append(" parent ").append(typeRef(parent)).append('\n');
                }
                for (Ast.MethodDecl method : ancestor.methods()) {
                    if (method.visibility() == Ast.Visibility.PUBLIC) {
                        abi.append(classMethodAbi(method));
                    }
                }
            }
        }
        return digest(abi.toString());
    }

    private static void appendAbi(StringBuilder abi, Ast.Decl decl) {
        if (decl instanceof Ast.EnumDecl enumDecl) {
            if (enumDecl.visibility() == Ast.Visibility.PUBLIC) appendEnumAbi(abi, enumDecl);
            return;
        }
        if (decl instanceof Ast.FunctionDecl fn) {
            if (fn.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(fn.actorKind()).append(' ');
            if (fn.async()) abi.append("async ");
            if (fn.generator()) abi.append("generator ");
            if (fn.structural()) abi.append("structural ");
            abi.append(fn.kind()).append(" pub ").append(fn.name());
            appendGenericSpecs(abi, fn.genericSpecs());
            appendParams(abi, fn.parameters());
            abi.append("=>").append(typeRef(fn.returnType()));
            appendCallerRoleAbi(abi, fn.annotations());
            abi.append('\n');
            return;
        }
        if (decl instanceof Ast.ClassDecl klass) {
            if (klass.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(klass.actorKind()).append(" pub class ").append(klass.name());
            appendGenerics(abi, klass.genericParameters());
            abi.append(" extends ");
            for (Ast.TypeRef parent : klass.parents()) abi.append(typeRef(parent)).append(',');
            abi.append(" implements ");
            for (Ast.TypeRef iface : klass.interfaces()) abi.append(typeRef(iface)).append(',');
            abi.append('\n');
            if (klass.constructor() != null
                    && klass.constructor().visibility() == Ast.Visibility.PUBLIC) {
                abi.append(" constructor");
                appendParams(abi, klass.constructor().parameters());
                abi.append('\n');
            } else if (klass.constructor() == null) {
                // Legacy synthesized constructors are externally visible when
                // their class is public; all positional field slots affect ABI.
                abi.append(" synthesized-constructor(");
                for (Ast.FieldDecl field : klass.fields()) {
                    abi.append(field.type() == null
                            ? "<inferred:" + field.initializer() + ">"
                            : typeRef(field.type())).append(',');
                }
                abi.append(")\n");
            }
            for (Ast.FieldDecl field : klass.fields()) {
                String fromJsonKey = AnnotationExpander.fromJsonKey(field);
                if (fromJsonKey != null) {
                    abi.append(" from-json ")
                            .append(field.name()).append('=')
                            .append(fromJsonKey.length()).append(':').append(fromJsonKey)
                            .append(':').append(field.type() == null ? "<inferred>" : typeRef(field.type())).append('\n');
                }
                if (field.visibility() != Ast.Visibility.PUBLIC) continue;
                abi.append(" field ").append(field.bindingKind()).append(' ')
                        .append(field.type() == null ? "<inferred:" + field.initializer() + ">" : typeRef(field.type()))
                        .append(' ').append(field.name()).append('\n');
            }
            List<String> methodEntries = new ArrayList<>();
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                methodEntries.add(classMethodAbi(method));
            }
            methodEntries.stream().sorted().forEach(abi::append);
            return;
        }
        if (decl instanceof Ast.InterfaceDecl iface) {
            // Private contracts/traits/interfaces are not part of the imported ABI.
            if (iface.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append("interface ").append(iface.visibility()).append(' ').append(iface.name());
            appendGenerics(abi, iface.genericParameters());
            abi.append(" extends ");
            for (Ast.TypeRef parent : iface.parents()) abi.append(typeRef(parent)).append(',');
            abi.append('\n');
            List<String> memberEntries = new ArrayList<>();
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    memberEntries.add(interfaceFunctionAbi(fn));
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    memberEntries.add(" iface-field " + field.name() + ":" + typeRef(field.type()) + "\n");
                }
            }
            memberEntries.stream().sorted().forEach(abi::append);
            return;
        }
        if (decl instanceof Ast.TypeAliasDecl alias) {
            if (alias.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append("pub type ").append(alias.name());
            appendGenerics(abi, alias.genericParameters());
            abi.append('=').append(typeRef(alias.target())).append('\n');
            return;
        }
        if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
            abi.append("binding ").append(field.bindingKind()).append(' ')
                    .append(field.type() == null ? "<inferred:" + field.initializer() + ">" : typeRef(field.type()))
                    .append(' ').append(field.name()).append('\n');
        }
    }

    private static void appendEnumAbi(StringBuilder abi, Ast.EnumDecl declaration) {
        abi.append(declaration.visibility()).append(" enum ").append(declaration.name()).append('\n');
        for (Ast.EnumCase variant : declaration.cases()) {
            String name = variant.name();
            String payload = variant.payload() == null
                    ? "<nullary>" : typeRef(variant.payload());
            abi.append(" enum-case ").append(name.length()).append(':')
                    .append(name).append('#').append(variant.tag())
                    .append('(').append(payload.length()).append(':')
                    .append(payload).append(")\n");
        }
    }

    /**
     * Private class dependencies reachable from ANY public class within the
     * same source program. Class owners are nominal module-qualified names.
     * Without a global scan, a public Child in module B extending module
     * A's private Base misses role-policy changes to A.Base in the exported ABI.
     */
    private static Map<String, List<Ast.ClassDecl>> exportedPrivateAncestors(
            Ast.Program program) {
        Map<String, Ast.ClassDecl> classes = new LinkedHashMap<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ClassDecl klass)) continue;
                String qualified = module.name() + "." + klass.name();
                classes.putIfAbsent(qualified, klass);
                if (klass.visibility() == Ast.Visibility.PUBLIC) {
                    pending.addLast(qualified);
                }
            }
        }

        Map<String, List<Ast.ClassDecl>> inherited = new LinkedHashMap<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            Ast.ClassDecl klass = classes.get(current);
            if (klass == null) continue;
            String ownerModule = current.substring(0, current.lastIndexOf('.'));
            if (klass.visibility() != Ast.Visibility.PUBLIC) {
                inherited.computeIfAbsent(ownerModule, ignored -> new ArrayList<>())
                        .add(klass);
            }
            for (Ast.TypeRef parent : klass.parents()) {
                String rawName = parent.name();
                if (rawName == null) continue;
                String qualifiedParent = rawName.contains(".")
                        ? rawName : ownerModule + "." + rawName;
                if (classes.containsKey(qualifiedParent)) {
                    pending.addLast(qualifiedParent);
                }
                // External ancestors still require the linker's fail-closed
                // authenticated inheritance policy; the local ABI alone is
                // not evidence that an external source is safe to import.
            }
        }
        Map<String, List<Ast.ClassDecl>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Ast.ClassDecl>> entry : inherited.entrySet()) {
            entry.getValue().sort(
                    java.util.Comparator.comparing(Ast.ClassDecl::name));
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    /** Exact local enum dependencies of exported caller-role annotations. */
    private static java.util.Set<String> publicRoleEnumDependencies(
            Ast.ModuleDecl module, List<Ast.ClassDecl> inheritedPrivateClasses) {
        java.util.Set<String> localEnumNames = new java.util.HashSet<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.EnumDecl e) localEnumNames.add(e.name());
        }
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                addRoleEnumDependencies(referenced, fn.annotations(), module.name(), localEnumNames);
            } else if (decl instanceof Ast.ClassDecl klass
                    && klass.visibility() == Ast.Visibility.PUBLIC) {
                for (Ast.MethodDecl method : klass.methods()) {
                    if (method.visibility() == Ast.Visibility.PUBLIC) {
                        addRoleEnumDependencies(
                                referenced, method.annotations(), module.name(), localEnumNames);
                    }
                }
            }
        }
        for (Ast.ClassDecl ancestor : inheritedPrivateClasses) {
            for (Ast.MethodDecl method : ancestor.methods()) {
                if (method.visibility() == Ast.Visibility.PUBLIC) {
                    addRoleEnumDependencies(
                            referenced, method.annotations(), module.name(), localEnumNames);
                }
            }
        }
        return java.util.Set.copyOf(referenced);
    }

    private static void addRoleEnumDependencies(
            java.util.Set<String> referenced, List<Ast.Annotation> annotations,
            String moduleName, java.util.Set<String> localEnumNames) {
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("CallerRole")
                    && !annotation.name().equals("CallableBy")) continue;
            for (Ast.TypeRef argument : annotation.arguments()) {
                if (argument == null) continue; // Bad metadata is rejected by the type checker.
                String role = argument.name();
                if (role == null) continue;
                int lastDot = role.lastIndexOf('.');
                if (lastDot <= 0) continue;
                String owner = role.substring(0, lastDot);
                String localName = owner.startsWith(moduleName + ".")
                        ? owner.substring(moduleName.length() + 1) : owner;
                if (localEnumNames.contains(localName)
                        && (owner.equals(localName)
                            || owner.equals(moduleName + "." + localName))) {
                    referenced.add(localName);
                }
            }
        }
    }

    private static String classMethodAbi(Ast.MethodDecl method) {
        StringBuilder out = new StringBuilder();
        if (method.async()) out.append("async ");
        if (method.structural()) out.append("structural ");
        out.append(method.isStatic() ? " static-fnc " : " method ")
                .append(method.name())
                .append("#arity").append(method.arity());
        appendGenerics(out, method.genericParameters());
        appendParams(out, method.parameters());
        out.append("=>").append(typeRef(method.returnType()));
        appendCallerRoleAbi(out, method.annotations());
        out.append('\n');
        return out.toString();
    }

    /**
     * Callable annotations affect which clients are allowed to call/export
     * a symbol and therefore participate in public incremental ABI hashes.
     * Treat these values as ABI *fingerprints*, not validated import proof:
     * the linked declaration and its roles still require trusted resolution.
     */
    private static void appendCallerRoleAbi(StringBuilder out, List<Ast.Annotation> annotations) {
        for (String annotationName : List.of("CallableBy", "CallerRole")) {
            for (Ast.Annotation annotation : annotations) {
                if (!annotation.name().equals(annotationName)) continue;
                List<String> roles = annotation.arguments().stream()
                        .map(IncrementalCompiler::typeRef).sorted().toList();
                out.append(" @").append(annotationName).append('(');
                for (String role : roles) {
                    out.append(role.length()).append(':').append(role).append(',');
                }
                out.append(')');
            }
        }
    }

    private static String interfaceFunctionAbi(Ast.InterfaceFunctionDecl fn) {
        StringBuilder out = new StringBuilder();
        if (fn.structural()) out.append("structural ");
        out.append(" iface-fnc ").append(fn.name())
                .append("#arity").append(fn.parameters().size());
        appendGenerics(out, fn.genericParameters());
        appendParams(out, fn.parameters());
        out.append("=>").append(typeRef(fn.returnType())).append('\n');
        return out.toString();
    }

    /** Generic defaults are public source-ABI, even when execution is erased. */
    private static void appendGenericSpecs(
            StringBuilder abi, List<Ast.GenericParameter> specs) {
        abi.append('<');
        for (Ast.GenericParameter spec : specs) {
            abi.append(spec.name());
            if (spec.defaultType() != null) {
                abi.append('=').append(typeRef(spec.defaultType()));
            }
            abi.append(',');
        }
        abi.append('>');
    }

    private static void appendGenerics(StringBuilder abi, List<String> generics) {
        abi.append('<');
        for (String generic : generics) abi.append(generic).append(',');
        abi.append('>');
    }

    private static void appendParams(StringBuilder abi, List<Ast.Param> params) {
        abi.append('(');
        for (Ast.Param param : params) {
            if (param.structural()) abi.append("structural ");
            abi.append(typeRef(param.type()));
            if (param.mutable()) abi.append(" mut");
            abi.append(',');
        }
        abi.append(')');
    }

    private static String typeRef(Ast.TypeRef ref) {
        if (ref == null) return "<inferred>";
        if (ref.isStringLiteral()) return "'" + ref.stringLiteralValue() + "'";
        StringBuilder out = new StringBuilder(ref.name());
        if (ref.inferArguments()) return out.append("<>").toString();
        if (!ref.arguments().isEmpty()) {
            out.append('<');
            for (Ast.TypeRef arg : ref.arguments()) out.append(typeRef(arg)).append(',');
            out.append('>');
        }
        return out.toString();
    }

    private static Set<String> resolveDependencies(String unitId, Ast.Program program, Set<String> available) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Path parent = Path.of(unitId).getParent();
        for (Ast.ImportDecl imported : program.imports()) {
            String raw = imported.path().replace('\\', '/');
            Path candidatePath = raw.startsWith(".")
                    ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                    : Path.of(raw).normalize();
            String candidate = normalizeUnitId(candidatePath.toString());
            if (!available.contains(candidate) && !candidate.endsWith(".ores") && available.contains(candidate + ".ores")) {
                candidate += ".ores";
            }
            if (available.contains(candidate)) {
                result.add(candidate);
            } else if (raw.startsWith(".")) {
                throw new IllegalArgumentException("relative import '" + imported.path() + "' from '" + unitId
                        + "' does not resolve to a supplied Oreslang source unit");
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Map<String, String>> normalizeImportResolutions(
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Map<String, String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> importer : importResolutions.entrySet()) {
            String importerId = normalizeUnitId(importer.getKey());
            if (importer.getValue() == null) {
                throw new IllegalArgumentException("import resolution map cannot be null for '" + importerId + "'");
            }
            LinkedHashMap<String, String> imports = new LinkedHashMap<>();
            for (Map.Entry<String, String> resolution : importer.getValue().entrySet()) {
                if (resolution.getKey() == null || resolution.getKey().isBlank()) {
                    throw new IllegalArgumentException("resolved import path cannot be blank");
                }
                String targetId = normalizeUnitId(resolution.getValue());
                String previous = imports.putIfAbsent(resolution.getKey(), targetId);
                if (previous != null && !previous.equals(targetId)) {
                    throw new IllegalArgumentException(
                            "conflicting resolved import '" + resolution.getKey() + "' for '" + importerId + "'");
                }
            }
            normalized.put(importerId, Map.copyOf(imports));
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Set<String>> reverseDependencies(Map<String, Set<String>> dependencies) {
        LinkedHashMap<String, Set<String>> reverse = new LinkedHashMap<>();
        for (String id : dependencies.keySet()) reverse.put(id, new LinkedHashSet<>());
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                reverse.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        LinkedHashMap<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : reverse.entrySet()) frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        return Map.copyOf(frozen);
    }

    private static String packageId(String unitId, Ast.Program program) {
        if (program.namespace() != null) return program.namespace();
        String id = unitId;
        if (id.endsWith(".ores")) id = id.substring(0, id.length() - ".ores".length());
        return id;
    }

    private static String normalizeUnitId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("source unit id cannot be blank");
        return Path.of(id).normalize().toString().replace('\\', '/');
    }

    private static String digest(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record CompiledUnit(
            String unitId,
            String packageId,
            String namespace,
            String sourceDigest,
            String abiDigest,
            Set<String> dependencies,
            String sourceText,
            Ast.Program program) {
        public CompiledUnit {
            dependencies = Set.copyOf(dependencies);
        }
    }

    public record BuildResult(
            Map<String, CompiledUnit> units,
            Set<String> rebuiltUnits,
            Set<String> reusedUnits,
            List<List<String>> initializationGroups) {
        public BuildResult {
            units = Map.copyOf(units);
            rebuiltUnits = Set.copyOf(rebuiltUnits);
            reusedUnits = Set.copyOf(reusedUnits);
            initializationGroups = initializationGroups.stream()
                    .map(List::copyOf)
                    .toList();
        }

        /** Backward-compatible constructor for callers that do not need lifecycle planning. */
        public BuildResult(
                Map<String, CompiledUnit> units,
                Set<String> rebuiltUnits,
                Set<String> reusedUnits) {
            this(units, rebuiltUnits, reusedUnits, List.of());
        }

        public boolean rebuilt(String unitId) { return rebuiltUnits.contains(normalizeUnitId(unitId)); }
        public boolean reused(String unitId) { return reusedUnits.contains(normalizeUnitId(unitId)); }

        /**
         * Flattens the dependency-first SCC plan. Units in the same inner list
         * form one load barrier: all of them must be linked before the first
         * init hook in that group executes.
         */
        public List<String> initializationOrder() {
            return initializationGroups.stream().flatMap(List::stream).toList();
        }
    }
}
