// Host-only OS peer credential proof; no Atto guest, authenticated tenant
// capabilities, canonical transport schema or compiled proxy sender.
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import jdk.net.ExtendedSocketOptions;
import jdk.net.UnixDomainPrincipal;

public final class M10UnixPeerCredentialProbe {
    private M10UnixPeerCredentialProbe() {}
    private static void require(boolean actual, String why) {
        if (!actual) throw new AssertionError(why);
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 0, "no input required");
        Path parent = Files.createTempDirectory("atto-m10-peer-owner-");
        Files.setPosixFilePermissions(parent, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
        Path socket = parent.resolve("peer.sock");
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            try (SocketChannel client = SocketChannel.open(UnixDomainSocketAddress.of(socket));
                 SocketChannel accepted = listener.accept()) {
                var owner = Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS);
                UnixDomainPrincipal inbound = accepted.getOption(ExtendedSocketOptions.SO_PEERCRED);
                UnixDomainPrincipal outbound = client.getOption(ExtendedSocketOptions.SO_PEERCRED);
                require(inbound != null && inbound.user().equals(owner),
                    "inbound peer did not match private directory OS owner");
                require(outbound != null && outbound.user().equals(owner),
                    "outbound peer did not match private directory OS owner");
                String old = System.getProperty("user.name");
                try {
                    System.setProperty("user.name", "forged-jvm-property");
                    require(inbound.user().equals(owner) && outbound.user().equals(owner),
                        "mutable Java property changed OS peer identity");
                } finally {
                    if (old == null) System.clearProperty("user.name");
                    else System.setProperty("user.name", old);
                }
                System.out.println("M10_UDS_PEER_KERNEL_PRINCIPAL_NOT_USER_NAME_PROPERTY=PASS");
            }
        } finally {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(parent);
        }
    }
}
