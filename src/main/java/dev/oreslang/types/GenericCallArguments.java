package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical declaration-slot binding for call-site generic type arguments.
 *
 * <p>Generic names are source-level slot identities. Positional arguments
 * occupy slots from the beginning; named arguments select unoccupied slots.
 * A bare underscore requires type inference and never supplies a type or
 * triggers a default. Neither the type checker nor ownership analysis may
 * reimplement these rules independently.
 */
final class GenericCallArguments {
    private GenericCallArguments() { }

    record Bound(Map<String, Ast.TypeRef> explicitTypes, Set<String> inferenceHoles) {
        Bound {
            explicitTypes = Map.copyOf(explicitTypes);
            inferenceHoles = Set.copyOf(inferenceHoles);
        }
    }

    static Bound bind(List<String> parameters, List<Ast.TypeRef> arguments, String label) {
        if (arguments.size() > parameters.size()) {
            throw new IllegalArgumentException(label + " expects at most " + parameters.size()
                    + " explicit type argument(s), got " + arguments.size());
        }
        if (new HashSet<>(parameters).size() != parameters.size()) {
            throw new IllegalArgumentException(label + " has duplicate generic parameter declarations");
        }
        Map<String, Ast.TypeRef> types = new LinkedHashMap<>();
        Set<String> inferenceHoles = new LinkedHashSet<>();
        Set<String> assigned = new HashSet<>();
        boolean seenNamed = false;
        int positionalIndex = 0;
        for (Ast.TypeRef argument : arguments) {
            String name;
            Ast.TypeRef value;
            if (argument.isNamedParameter()) {
                seenNamed = true;
                name = argument.namedParameterName();
                value = argument.namedParameterValue();
                if (!parameters.contains(name)) {
                    throw new IllegalArgumentException(label + " has no generic parameter named '" + name + "'");
                }
            } else {
                if (seenNamed) {
                    throw new IllegalArgumentException(label
                            + ": positional generic arguments must precede named arguments");
                }
                name = parameters.get(positionalIndex++);
                value = argument;
            }
            if (!assigned.add(name)) {
                throw new IllegalArgumentException(label + ": duplicate generic argument '" + name + "'");
            }
            if (value.name().equals("_") && value.arguments().isEmpty() && !value.inferArguments()) {
                inferenceHoles.add(name);
            } else {
                types.put(name, value);
            }
        }
        return new Bound(types, inferenceHoles);
    }

    static List<String> canonicalStructuralNames(int arity) {
        List<String> names = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) names.add("$callable" + i);
        return List.copyOf(names);
    }
}
