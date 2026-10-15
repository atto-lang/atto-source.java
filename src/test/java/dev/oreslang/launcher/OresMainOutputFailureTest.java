package dev.oreslang.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
final class OresMainOutputFailureTest {
    @Test
    void failedStdoutCannotReportSuccessfulBuildInfo() {
        PrintStream original = System.out;
        try (PrintStream broken = new PrintStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                throw new IOException("fixture output unavailable");
            }
        })) {
            System.setOut(broken);
            assertEquals(1, OresMain.exitStatusForArgs(new String[] {"--build-info"}));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void failedStderrFlushCannotReportSuccess() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream());
             PrintStream broken = new PrintStream(new OutputStream() {
                 @Override public void write(int value) { }
                 @Override public void flush() throws IOException {
                     throw new IOException("fixture flush failed");
                 }
             })) {
            System.setOut(output);
            System.setErr(broken);
            assertEquals(1, OresMain.exitStatusForArgs(new String[] {"--build-info"}));
            assertEquals(2, OresMain.exitStatusForArgs(new String[] {"--unknown-option"}));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
