package dev.oreslang.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Interop-only null value for an explicit Option.None passed to an allowlisted
 * Java host method. This is never a first-class Oreslang guest value and is
 * not admitted to actor transport or singleton state.
 */
@ExportLibrary(InteropLibrary.class)
public final class HostInteropNull implements TruffleObject {
    public static final HostInteropNull INSTANCE = new HostInteropNull();
    private HostInteropNull() { }

    @ExportMessage
    boolean isNull() { return true; }
}
