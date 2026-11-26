package dev.oreslang.compiler;

import java.util.LinkedHashMap;
import java.util.Map;
import static dev.oreslang.compiler.NamespaceArtifactIdentity.*;

public final class NamespaceArtifactIdentitySelfTest {
    private static int cases;
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void pass(String name) { cases++; System.out.println("PASS " + name); }
    public static void main(String[] args) {
        String a = sourceDigest("a"), b = sourceDigest("b");
        check(a.equals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"));
        rejects(() -> sourceDigest("\ud800")); rejects(() -> sourceDigest("\udfff"));
        check(!sourceDigest("?").equals(sourceDigest("\ufffd")));
        pass("strict UTF-8 source digest");
        Key first = new Key("file:src#x.atto", "cart"), second = new Key("file:src", "x.atto#cart");
        check(!first.displayKey().equals(second.displayKey()));
        check(!new Key("file:src%23x.atto", "cart").displayKey().equals(first.displayKey()));
        check(new Key("pkg:shop", "cart").displayKey().equals("pkg:shop#cart"));
        pass("injective legacy display labels");
        Key k = new Key("pkg:shop", "cart");
        Map<String, String> left = new LinkedHashMap<>(), right = new LinkedHashMap<>();
        left.put("a.atto", a); left.put("b.atto", b);
        right.put("b.atto", b); right.put("a.atto", a);
        String hash = namespaceInterface(k, left);
        check(hash.equals(namespaceInterface(k, right)));
        check(!hash.equals(namespaceInterface(k, Map.of("a.atto", b, "b.atto", b))));
        check(!hash.equals(namespaceInterface(new Key("pkg:other", "cart"), left)));
        check(!hash.equals(namespaceInterface(k, Map.of("a.atto", a))));
        pass("canonical member order, edits, identity and removal");
        check(!namespaceInterface(new Key("pkg:shop", null), left).equals(hash));
        rejects(() -> new Key("pkg:shop", ""));
        rejects(() -> namespaceInterface(k, Map.of("a.atto", "bad")));
        rejects(() -> namespaceInterface(k, Map.of()));
        pass("invalid metadata rejected");
        Key k2 = new Key("pkg:shop", "tax");
        String tax = namespaceInterface(k2, Map.of("tax.atto", a));
        check(packageInterface("pkg:shop", Map.of(k, hash, k2, tax)).equals(packageInterface("pkg:shop", Map.of(k2, tax, k, hash))));
        check(!packageInterface("pkg:shop", Map.of(k, hash, k2, tax)).equals(packageInterface("pkg:shop", Map.of(k, hash))));
        rejects(() -> packageInterface("pkg:other", Map.of(k, hash)));
        pass("structured package aggregation and mixed-owner denial");
        System.out.println("NAMESPACE_IDENTITY_PASS cases=" + cases);
    }
}
