import dev.atto.compiler.gpu.GpuSaxpySubsetCompiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Local exact-source compiler parity smoke, not a physical GPU/guest runtime. */
public final class GpuSaxpySubsetCompilerTest {
    private static int checks;
    private static final String GOLDEN_SHA =
            "b5076980355531632de445e05e35554d3fb54ceeebaf707457863571bdeb77a4";

    private static void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void reject(String source, String label) {
        checks++;
        try {
            GpuSaxpySubsetCompiler.lowerOpenCl(source);
            throw new AssertionError("unsafe kernel accepted: " + label);
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("GPU-KERNEL-G2-001"))
                throw new AssertionError("unexpected GPU diagnostic " + label, expected);
        }
    }
    private static String replace(String source, String from, String to) {
        require(source.contains(from), "test mutation did not match " + from);
        return source.replace(from, to);
    }
    private static String sha256(String source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected .atto fixture");
        String source = Files.readString(Path.of(args[0]));
        String output = GpuSaxpySubsetCompiler.lowerOpenCl(source);
        require(GOLDEN_SHA.equals(sha256(output)), "generated OpenCL hash not equal LLVM golden");
        require(output.contains("__kernel void attolang_saxpy_v1"), "kernel ABI symbol");
        require(output.contains("#pragma OPENCL FP_CONTRACT OFF"), "numeric FP contract");
        require(output.contains("if (i >= n) return;"), "bounds guard");
        require(output.contains("const ulong i = get_global_id(0);"), "device ID");

        String renamed = source.replace("scale", "alpha").replace("out", "result")
                .replace("const u64 i", "const u64 lane")
                .replace("if i >= n", "if lane >= n")
                .replace("[i]", "[lane]");
        require(GOLDEN_SHA.equals(sha256(GpuSaxpySubsetCompiler.lowerOpenCl(renamed))),
                "alpha-equivalent kernel must lower identically");
        require(GOLDEN_SHA.equals(sha256(GpuSaxpySubsetCompiler.lowerOpenCl(
                source.replace("return;\n}", "}")))), "optional final return");
        require(GOLDEN_SHA.equals(sha256(GpuSaxpySubsetCompiler.lowerOpenCl(
                source.replace("pub gpu fnc", "pub /* valid */ gpu fnc")))), "comment parsing");

        reject(source + "\nfnc host(): void {}", "additional host code");
        reject(source + "\npub gpu fnc evil(): void {}", "additional device code");
        reject(replace(source,"gpu.global_id(0)","gpu.global_id(1)"), "wrong axis");
        reject(replace(source,"gpu.global_id(0)","gpu<<<1,256>>>"), "chevrons");
        reject(replace(source,"GpuArray<f32> x","HostBuffer<f32> x"), "host pointer");
        reject(replace(source,"GpuArray<f32> x","GpuArray<f64> x"), "wrong precision");
        reject(replace(source,"GpuArray<f32> mut out","GpuArray<f32> out"), "missing mut");
        reject(replace(source,"GpuArray<f32> y","GpuArray<f32> x"), "alias names");
        reject(replace(source,"if i >= n { return; }",""), "unbounded index");
        reject(replace(source,"if i >= n","if n >= i"), "reverse guard");
        reject(replace(source,"out[i] =","x[i] ="), "write to input");
        reject(replace(source,"scale * x[i]","scale * y[i]"), "different expression");
        reject(replace(source," + y[i]"," - y[i]"), "different arithmetic");
        reject(replace(source,"return;\n}","await task; return;\n}"), "await in kernel");
        reject(replace(source,"return;\n}","critical {} return;\n}"), "critical in kernel");
        reject(replace(source,"return;\n}","new Array(); return;\n}"), "allocation in kernel");
        reject(replace(source,"return;\n}","actor.send(); return;\n}"), "actor message in kernel");
        reject(replace(source,"scale","await"), "reserved name");
        reject(source+"\n/* unterminated", "unterminated comment");
        reject(source+"\nλ", "non-ASCII source");
        reject(source+" ".repeat(65537), "excessive source size");
        reject("", "empty source");
        reject(source.replace("const u64 i", "const u64 n").replace("if i >= n", "if n >= n")
                .replace("[i]", "[n]"), "duplicate index binding");
        System.out.println("G2_JAVA_HOST_SOURCE_COMPILER_PASS checks=" + checks
                + " artifact_sha256=" + GOLDEN_SHA);
    }
}
