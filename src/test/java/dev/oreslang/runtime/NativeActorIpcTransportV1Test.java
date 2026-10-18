package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
final class NativeActorIpcTransportV1Test {

    @Test
    void launchesDeletedExactObjectWithOnlyFixedDescriptorsAndSeparateLanes()
            throws Exception {
        assertTrue(
                NativeActorIpcTransportV1.supported(),
                "Linux IPC transport requires closefrom-capable posix_spawn");

        Path directory = Files.createTempDirectory("ores-ipc-worker-");
        Path source = directory.resolve("worker.c");
        Path executable = directory.resolve("worker");
        try {
            Files.writeString(source, workerSource(), StandardCharsets.UTF_8);
            Process compiler = new ProcessBuilder(
                    "cc",
                    "-std=c11",
                    "-O2",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    source.toString(),
                    "-o",
                    executable.toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] compilerOutput = compiler.getInputStream().readAllBytes();
            assertTrue(
                    compiler.waitFor(20, java.util.concurrent.TimeUnit.SECONDS),
                    "test worker compiler timed out");
            assertEquals(
                    0,
                    compiler.exitValue(),
                    () -> "worker compile failed:\n"
                            + new String(
                                    compilerOutput,
                                    StandardCharsets.UTF_8));

            byte[] exactExecutable = Files.readAllBytes(executable);
            byte[] bootstrap = bootstrap();
            Files.delete(executable);
            Files.delete(source);

            try (NativeActorIpcTransportV1 transport =
                         NativeActorIpcTransportV1.launch(
                                 exactExecutable,
                                 bootstrap)) {
                assertTrue(transport.pid() > 0);
                assertTrue(transport.isAlive());

                assertArrayEquals(
                        "READY".getBytes(StandardCharsets.US_ASCII),
                        transport.readControlFrame(2_000));

                byte[] data =
                        "physical-data-lane"
                                .getBytes(StandardCharsets.US_ASCII);
                transport.writeDataFrame(data);
                assertArrayEquals(
                        data,
                        transport.readDataFrame(2_000));

                byte[] control =
                        "physical-control-lane"
                                .getBytes(StandardCharsets.US_ASCII);
                transport.writeControlFrame(control);
                assertArrayEquals(
                        control,
                        transport.readControlFrame(2_000));
            } finally {
                Arrays.fill(exactExecutable, (byte) 0);
                Arrays.fill(bootstrap, (byte) 0);
            }
        } finally {
            Files.deleteIfExists(executable);
            Files.deleteIfExists(source);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void idleTimeoutIsRecoverableButPartialPrefixTimeoutFailsClosed()
            throws Exception {
        assertTrue(NativeActorIpcTransportV1.supported());

        Path directory = Files.createTempDirectory("ores-ipc-timeout-");
        Path source = directory.resolve("worker.c");
        Path executable = directory.resolve("worker");
        byte[] exactExecutable = null;
        byte[] bootstrap = bootstrap();
        try {
            Files.writeString(source, workerSource(), StandardCharsets.UTF_8);
            Process compiler = new ProcessBuilder(
                    "cc",
                    "-std=c11",
                    "-O2",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    source.toString(),
                    "-o",
                    executable.toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] compilerOutput = compiler.getInputStream().readAllBytes();
            assertTrue(
                    compiler.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(
                    0,
                    compiler.exitValue(),
                    () -> new String(
                            compilerOutput,
                            StandardCharsets.UTF_8));
            exactExecutable = Files.readAllBytes(executable);
            Files.delete(executable);
            Files.delete(source);

            try (NativeActorIpcTransportV1 transport =
                         NativeActorIpcTransportV1.launch(
                                 exactExecutable,
                                 bootstrap)) {
                assertArrayEquals(
                        "READY".getBytes(StandardCharsets.US_ASCII),
                        transport.readControlFrame(2_000));

                assertThrows(
                        NativeActorIpcTransportV1.ReadTimeoutException.class,
                        () -> transport.readDataFrame(25));

                byte[] unblock =
                        "unblock-data".getBytes(StandardCharsets.US_ASCII);
                transport.writeDataFrame(unblock);
                assertArrayEquals(
                        unblock,
                        transport.readDataFrame(2_000));

                byte[] trigger =
                        "PART".getBytes(StandardCharsets.US_ASCII);
                transport.writeControlFrame(trigger);
                assertThrows(
                        IllegalStateException.class,
                        () -> transport.readControlFrame(25));
            }
        } finally {
            if (exactExecutable != null) {
                Arrays.fill(exactExecutable, (byte) 0);
            }
            Arrays.fill(bootstrap, (byte) 0);
            Files.deleteIfExists(executable);
            Files.deleteIfExists(source);
            Files.deleteIfExists(directory);
        }
    }

    private static byte[] bootstrap() {
        byte[] wire = new byte[ActorIpcBootstrapV1.WIRE_BYTES];
        System.arraycopy(
                ActorIpcBootstrapV1.MAGIC,
                0,
                wire,
                0,
                ActorIpcBootstrapV1.MAGIC.length);
        java.nio.ByteBuffer.wrap(wire)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putShort(ActorIpcBootstrapV1.MAGIC.length, (short) 1)
                .putShort(
                        ActorIpcBootstrapV1.MAGIC.length + Short.BYTES,
                        (short) 0);
        return wire;
    }

    private static String workerSource() {
        return """
                #define _GNU_SOURCE
                #include <errno.h>
                #include <fcntl.h>
                #include <stdint.h>
                #include <stdlib.h>
                #include <string.h>
                #include <time.h>
                #include <unistd.h>

                #ifndef F_GET_SEALS
                #define F_GET_SEALS 1034
                #endif
                #ifndef F_SEAL_SEAL
                #define F_SEAL_SEAL 0x0001
                #endif
                #ifndef F_SEAL_SHRINK
                #define F_SEAL_SHRINK 0x0002
                #endif
                #ifndef F_SEAL_GROW
                #define F_SEAL_GROW 0x0004
                #endif
                #ifndef F_SEAL_WRITE
                #define F_SEAL_WRITE 0x0008
                #endif

                extern char **environ;

                static int read_exact(int fd, unsigned char *p, size_t n) {
                    size_t off = 0;
                    while (off < n) {
                        ssize_t r = read(fd, p + off, n - off);
                        if (r < 0 && errno == EINTR) continue;
                        if (r <= 0) return -1;
                        off += (size_t)r;
                    }
                    return 0;
                }

                static int write_all(int fd, const unsigned char *p, size_t n) {
                    size_t off = 0;
                    while (off < n) {
                        ssize_t w = write(fd, p + off, n - off);
                        if (w < 0 && errno == EINTR) continue;
                        if (w <= 0) return -1;
                        off += (size_t)w;
                    }
                    return 0;
                }

                static uint32_t u32be(const unsigned char p[4]) {
                    return ((uint32_t)p[0] << 24)
                        | ((uint32_t)p[1] << 16)
                        | ((uint32_t)p[2] << 8)
                        | (uint32_t)p[3];
                }

                static void put_u32be(unsigned char p[4], uint32_t v) {
                    p[0] = (unsigned char)(v >> 24);
                    p[1] = (unsigned char)(v >> 16);
                    p[2] = (unsigned char)(v >> 8);
                    p[3] = (unsigned char)v;
                }

                static int read_frame(int fd, unsigned char **out, uint32_t *len) {
                    unsigned char prefix[4];
                    if (read_exact(fd, prefix, 4) != 0) return -1;
                    uint32_t n = u32be(prefix);
                    if (n == 0 || n > 1048576U + 256U) return -1;
                    unsigned char *p = (unsigned char *)malloc(n);
                    if (p == NULL) return -1;
                    if (read_exact(fd, p, n) != 0) {
                        free(p);
                        return -1;
                    }
                    *out = p;
                    *len = n;
                    return 0;
                }

                static int write_frame(int fd, const unsigned char *p, uint32_t n) {
                    unsigned char prefix[4];
                    put_u32be(prefix, n);
                    return write_all(fd, prefix, 4) == 0
                            && write_all(fd, p, n) == 0 ? 0 : -1;
                }

                int main(void) {
                    if (environ != NULL && environ[0] != NULL) return 10;

                    for (int fd = 0; fd <= 2; fd++) {
                        errno = 0;
                        if (fcntl(fd, F_GETFD) != -1 || errno != EBADF) return 11;
                    }
                    for (int fd = 3; fd <= 8; fd++) {
                        if (fcntl(fd, F_GETFD) == -1) return 12;
                    }
                    for (int fd = 9; fd < 256; fd++) {
                        errno = 0;
                        if (fcntl(fd, F_GETFD) != -1 || errno != EBADF) return 13;
                    }

                    const int required_seals =
                            F_SEAL_WRITE | F_SEAL_GROW
                                    | F_SEAL_SHRINK | F_SEAL_SEAL;
                    int seals = fcntl(8, F_GET_SEALS);
                    if (seals < 0 || (seals & required_seals) != required_seals) {
                        return 14;
                    }
                    errno = 0;
                    if (write(8, "x", 1) >= 0 || errno != EPERM) return 15;

                    unsigned char bootstrap[124];
                    if (read_exact(3, bootstrap, sizeof bootstrap) != 0) return 16;
                    unsigned char extra;
                    if (read(3, &extra, 1) != 0) return 17;
                    if (memcmp(bootstrap, "ORESIPCB1", 8) != 0) return 18;

                    const unsigned char ready[] = "READY";
                    if (write_frame(7, ready, 5) != 0) return 19;

                    unsigned char *data = NULL;
                    uint32_t data_len = 0;
                    if (read_frame(4, &data, &data_len) != 0) return 20;
                    if (write_frame(5, data, data_len) != 0) return 21;
                    free(data);

                    unsigned char *control = NULL;
                    uint32_t control_len = 0;
                    if (read_frame(6, &control, &control_len) != 0) return 22;
                    if (control_len == 4 && memcmp(control, "PART", 4) == 0) {
                        unsigned char prefix[4];
                        put_u32be(prefix, 4);
                        if (write_all(7, prefix, 2) != 0) return 23;
                        struct timespec delay = {0, 200000000L};
                        nanosleep(&delay, NULL);
                        if (write_all(7, prefix + 2, 2) != 0) return 24;
                        if (write_all(7, (const unsigned char *)"late", 4) != 0) {
                            return 25;
                        }
                    } else {
                        if (write_frame(7, control, control_len) != 0) return 26;
                    }
                    free(control);

                    for (;;) pause();
                }
                """;
    }
}
