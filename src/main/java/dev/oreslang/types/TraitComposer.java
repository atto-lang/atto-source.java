package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time stateful-trait composition.
 *
 * Traits have no runtime object identity. This pass expands trait state,
 * behavior, and interface obligations into the consuming class before the
 * ordinary type/ownership/capability/runtime passes execute.
 */
public final class TraitComposer {
    private TraitComposer() { }

    public static Ast.Program compose(Ast.Program program) {
        return new Composer(program).compose();
    }

    private record TraitBinding(String module, Ast.TraitDecl declaration) { }
    private record FieldEntry(Ast.FieldDecl field, String origin) { }
    private record MethodEntry(Ast.MethodDecl method, String origin) { }

    private static final class Material {
        private final LinkedHashMap<String, FieldEntry> fields = new LinkedHashMap<>();
        private final LinkedHashMap<String, MethodEntry> methods = new LinkedHashMap<>();
        private final LinkedHashMap<String, Ast.TypeRef> interfaces = new LinkedHashMap<>();
    }

    private static final class Composer {
        private final Ast.Program program;
        private final Map<String, TraitBinding> qualifiedTraits = new LinkedHashMap<>();
        private final Map<String, TraitBinding> unqualifiedTraits = new LinkedHashMap<>();
        private final Set<String> ambiguousTraits = new HashSet<>();
        private final Map<String, Material> materialCache = new HashMap<>();

        private Composer(Ast.Program program) {
            this.program = program;
            indexTraits();
        }

        private Ast.Program compose() {
            List<Ast.ModuleDecl> modules = new ArrayList<>();
            for (Ast.ModuleDecl module : program.modules()) {
                List<Ast.Decl> declarations = new ArrayList<>();
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.TraitDecl) {
                        continue;
                    }
                    if (declaration instanceof Ast.ClassDecl klass) {
                        declarations.add(composeClass(module.name(), klass));
                    } else {
                        declarations.add(declaration);
                    }
                }
                modules.add(new Ast.ModuleDecl(
                        module.name(),
                        module.singleton(),
                        module.annotations(),
                        declarations));
            }
            return new Ast.Program(program.namespace(), program.imports(), modules);
        }

        private void indexTraits() {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (!(declaration instanceof Ast.TraitDecl trait)) continue;

                    TraitBinding binding = new TraitBinding(module.name(), trait);
                    String qualified = module.name() + "." + trait.name();
                    if (qualifiedTraits.putIfAbsent(qualified, binding) != null) {
                        throw new IllegalArgumentException("duplicate trait '" + qualified + "'");
                    }

                    TraitBinding previous = unqualifiedTraits.putIfAbsent(trait.name(), binding);
                    if (previous != null && previous != binding) {
                        ambiguousTraits.add(trait.name());
                        unqualifiedTraits.remove(trait.name());
                    }
                }
            }
        }

        private Ast.ClassDecl composeClass(String moduleName, Ast.ClassDecl klass) {
            if (klass.traits().isEmpty()) return klass;

            LinkedHashMap<String, FieldEntry> traitFields = new LinkedHashMap<>();
            LinkedHashMap<String, List<MethodEntry>> traitMethods = new LinkedHashMap<>();
            LinkedHashMap<String, Ast.TypeRef> interfaces = new LinkedHashMap<>();
            for (Ast.TypeRef iface : klass.interfaces()) {
                mergeInterface(interfaces, iface, "class " + klass.name());
            }

            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            for (Ast.TypeRef traitRef : klass.traits()) {
                Material material = materialize(moduleName, traitRef, classGenerics, new ArrayDeque<>());

                for (FieldEntry field : material.fields.values()) {
                    FieldEntry previous = traitFields.putIfAbsent(field.field().name(), field);
                    if (previous != null && !previous.origin().equals(field.origin())) {
                        throw new IllegalArgumentException(
                                "trait state collision for field '" + field.field().name()
                                        + "' in class '" + klass.name() + "': "
                                        + previous.origin() + " vs " + field.origin());
                    }
                }

                for (Map.Entry<String, MethodEntry> method : material.methods.entrySet()) {
                    traitMethods.computeIfAbsent(method.getKey(), ignored -> new ArrayList<>())
                            .add(method.getValue());
                }

                for (Ast.TypeRef iface : material.interfaces.values()) {
                    mergeInterface(interfaces, iface, "trait composition of " + klass.name());
                }
            }

            for (Ast.FieldDecl field : klass.fields()) {
                if (traitFields.containsKey(field.name())) {
                    throw new IllegalArgumentException(
                            "class '" + klass.name() + "' field '" + field.name()
                                    + "' collides with composed trait state; trait storage cannot be shadowed");
                }
            }

            LinkedHashMap<String, Ast.MethodDecl> localMethods = new LinkedHashMap<>();
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic()) localMethods.put(methodKey(method), method);
            }

            List<Ast.MethodDecl> composedMethods = new ArrayList<>();
            for (Map.Entry<String, List<MethodEntry>> entry : traitMethods.entrySet()) {
                String key = entry.getKey();
                List<MethodEntry> candidates = dedupeOrigins(entry.getValue());
                Ast.MethodDecl local = localMethods.get(key);

                if (local != null) {
                    for (MethodEntry candidate : candidates) {
                        requireCompatible(
                                local,
                                candidate.method(),
                                "class override " + klass.name() + "." + key);
                    }
                    continue;
                }

                Ast.MethodDecl selected = selectTraitMethod(klass, key, candidates);
                if (selected != null) composedMethods.add(selected);
            }

            List<Ast.FieldDecl> fields = new ArrayList<>(traitFields.size() + klass.fields().size());
            for (FieldEntry entry : traitFields.values()) fields.add(entry.field());
            fields.addAll(klass.fields());

            List<Ast.MethodDecl> methods = new ArrayList<>(composedMethods.size() + klass.methods().size());
            methods.addAll(composedMethods);
            methods.addAll(klass.methods());

            return new Ast.ClassDecl(
                    klass.name(),
                    klass.isAbstract(),
                    klass.genericParameters(),
                    klass.parents(),
                    List.copyOf(interfaces.values()),
                    List.of(),
                    fields,
                    methods);
        }

        private Ast.MethodDecl selectTraitMethod(
                Ast.ClassDecl klass,
                String key,
                List<MethodEntry> candidates) {
            if (candidates.isEmpty()) return null;

            Ast.MethodDecl baseline = candidates.getFirst().method();
            for (MethodEntry candidate : candidates) {
                requireCompatible(
                        baseline,
                        candidate.method(),
                        "trait method " + klass.name() + "." + key);
            }

            List<MethodEntry> concrete = candidates.stream()
                    .filter(entry -> !entry.method().isAbstract())
                    .toList();

            if (concrete.size() > 1) {
                throw new IllegalArgumentException(
                        "ambiguous trait method '" + key + "' in class '" + klass.name()
                                + "' from " + concrete.stream().map(MethodEntry::origin).toList()
                                + "; declare the method on the class to resolve the conflict");
            }
            if (concrete.size() == 1) return concrete.getFirst().method();

            if (!klass.isAbstract()) {
                throw new IllegalArgumentException(
                        "concrete class '" + klass.name()
                                + "' must implement trait requirement '" + key + "'");
            }
            return candidates.getFirst().method();
        }

        private Material materialize(
                String hostModule,
                Ast.TypeRef traitRef,
                Set<String> hostGenerics,
                ArrayDeque<String> stack) {
            TraitBinding binding = resolveTrait(hostModule, traitRef.name());

            // Method bodies retain their lexical module environment after
            // flattening. Restrict v0 composition to the declaring module so
            // that this transformation cannot silently change lexical lookup.
            if (!binding.module().equals(hostModule)) {
                throw new IllegalArgumentException(
                        "trait '" + traitRef.name() + "' is declared in module '"
                                + binding.module()
                                + "' but v0 trait composition is module-local");
            }

            Map<String, Ast.TypeRef> substitutions =
                    bindGenerics(binding.declaration(), traitRef, hostGenerics);
            String applicationKey = applicationKey(binding, substitutions);

            Material cached = materialCache.get(applicationKey);
            if (cached != null) return cached;
            if (stack.contains(applicationKey)) {
                throw new IllegalArgumentException(
                        "trait composition cycle: " + stack + " -> " + applicationKey);
            }
            stack.addLast(applicationKey);

            Material material = new Material();
            LinkedHashMap<String, List<MethodEntry>> inheritedMethods = new LinkedHashMap<>();

            for (Ast.TypeRef nestedRaw : binding.declaration().traits()) {
                Ast.TypeRef nested = substitute(nestedRaw, substitutions);
                Material nestedMaterial =
                        materialize(hostModule, nested, hostGenerics, stack);

                for (FieldEntry field : nestedMaterial.fields.values()) {
                    mergeField(material, field);
                }
                for (Map.Entry<String, MethodEntry> method : nestedMaterial.methods.entrySet()) {
                    inheritedMethods
                            .computeIfAbsent(method.getKey(), ignored -> new ArrayList<>())
                            .add(method.getValue());
                }
                for (Ast.TypeRef iface : nestedMaterial.interfaces.values()) {
                    mergeInterface(
                            material.interfaces,
                            iface,
                            "trait " + binding.declaration().name());
                }
            }

            for (Ast.TypeRef ifaceRaw : binding.declaration().interfaces()) {
                mergeInterface(
                        material.interfaces,
                        substitute(ifaceRaw, substitutions),
                        "trait " + binding.declaration().name());
            }

            for (Ast.FieldDecl raw : binding.declaration().fields()) {
                if (raw.initializer() == null) {
                    throw new IllegalArgumentException(
                            "trait state field '" + binding.declaration().name() + "."
                                    + raw.name()
                                    + "' requires an initializer because trait state is not "
                                    + "a host-class constructor parameter");
                }

                Ast.FieldDecl field = new Ast.FieldDecl(
                        raw.name(),
                        raw.visibility(),
                        raw.bindingKind(),
                        substitute(raw.type(), substitutions),
                        raw.initializer(),
                        binding.declaration().name());

                mergeField(
                        material,
                        new FieldEntry(
                                field,
                                applicationKey + "::" + raw.name()));
            }

            LinkedHashMap<String, Ast.MethodDecl> ownMethods = new LinkedHashMap<>();
            for (Ast.MethodDecl raw : binding.declaration().methods()) {
                Ast.MethodDecl method = substitute(raw, substitutions);
                String key = methodKey(method);
                Ast.MethodDecl previous = ownMethods.putIfAbsent(key, method);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate trait method '"
                                    + binding.declaration().name() + "." + key + "'");
                }
            }

            Set<String> methodKeys = new LinkedHashSet<>();
            methodKeys.addAll(inheritedMethods.keySet());
            methodKeys.addAll(ownMethods.keySet());

            for (String key : methodKeys) {
                Ast.MethodDecl override = ownMethods.get(key);
                List<MethodEntry> inherited =
                        dedupeOrigins(inheritedMethods.getOrDefault(key, List.of()));

                if (override != null) {
                    for (MethodEntry candidate : inherited) {
                        requireCompatible(
                                override,
                                candidate.method(),
                                "trait override "
                                        + binding.declaration().name() + "." + key);
                    }
                    material.methods.put(
                            key,
                            new MethodEntry(
                                    override,
                                    applicationKey + "::" + key));
                    continue;
                }

                if (inherited.isEmpty()) continue;

                Ast.MethodDecl baseline = inherited.getFirst().method();
                for (MethodEntry candidate : inherited) {
                    requireCompatible(
                            baseline,
                            candidate.method(),
                            "nested trait method "
                                    + binding.declaration().name() + "." + key);
                }

                List<MethodEntry> concrete = inherited.stream()
                        .filter(entry -> !entry.method().isAbstract())
                        .toList();
                if (concrete.size() > 1) {
                    throw new IllegalArgumentException(
                            "trait '" + binding.declaration().name()
                                    + "' inherits ambiguous concrete method '" + key
                                    + "'; override it in the trait to resolve the conflict");
                }

                MethodEntry selected =
                        concrete.isEmpty() ? inherited.getFirst() : concrete.getFirst();
                material.methods.put(key, selected);
            }

            stack.removeLast();
            materialCache.put(applicationKey, material);
            return material;
        }

        private void mergeField(Material material, FieldEntry incoming) {
            FieldEntry previous =
                    material.fields.putIfAbsent(incoming.field().name(), incoming);
            if (previous != null && !previous.origin().equals(incoming.origin())) {
                throw new IllegalArgumentException(
                        "trait state collision for field '" + incoming.field().name()
                                + "': " + previous.origin() + " vs " + incoming.origin());
            }
        }

        private void mergeInterface(
                Map<String, Ast.TypeRef> interfaces,
                Ast.TypeRef incoming,
                String owner) {
            Ast.TypeRef previous = interfaces.putIfAbsent(incoming.name(), incoming);
            if (previous != null && !previous.equals(incoming)) {
                throw new IllegalArgumentException(
                        "conflicting interface instantiations for '" + incoming.name()
                                + "' in " + owner + ": " + previous + " vs " + incoming);
            }
        }

        private TraitBinding resolveTrait(String hostModule, String name) {
            TraitBinding local = qualifiedTraits.get(hostModule + "." + name);
            if (local != null) return local;

            if (name.contains(".")) {
                TraitBinding exact = qualifiedTraits.get(name);
                if (exact != null) return exact;
            }

            if (ambiguousTraits.contains(name)) {
                throw new IllegalArgumentException(
                        "ambiguous trait '" + name + "'; qualify it with its module");
            }

            TraitBinding found = unqualifiedTraits.get(name);
            if (found == null) {
                throw new IllegalArgumentException("unknown trait '" + name + "'");
            }
            return found;
        }

        private Map<String, Ast.TypeRef> bindGenerics(
                Ast.TraitDecl trait,
                Ast.TypeRef reference,
                Set<String> hostGenerics) {
            List<String> parameters = trait.genericParameters();
            List<Ast.TypeRef> arguments = reference.arguments();

            if (parameters.isEmpty()) {
                if (!arguments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "trait '" + trait.name() + "' is not generic");
                }
                return Map.of();
            }

            List<Ast.TypeRef> bound = arguments;
            if (reference.inferArguments()) {
                ArrayList<Ast.TypeRef> inferred = new ArrayList<>();
                for (String parameter : parameters) {
                    if (!hostGenerics.contains(parameter)) {
                        throw new IllegalArgumentException(
                                "cannot infer trait generic '" + parameter
                                        + "' for " + trait.name()
                                        + "; use explicit type arguments");
                    }
                    inferred.add(Ast.TypeRef.simple(parameter));
                }
                bound = inferred;
            }

            if (bound.size() != parameters.size()) {
                throw new IllegalArgumentException(
                        "trait '" + trait.name() + "' expects "
                                + parameters.size()
                                + " type arguments but got " + bound.size());
            }

            LinkedHashMap<String, Ast.TypeRef> result = new LinkedHashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                result.put(parameters.get(i), bound.get(i));
            }
            return result;
        }

        private String applicationKey(
                TraitBinding binding,
                Map<String, Ast.TypeRef> substitutions) {
            StringBuilder key =
                    new StringBuilder(binding.module())
                            .append('.')
                            .append(binding.declaration().name())
                            .append('<');
            for (String parameter : binding.declaration().genericParameters()) {
                key.append(parameter)
                        .append('=')
                        .append(substitutions.get(parameter))
                        .append(';');
            }
            return key.append('>').toString();
        }

        private Ast.TypeRef substitute(
                Ast.TypeRef type,
                Map<String, Ast.TypeRef> substitutions) {
            if (type == null) return null;

            Ast.TypeRef direct = substitutions.get(type.name());
            if (direct != null && type.arguments().isEmpty()) return direct;

            return new Ast.TypeRef(
                    type.name(),
                    type.arguments().stream()
                            .map(argument -> substitute(argument, substitutions))
                            .toList(),
                    type.inferArguments());
        }

        private Ast.MethodDecl substitute(
                Ast.MethodDecl method,
                Map<String, Ast.TypeRef> substitutions) {
            Map<String, Ast.TypeRef> effective =
                    new LinkedHashMap<>(substitutions);
            for (String methodGeneric : method.genericParameters()) {
                effective.remove(methodGeneric);
            }

            List<Ast.Param> parameters = method.parameters().stream()
                    .map(parameter -> new Ast.Param(
                            substitute(parameter.type(), effective),
                            parameter.name(),
                            parameter.structural(),
                            parameter.mutable()))
                    .toList();

            List<Ast.Annotation> annotations = method.annotations().stream()
                    .map(annotation -> new Ast.Annotation(
                            annotation.name(),
                            annotation.arguments().stream()
                                    .map(argument -> substitute(argument, effective))
                                    .toList()))
                    .toList();

            return new Ast.MethodDecl(
                    method.name(),
                    method.visibility(),
                    method.isStatic(),
                    method.isAbstract(),
                    method.async(),
                    substitute(method.explicitReceiverType(), effective),
                    method.genericParameters(),
                    parameters,
                    substitute(method.returnType(), effective),
                    annotations,
                    method.body());
        }

        private List<MethodEntry> dedupeOrigins(List<MethodEntry> entries) {
            LinkedHashMap<String, MethodEntry> result = new LinkedHashMap<>();
            for (MethodEntry entry : entries) {
                result.putIfAbsent(entry.origin(), entry);
            }
            return List.copyOf(result.values());
        }

        private String methodKey(Ast.MethodDecl method) {
            return method.name() + "/" + method.arity();
        }

        private void requireCompatible(
                Ast.MethodDecl left,
                Ast.MethodDecl right,
                String where) {
            if (!sameContract(left, right)) {
                throw new IllegalArgumentException(
                        "incompatible trait method contracts in " + where
                                + ": " + describe(left) + " vs " + describe(right));
            }
        }

        private boolean sameContract(
                Ast.MethodDecl left,
                Ast.MethodDecl right) {
            if (left.async() != right.async()) return false;
            if (!left.genericParameters().equals(right.genericParameters())) return false;
            if (!java.util.Objects.equals(
                    left.explicitReceiverType(),
                    right.explicitReceiverType())) return false;
            if (!left.returnType().equals(right.returnType())) return false;
            if (left.parameters().size() != right.parameters().size()) return false;

            for (int i = 0; i < left.parameters().size(); i++) {
                Ast.Param a = left.parameters().get(i);
                Ast.Param b = right.parameters().get(i);
                if (!a.type().equals(b.type())
                        || a.structural() != b.structural()
                        || a.mutable() != b.mutable()) return false;
            }
            return true;
        }

        private String describe(Ast.MethodDecl method) {
            return method.name()
                    + method.parameters().stream()
                            .map(parameter -> parameter.type().toString())
                            .toList()
                    + " => " + method.returnType();
        }
    }
}
