package dev.oreslang.runtime;

/**
 * Checked physical byte layout for the restricted scalar guest-frame subset.
 *
 * <p>This is a pure calculation, not a frame allocator or an authority token.
 * Compiler/runtime callers must separately authenticate the current actor,
 * execution and physical turn before accessing a frame. The 16-byte header
 * holds PC and operand depth; each primitive local/operand uses a 16-byte
 * tagged slot. No reference/object slot is admitted by this layout.
 *
 * <p>Extracted from the layout admission in #2781's ScalarFrameStorage,
 * without importing its stale actor scheduler or ownership implementation.
 */
final class ScalarFrameLayout {
    static final int HEADER_BYTES = 16;
    static final int SLOT_BYTES = 16;
    static final int MAX_SLOTS = 256;
    static final int MAX_BYTES = HEADER_BYTES + SLOT_BYTES * MAX_SLOTS;

    private final int locals;
    private final int operands;
    private final int bytes;

    private ScalarFrameLayout(int locals, int operands) {
        this.locals = locals;
        this.operands = operands;
        this.bytes = HEADER_BYTES + (locals + operands) * SLOT_BYTES;
    }

    static ScalarFrameLayout checked(int locals, int operands) {
        // Subtraction is intentionally used before any addition/multiply:
        // even MAX_VALUE inputs cannot wrap into an admissible allocation.
        if (locals < 0 || locals > MAX_SLOTS || operands < 0
                || operands > MAX_SLOTS - locals) {
            throw new IllegalArgumentException("invalid scalar frame layout");
        }
        return new ScalarFrameLayout(locals, operands);
    }

    int locals() { return locals; }
    int operands() { return operands; }
    int bytes() { return bytes; }

    int localOffset(int localIndex) {
        return checkedOffset(localIndex, locals, 0);
    }

    int operandOffset(int operandIndex) {
        return checkedOffset(operandIndex, operands, locals);
    }

    private static int checkedOffset(int index, int count, int base) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("scalar frame slot");
        }
        // The entire range has already been checked against MAX_SLOTS.
        return HEADER_BYTES + (base + index) * SLOT_BYTES;
    }
}
