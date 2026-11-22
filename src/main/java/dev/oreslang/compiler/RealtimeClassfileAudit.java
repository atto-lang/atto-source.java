package dev.oreslang.compiler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact post-lowering verifier for the classfile emitted by
 * {@link RealtimePrimitiveCompiler}. It admits only primitive static methods,
 * no fields, no exception handlers, no allocation/monitor/dynamic-dispatch
 * bytecodes, forward-only control flow, and acyclic invokestatic calls back
 * into the same generated module.
 */
public final class RealtimeClassfileAudit {
    private static final int JAVA_8_CLASSFILE_MAJOR = 52;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_SYNCHRONIZED = 0x0020;
    private static final int MAX_CODE_BYTES = 1_000_000;
    private static final int MAX_CLASS_BYTES = 1_500_000;
    private static final int ACC_NATIVE = 0x0100;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ANNOTATION = 0x2000;
    private static final int ACC_ENUM = 0x4000;

    public record Report(
            String className,
            int verifiedMethods,
            int bytecodeBytes,
            String assurance) { }

    private sealed interface Cp permits Utf8Cp, IntegerCp, ClassCp, NameAndTypeCp, MethodRefCp, OtherCp { }
    private record Utf8Cp(String value) implements Cp { }
    private record IntegerCp() implements Cp { }
    private record ClassCp(int nameIndex) implements Cp { }
    private record NameAndTypeCp(int nameIndex, int descriptorIndex) implements Cp { }
    private record MethodRefCp(int classIndex, int nameAndTypeIndex) implements Cp { }
    private record OtherCp(int tag) implements Cp { }
    private record CodeAttribute(byte[] code, int exceptionHandlers) { }

    public static Report audit(Path classFile) throws IOException {
        long fileSize = Files.size(classFile);
        if (fileSize < 24 || fileSize > MAX_CLASS_BYTES) deny("invalid or oversized classfile");
        byte[] bytes = Files.readAllBytes(classFile);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) deny("bad classfile magic");
            in.readUnsignedShort();
            int major = in.readUnsignedShort();
            if (major != JAVA_8_CLASSFILE_MAJOR) {
                deny("RT primitive classfile must be exactly Java 8; major=" + major);
            }

            Cp[] cp = readConstantPool(in);
            int classAccess = in.readUnsignedShort();
            if ((classAccess & (ACC_INTERFACE | ACC_ANNOTATION | ACC_ENUM)) != 0) {
                deny("only an ordinary final class is permitted");
            }
            int thisClass = in.readUnsignedShort();
            int superClass = in.readUnsignedShort();
            String className = className(cp, thisClass);
            if (!"java.lang.Object".equals(className(cp, superClass))) {
                deny("unexpected superclass; only Object is admitted");
            }
            if (!RealtimePrimitiveCompiler.CLASS_NAME.equals(className)) {
                deny("unexpected generated class " + className);
            }

            int interfaces = in.readUnsignedShort();
            if (interfaces != 0) deny("interfaces are forbidden in RT primitive artifact");
            for (int i = 0; i < interfaces; i++) in.readUnsignedShort();

            int fields = in.readUnsignedShort();
            if (fields != 0) deny("fields/static state are forbidden in RT primitive artifact");
            for (int i = 0; i < fields; i++) skipMember(in, cp);

            int methods = in.readUnsignedShort();
            int verified = 0;
            int bytecodeBytes = 0;
            boolean constructorSeen = false;
            Map<String, List<String>> callGraph = new HashMap<>();
            for (int i = 0; i < methods; i++) {
                int access = in.readUnsignedShort();
                String name = utf8(cp, in.readUnsignedShort());
                String descriptor = utf8(cp, in.readUnsignedShort());
                int attributes = in.readUnsignedShort();

                CodeAttribute codeAttribute = null;
                if (attributes != 1) deny("unexpected method attributes");
                for (int a = 0; a < attributes; a++) {
                    String attributeName = utf8(cp, in.readUnsignedShort());
                    int length = in.readInt();
                    if (length < 0) deny("negative attribute length");
                    byte[] payload = in.readNBytes(length);
                    if (payload.length != length) deny("truncated method attribute");
                    if (!"Code".equals(attributeName)) deny("unknown method attribute");
                    if (codeAttribute != null) deny("duplicate Code attribute");
                    codeAttribute = parseCodeAttribute(payload, cp);
                }

                if ("<init>".equals(name)) {
                    if (constructorSeen) deny("duplicate constructors");
                    constructorSeen = true;
                    verifyConstructor(access, descriptor, codeAttribute, cp);
                    continue;
                }
                if ("<clinit>".equals(name)) deny("class initialization is forbidden");
                if ((access & ACC_STATIC) == 0) deny("critical method is not static: " + name);
                if ((access & (ACC_NATIVE | ACC_ABSTRACT)) != 0) deny("native/abstract method forbidden");
                if ((access & ACC_SYNCHRONIZED) != 0) {
                    deny("synchronized critical methods are forbidden: " + name);
                }
                requirePrimitiveDescriptor(descriptor);
                if (codeAttribute == null) deny("critical method has no Code attribute: " + name);
                if (codeAttribute.exceptionHandlers() != 0) {
                    deny("exception handlers are forbidden: " + name);
                }

                String methodId = methodId(name, descriptor);
                if (callGraph.containsKey(methodId)) deny("duplicate critical method: " + methodId);
                List<String> calls = auditCode(
                        codeAttribute.code(), cp, className, methodId);
                callGraph.put(methodId, calls);
                verified++;
                bytecodeBytes = Math.addExact(bytecodeBytes, codeAttribute.code().length);
            }
            if (!constructorSeen) deny("expected javac constructor was not present");
            if (verified == 0) deny("no critical methods were emitted");
            requireAcyclicCallGraph(callGraph);

            int classAttributes = in.readUnsignedShort();
            for (int i = 0; i < classAttributes; i++) {
                String name = utf8(cp, in.readUnsignedShort());
                int length = in.readInt();
                if (length < 0) deny("negative class attribute length");
                if (!"SourceFile".equals(name) || length != 2) {
                    deny("unsupported class-level attribute: " + name);
                }
                skipFully(in, length);
            }
            if (in.available() != 0) deny("trailing classfile bytes");
            return new Report(className, verified, bytecodeBytes,
                    "SOURCE_AND_CLASSFILE_PRIMITIVE_SUBSET;ACYCLIC;WCET_AND_VM_QUALIFICATION_REQUIRED");
        } catch (ArithmeticException overflow) {
            throw invalid("classfile resource accounting overflow");
        }
    }

    private static CodeAttribute parseCodeAttribute(byte[] payload, Cp[] cp) throws IOException {
        try (DataInputStream codeIn =
                     new DataInputStream(new ByteArrayInputStream(payload))) {
            codeIn.readUnsignedShort(); // max_stack
            codeIn.readUnsignedShort(); // max_locals
            int codeLength = codeIn.readInt();
            if (codeLength < 1 || codeLength > MAX_CODE_BYTES) deny("invalid code length");
            byte[] code = codeIn.readNBytes(codeLength);
            if (code.length != codeLength) deny("truncated bytecode");
            int exceptionHandlers = codeIn.readUnsignedShort();
            for (int e = 0; e < exceptionHandlers; e++) {
                codeIn.readUnsignedShort();
                codeIn.readUnsignedShort();
                codeIn.readUnsignedShort();
                codeIn.readUnsignedShort();
            }
            skipCodeAttributes(codeIn, cp);
            if (codeIn.available() != 0) deny("Code attribute length mismatch");
            return new CodeAttribute(code, exceptionHandlers);
        }
    }

    private static void verifyConstructor(int access, String descriptor, CodeAttribute code, Cp[] cp) {
        if ((access & (ACC_STATIC | ACC_NATIVE | ACC_ABSTRACT | ACC_SYNCHRONIZED)) != 0
                || !"()V".equals(descriptor) || code == null
                || code.exceptionHandlers() != 0) deny("invalid Object constructor");
        byte[] b = code.code();
        if (b.length != 5 || (b[0] & 0xff) != 0x2a || (b[1] & 0xff) != 0xb7
                || (b[4] & 0xff) != 0xb1) deny("constructor contains unqualified effects");
        int index = u2(b, 2);
        Cp item = cpAt(cp, index);
        if (!(item instanceof MethodRefCp)) deny("constructor invokes a non-method");
        MethodRefCp ref = (MethodRefCp) item;
        if (!"java.lang.Object".equals(className(cp, ref.classIndex()))) {
            deny("constructor must only invoke Object.<init>");
        }
        Cp ntItem = cpAt(cp, ref.nameAndTypeIndex());
        if (!(ntItem instanceof NameAndTypeCp)) deny("bad Object constructor descriptor");
        NameAndTypeCp nt = (NameAndTypeCp) ntItem;
        if (!"<init>".equals(utf8(cp, nt.nameIndex()))
                || !"()V".equals(utf8(cp, nt.descriptorIndex()))) {
            deny("constructor must only call Object.<init>()V");
        }
    }

    private static List<String> auditCode(
            byte[] code, Cp[] cp, String ownClass, String methodId) {
        boolean[] instructionStart = new boolean[code.length];
        int[] instructionWidths = new int[code.length];
        int[] branches = new int[code.length];
        java.util.Arrays.fill(branches, -1);
        ArrayList<Integer> branchTargets = new ArrayList<>();
        ArrayList<String> calls = new ArrayList<>();
        int pc = 0;
        while (pc < code.length) {
            instructionStart[pc] = true;
            int op = code[pc] & 0xff;
            int width;
            switch (op) {
                case 0x02,0x03,0x04,0x05,0x06,0x07,0x08, // iconst_m1..5
                     0x1a,0x1b,0x1c,0x1d,                 // iload_0..3
                     0x3b,0x3c,0x3d,0x3e,                 // istore_0..3
                     0x60,0x64,0x68,                       // iadd,isub,imul
                     0x74,                                 // ineg
                     0x7e,0x80,0x82,                       // iand,ior,ixor
                     0xac -> width = 1;                    // ireturn
                case 0x10,0x15,0x36 -> width = 2;         // bipush,iload,istore
                case 0x11 -> width = 3;                    // sipush
                case 0x99,0x9a,0x9b,0x9c,0x9d,0x9e,       // if*
                     0x9f,0xa0,0xa1,0xa2,0xa3,0xa4,       // if_icmp*
                     0xa7 -> {                              // goto
                    if (pc > code.length - 3) deny("truncated branch in " + methodId);
                    short offset = (short) u2(code, pc + 1);
                    int target = pc + (int) offset;
                    if (target <= pc) deny("backward/self branch is forbidden in " + methodId);
                    branchTargets.add(target);
                    branches[pc] = target;
                    width = 3;
                }
                case 0x12 -> {                             // ldc
                    if (pc > code.length - 2) deny("truncated ldc in " + methodId);
                    requireIntegerConstant(cp, code[pc + 1] & 0xff);
                    width = 2;
                }
                case 0x13 -> {                             // ldc_w
                    requireIntegerConstant(cp, u2(code, pc + 1));
                    width = 3;
                }
                case 0xb8 -> {                             // invokestatic
                    int index = u2(code, pc + 1);
                    calls.add(requireOwnStaticCall(cp, index, ownClass));
                    width = 3;
                }
                default -> throw invalid("forbidden bytecode 0x"
                        + Integer.toHexString(op) + " in " + methodId + " at pc " + pc);
            }
            if (pc > code.length - width) deny("truncated instruction in " + methodId);
            instructionWidths[pc] = width;
            pc += width;
        }
        if (pc != code.length) deny("misaligned bytecode in " + methodId);
        for (int target : branchTargets) {
            if (target < 0 || target >= code.length || !instructionStart[target]) {
                deny("branch target is outside an instruction boundary in " + methodId);
            }
        }
        boolean[] reachable = new boolean[code.length];
        java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();
        pending.add(0);
        while (!pending.isEmpty()) {
            int at = pending.removeFirst();
            if (at < 0 || at >= code.length || !instructionStart[at]) {
                deny("reachable branch or fallthrough leaves instruction boundaries in " + methodId);
            }
            if (reachable[at]) continue;
            reachable[at] = true;
            int opcode = code[at] & 0xff;
            if (opcode == 0xac) continue;
            if (branches[at] >= 0) {
                pending.add(branches[at]);
                if (opcode == 0xa7) continue;
            }
            int next = at + instructionWidths[at];
            if (next >= code.length) deny("critical path falls off end of bytecode: " + methodId);
            pending.add(next);
        }
        for (int at = 0; at < code.length; at++) {
            if (instructionStart[at] && !reachable[at]) deny("unreachable bytecode in " + methodId);
        }
        return List.copyOf(calls);
    }

    private static String requireOwnStaticCall(Cp[] cp, int index, String ownClass) {
        Cp item = cpAt(cp, index);
        if (!(item instanceof MethodRefCp)) deny("invokestatic does not reference a method");
        MethodRefCp ref = (MethodRefCp) item;
        String owner = className(cp, ref.classIndex());
        if (!ownClass.equals(owner)) deny("external static call is forbidden: " + owner);
        Cp ntItem = cpAt(cp, ref.nameAndTypeIndex());
        if (!(ntItem instanceof NameAndTypeCp)) deny("invalid method name/type");
        NameAndTypeCp nt = (NameAndTypeCp) ntItem;
        String name = utf8(cp, nt.nameIndex());
        if (name.startsWith("<")) deny("special method call is forbidden");
        String descriptor = utf8(cp, nt.descriptorIndex());
        requirePrimitiveDescriptor(descriptor);
        return methodId(name, descriptor);
    }

    private static void requireAcyclicCallGraph(Map<String, List<String>> graph) {
        Set<String> complete = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String method : graph.keySet()) {
            visit(method, graph, active, complete);
        }
    }

    private static void visit(
            String method,
            Map<String, List<String>> graph,
            Set<String> active,
            Set<String> complete) {
        if (complete.contains(method)) return;
        if (!active.add(method)) deny("recursive bytecode call graph: " + method);
        List<String> calls = graph.get(method);
        if (calls == null) deny("invokestatic targets unverified method: " + method);
        for (String callee : calls) {
            if (!graph.containsKey(callee)) {
                deny("invokestatic targets unverified method: " + callee);
            }
            visit(callee, graph, active, complete);
        }
        active.remove(method);
        complete.add(method);
    }

    private static String methodId(String name, String descriptor) {
        return name + descriptor;
    }

    private static void requireIntegerConstant(Cp[] cp, int index) {
        if (!(cpAt(cp, index) instanceof IntegerCp)) deny("ldc may load only an integer constant");
    }

    private static void requirePrimitiveDescriptor(String descriptor) {
        if (!descriptor.matches("\\([IZ]*\\)[IZ]")) {
            deny("non-primitive method descriptor: " + descriptor);
        }
    }

    private static int u2(byte[] bytes, int at) {
        if (at < 0 || at + 1 >= bytes.length) deny("truncated constant-pool operand");
        return ((bytes[at] & 0xff) << 8) | (bytes[at + 1] & 0xff);
    }

    private static Cp[] readConstantPool(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count < 1 || count > 65535) deny("invalid constant pool size");
        Cp[] cp = new Cp[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            cp[i] = switch (tag) {
                case 1 -> new Utf8Cp(in.readUTF());
                case 3 -> { in.readInt(); yield new IntegerCp(); }
                case 4 -> { in.readInt(); yield new OtherCp(tag); }
                case 5,6 -> {
                    in.readLong();
                    Cp value = new OtherCp(tag);
                    cp[i] = value;
                    i++;
                    if (i < count) cp[i] = new OtherCp(0);
                    yield value;
                }
                case 7 -> new ClassCp(in.readUnsignedShort());
                case 8,16,19,20 -> { in.readUnsignedShort(); yield new OtherCp(tag); }
                case 9,11,17,18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                    yield new OtherCp(tag);
                }
                case 10 -> new MethodRefCp(in.readUnsignedShort(), in.readUnsignedShort());
                case 12 -> new NameAndTypeCp(in.readUnsignedShort(), in.readUnsignedShort());
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    yield new OtherCp(tag);
                }
                default -> throw invalid("unsupported constant pool tag " + tag);
            };
        }
        return cp;
    }

    private static String className(Cp[] cp, int index) {
        Cp item = cpAt(cp, index);
        if (!(item instanceof ClassCp)) deny("invalid class constant");
        ClassCp cls = (ClassCp) item;
        return utf8(cp, cls.nameIndex()).replace('/', '.');
    }

    private static String utf8(Cp[] cp, int index) {
        Cp item = cpAt(cp, index);
        if (!(item instanceof Utf8Cp)) deny("invalid UTF8 constant");
        Utf8Cp text = (Utf8Cp) item;
        return text.value();
    }

    private static Cp cpAt(Cp[] cp, int index) {
        if (index <= 0 || index >= cp.length || cp[index] == null) deny("invalid constant-pool index");
        return cp[index];
    }

    private static void skipMember(DataInputStream in, Cp[] cp) throws IOException {
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        skipAttributes(in, cp);
    }

    private static void skipCodeAttributes(DataInputStream in, Cp[] cp) throws IOException {
        int count = in.readUnsignedShort();
        boolean stackMap = false;
        for (int i = 0; i < count; i++) {
            String name = utf8(cp, in.readUnsignedShort());
            int length = in.readInt();
            if (!"StackMapTable".equals(name) || stackMap) {
                deny("unsupported or duplicate code attribute: " + name);
            }
            stackMap = true;
            skipFully(in, length);
        }
    }

    private static void skipAttributes(DataInputStream in, Cp[] cp) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            utf8(cp, in.readUnsignedShort());
            int length = in.readInt();
            skipFully(in, length);
        }
    }

    private static void skipFully(DataInputStream in, int length) throws IOException {
        if (length < 0) deny("negative attribute length");
        byte[] skipped = in.readNBytes(length);
        if (skipped.length != length) deny("truncated classfile attribute");
    }

    private static void deny(String text) {
        throw invalid(text);
    }

    private static IllegalArgumentException invalid(String text) {
        return new IllegalArgumentException("RT-CLASSFILE: " + text);
    }

    private RealtimeClassfileAudit() { }
}
