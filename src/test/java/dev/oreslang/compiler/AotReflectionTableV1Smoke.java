package dev.oreslang.compiler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.util.List;

public final class AotReflectionTableV1Smoke {
    private static int checks;
    private static AotReflectionTableV1.Field f(String n, String t, AotReflectionTableV1.Visibility v) {
        return new AotReflectionTableV1.Field(n, t, v);
    }
    private static AotReflectionTableV1.ResolvedType t(String name, List<AotReflectionTableV1.Field> fields) {
        return new AotReflectionTableV1.ResolvedType("pkg@revision",name,List.of(),fields);
    }
    private static void good(boolean verdict) { checks++; if(!verdict) throw new AssertionError("failed check " + checks); }
    private static void deny(Runnable run) {
        checks++;
        try {run.run(); throw new AssertionError("did not reject check " + checks);}
        catch(IllegalArgumentException accepted) { }
    }
    public static void main(String[] args) throws Exception {
        var left = t("Person", List.of(f("visible", "core.int", AotReflectionTableV1.Visibility.PUBLIC),
            f("secret_marker", "core.text", AotReflectionTableV1.Visibility.PRIVATE)));
        var right = t("Other", List.of(f("n", "core.int", AotReflectionTableV1.Visibility.PUBLIC)));
        var a = AotReflectionTableV1.encode(List.of(left,right));
        var b = AotReflectionTableV1.encode(List.of(right,left));
        good(Arrays.equals(a.bytes(),b.bytes()));
        good(a.sha256Hex().equals(b.sha256Hex()));
        good(!new String(a.bytes(),java.nio.charset.StandardCharsets.UTF_8).contains("secret_marker"));
        good(new String(a.bytes(),java.nio.charset.StandardCharsets.UTF_8).contains("visible"));
        byte[] borrowed = a.bytes(); borrowed[0] = 0;
        good(a.bytes()[0]=='A');
        var hdr = new DataInputStream(new ByteArrayInputStream(a.bytes()));
        byte[] magic = new byte[8]; hdr.readFully(magic);
        good(new String(magic,java.nio.charset.StandardCharsets.US_ASCII).equals("ATTOREF1"));
        good(hdr.readInt() == 1);
        good(hdr.readInt() == 2);
        good(hdr.readInt() == a.bytes().length - 52);
        deny(() -> AotReflectionTableV1.encode(List.of(left,left)));
        deny(() -> AotReflectionTableV1.encode(List.of(t("dup", List.of(f("x","i",AotReflectionTableV1.Visibility.PUBLIC),f("x","i",AotReflectionTableV1.Visibility.PRIVATE))))));
        deny(() -> AotReflectionTableV1.encode(List.of(t("bad\uD800",List.of()))));
        deny(() -> AotReflectionTableV1.encode(List.of(t("N",List.of(f("bad\u0000", "int",AotReflectionTableV1.Visibility.PUBLIC))))));
        deny(() -> AotReflectionTableV1.encode(List.of(t("N",List.of(f("e\u0301", "int",AotReflectionTableV1.Visibility.PUBLIC))))));
        deny(() -> AotReflectionTableV1.encode(List.of(t("N",List.of(f("x", "x".repeat(600),AotReflectionTableV1.Visibility.PUBLIC))))));
        deny(() -> AotReflectionTableV1.encode(java.util.Collections.nCopies(1025,t("x",List.of()))));
        var type = new AotReflectionTableV1.ResolvedType("pkg@revision","Vector",List.of("core.i64"),List.of());
        good(!Arrays.equals(AotReflectionTableV1.encode(List.of(type)).bytes(),AotReflectionTableV1.encode(List.of(t("Vector",List.of()))).bytes()));
        // Nonprinting Unicode can spoof a portable descriptor identity. Every
        // metadata entry (including non-emitted private fields) must reject it.
        deny(() -> AotReflectionTableV1.encode(List.of(t("B\u2066", List.of())))); // LRI
        deny(() -> AotReflectionTableV1.encode(List.of(t("B\u2069", List.of())))); // PDI
        deny(() -> AotReflectionTableV1.encode(List.of(t("N", List.of(f("x\u200B", "int", AotReflectionTableV1.Visibility.PUBLIC))))));
        deny(() -> AotReflectionTableV1.encode(List.of(t("N", List.of(f("private", "T\uFEFF", AotReflectionTableV1.Visibility.PRIVATE))))));
        deny(() -> AotReflectionTableV1.encode(List.of(new AotReflectionTableV1.ResolvedType("pkg\u061C", "N", List.of(), List.of()))));
        deny(() -> AotReflectionTableV1.encode(List.of(t("N\uFDD0", List.of()))));
        deny(() -> AotReflectionTableV1.encode(List.of(t("N\uD83F\uDFFE", List.of())))); // U+1FFFE
        deny(() -> AotReflectionTableV1.encode(List.of(new AotReflectionTableV1.ResolvedType("pkg", "N", List.of("X\u2066"), List.of()))));
        deny(() -> new AotReflectionTableV1.ResolvedType("pkg", "N", null, List.of()));
        deny(() -> new AotReflectionTableV1.ResolvedType("pkg", "N", List.of(), null));
        deny(() -> new AotReflectionTableV1.ResolvedType("pkg", "N", java.util.Collections.singletonList(null), List.of()));
        deny(() -> new AotReflectionTableV1.ResolvedType("pkg", "N", List.of(), java.util.Collections.singletonList(null)));
        good(AotReflectionTableV1.encode(List.of(t("Emoji\uD83D\uDE80", List.of()))).bytes().length > 52);
        good(AotReflectionTableV1.encode(List.of(new AotReflectionTableV1.ResolvedType("pkg", "N", java.util.Collections.nCopies(64, "T"), List.of()))).bytes().length > 52);
        deny(() -> AotReflectionTableV1.encode(List.of(new AotReflectionTableV1.ResolvedType("pkg", "N", java.util.Collections.nCopies(65, "T"), List.of()))));
        System.out.println("HOST_JDK21_AOT_DESCRIPTOR_SMOKE_PASS checks="+checks);
    }
}
