package dev.oreslang.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Polyglot representation of Oreslang void.
 *
 * <p>This is deliberately not an interop null value. Oreslang has no ambient
 * nullable references: absence is represented by Option<T>/None.
 */
@ExportLibrary(InteropLibrary.class)
public final class OresVoid implements TruffleObject {
    public static final OresVoid INSTANCE = new OresVoid();

    private OresVoid() { }

    @ExportMessage
    Object toDisplayString(boolean allowSideEffects) {
        return "void";
    }

    @Override
    public String toString() {
        return "void";
    }
}
