/**
 * The only runtime entry linked with the statically generated Atto guest.
 * This program has no references to the parser, Java compiler, Truffle,
 * dynamic host reflection, guest interpreter or runtime code generation.
 */
public final class AttoStrictI32NativeGuestEntry {
    private AttoStrictI32NativeGuestEntry() { }

    public static void main(String[] args) {
        System.exit(RtPrimitiveModule.main());
    }
}
