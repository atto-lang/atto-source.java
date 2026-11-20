// Trusted-host-only post-MAC response correlation test.
// A cryptographically authenticated REJECTED belonging to another request
// must NOT be classified as a verified rejection for this caller.
import java.io.IOException;

public final class M10ResponseCorrelationProbe {
    private M10ResponseCorrelationProbe() {}
    private static void require(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    private static void expect_wrong_route(
            AttoM10ProxyIpcGateway.Frame actual,
            AttoM10ProxyIpcGateway.Frame expected, byte kind) throws Exception {
        try {
            AttoM10ProxyIpcGateway.require_reply(actual, expected, kind);
            throw new AssertionError("forged or crossed receipt accepted");
        } catch (IOException expectedFailure) {
            require(expectedFailure.getMessage().contains("correlation"),
                "unrelated I/O error mistaken for correlation failure");
        }
    }
    private static void check_stage(byte expectedKind) throws Exception {
        var original = new AttoM10ProxyIpcGateway.Frame(expectedKind,
            7L, 711L, 105L, 1L, 32L);
        var rejected = new AttoM10ProxyIpcGateway.Frame(
            AttoM10ProxyIpcGateway.REJECTED, 7L, 711L, 105L, 1L, 4L);
        AttoM10ProxyIpcGateway.require_reply(rejected, original,
            AttoM10ProxyIpcGateway.REJECTED);
        expect_wrong_route(new AttoM10ProxyIpcGateway.Frame(
            AttoM10ProxyIpcGateway.REJECTED, 8L, 711L, 105L, 1L, 4L),
            original, AttoM10ProxyIpcGateway.REJECTED);
        expect_wrong_route(new AttoM10ProxyIpcGateway.Frame(
            AttoM10ProxyIpcGateway.REJECTED, 7L, 712L, 105L, 1L, 4L),
            original, AttoM10ProxyIpcGateway.REJECTED);
        expect_wrong_route(new AttoM10ProxyIpcGateway.Frame(
            AttoM10ProxyIpcGateway.REJECTED, 7L, 711L, 106L, 1L, 4L),
            original, AttoM10ProxyIpcGateway.REJECTED);
        expect_wrong_route(new AttoM10ProxyIpcGateway.Frame(
            AttoM10ProxyIpcGateway.REJECTED, 7L, 711L, 105L, 2L, 4L),
            original, AttoM10ProxyIpcGateway.REJECTED);
        expect_wrong_route(new AttoM10ProxyIpcGateway.Frame(
            AttoM10ProxyIpcGateway.PROCESSED, 7L, 711L, 105L, 1L, 42L),
            original, AttoM10ProxyIpcGateway.REJECTED);
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 0, "unexpected fixture inputs");
        check_stage(AttoM10ProxyIpcGateway.HELLO);
        check_stage(AttoM10ProxyIpcGateway.SEND);
        System.out.println("M10_SIGNED_REJECTED_FULL_ROUTE_CORRELATION=PASS");
        System.out.println("M10_REJECTION_NEVER_INTERMEDIARY_ACK=PASS");
    }
}
