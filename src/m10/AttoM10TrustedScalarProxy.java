// Trusted-host-only injected scalar association; not a public guest
// capability, network message or Atto ABI. Host authenticates before binding.
import java.io.IOException;
@FunctionalInterface
public interface AttoM10TrustedScalarProxy {
    long request(long value) throws IOException;
}
