package dev.oreslang.runtime;

import java.util.Locale;

/**
 * Strict completion-driven filesystem I/O surface.
 *
 * <p>Implementations must never park one platform/virtual worker per
 * operation. Unsupported operations fail closed.</p>
 */
public interface StrictFileIoProvider extends AutoCloseable {
    boolean supported();
    String backend();

    OresFuture<String> readText(String absolutePath);
    OresFuture<Boolean> exists(String absolutePath);

    default OresFuture<Void> writeText(String absolutePath, String value) {
        return unsupported("fs.write_text_async");
    }

    default OresFuture<Void> appendText(String absolutePath, String value) {
        return unsupported("fs.append_text_async");
    }

    default OresFuture<Void> remove(String absolutePath) {
        return unsupported("fs.remove_async");
    }

    default OresFuture<Void> mkdirAll(String absolutePath) {
        return unsupported("fs.mkdir_all_async");
    }

    private static <T> OresFuture<T> unsupported(String operation) {
        return OresFuture.failed(new UnsupportedOperationException(
                operation + " has no strict completion provider on this backend"));
    }

    static StrictFileIoProvider createDefault() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return new Lazy(os.contains("linux"));
    }

    @Override
    void close();

    final class Lazy implements StrictFileIoProvider {
        private final boolean linuxCandidate;
        private StrictFileIoProvider delegate;
        private boolean closed;

        private Lazy(boolean linuxCandidate) {
            this.linuxCandidate = linuxCandidate;
        }

        private synchronized StrictFileIoProvider delegate() {
            if (closed) return Unsupported.INSTANCE;
            if (delegate == null) {
                delegate = linuxCandidate
                        ? LinuxUringFileIoProvider.tryCreate()
                        : Unsupported.INSTANCE;
                if (delegate == null) delegate = Unsupported.INSTANCE;
            }
            return delegate;
        }

        @Override
        public boolean supported() {
            return delegate().supported();
        }

        @Override
        public synchronized String backend() {
            if (delegate != null) return delegate.backend();
            return linuxCandidate ? "linux-io_uring-lazy" : "unsupported";
        }

        @Override
        public OresFuture<String> readText(String absolutePath) {
            return delegate().readText(absolutePath);
        }

        @Override
        public OresFuture<Boolean> exists(String absolutePath) {
            return delegate().exists(absolutePath);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (delegate != null) delegate.close();
            delegate = Unsupported.INSTANCE;
        }
    }

    enum Unsupported implements StrictFileIoProvider {
        INSTANCE;

        @Override public boolean supported() { return false; }
        @Override public String backend() { return "unsupported"; }
        @Override public OresFuture<String> readText(String absolutePath) {
            return unsupportedRead("fs.read_text_async");
        }
        @Override public OresFuture<Boolean> exists(String absolutePath) {
            return unsupportedRead("fs.exists_async");
        }
        private static <T> OresFuture<T> unsupportedRead(String operation) {
            return OresFuture.failed(new UnsupportedOperationException(
                    operation + " has no strict completion provider on this backend"));
        }
        @Override public void close() { }
    }
}
