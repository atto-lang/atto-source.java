package dev.oreslang.types;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ReceiverNamespaceGateSmoke {
    private static int assertions;

    private static void pass(String namespace, String receiver, Map<String, Set<String>> owners) {
        ReceiverNamespaceGate.requireLocalOwner(namespace, receiver, "big_ly", owners);
        assertions++;
    }

    private static void reject(String namespace, String receiver, Map<String, Set<String>> owners,
                               String expected) {
        try {
            ReceiverNamespaceGate.requireLocalOwner(namespace, receiver, "big_ly", owners);
            throw new AssertionError("accepted forbidden receiver: " + namespace + "." + receiver);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains(expected)) throw new AssertionError(e);
            assertions++;
        }
    }

    public static void main(String[] args) {
        Map<String, Set<String>> nominal = new LinkedHashMap<>();
        nominal.put("Worker1", Set.of("foo"));
        nominal.put("Worker2", Set.of("bar"));
        nominal.put("Widget", Set.of("foo"));
        pass("foo", "Worker1", nominal);
        pass("bar", "Worker2", nominal);
        reject("bar", "Worker1", nominal, "EXTERNAL_RECEIVER_NAMESPACE_MISMATCH");
        reject("bar", "Widget", nominal, "[foo]");
        pass("bar", "Missing", nominal); // unknown type still rejected by nominal binder
        nominal.put("Worker1", Set.of("foo", "bar"));
        pass("bar", "Worker1", nominal); // local nominal wins despite foreign homonym
        nominal.put("Alias", Set.of("bar"));
        pass("bar", "Alias", nominal); // local type alias is not a remote actor
        Map<String, Set<String>> reversed = new LinkedHashMap<>();
        reversed.put("Worker1", new LinkedHashSet<>(java.util.List.of("zeta", "foo")));
        reject("bar", "Worker1", reversed, "[foo, zeta]");
        reject("", "Worker1", nominal, "invalid out-of-line receiver declaration");
        System.out.println("RECEIVER_NAMESPACE_GUARD_PASS assertions=" + assertions
                + " scope=isolated-production-helper-only-not-parser-or-guest");
    }
}
