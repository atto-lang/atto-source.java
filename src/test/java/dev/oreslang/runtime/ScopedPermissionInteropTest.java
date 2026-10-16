package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ScopedPermissionInteropTest {
    @Test
    void rawJdkFilesystemClassCannotBypassOresReadWriteScopes() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> policy.restrictedContextBuilder(
                        ExecutionProfile.serverJit(),
                        Set.of("java.nio.file.Files")));

        assertTrue(denied.getMessage().contains("direct Java host I/O"));
    }

    @Test
    void rawJdkNetworkClassCannotBypassOresNetScopes() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.NETWORK);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> policy.restrictedContextBuilder(
                        ExecutionProfile.serverJit(),
                        Set.of("java.net.Socket")));

        assertTrue(denied.getMessage().contains("direct Java host I/O"));
    }

    @Test
    void indirectJdkFileAccessIsNotAllowedThroughHostClassInterop() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP);
        // These classes expose public constructors or static methods which
        // open arbitrary host paths despite living outside java.io/java.nio.
        for (String className : new String[]{
                "java.util.Scanner", "java.util.Formatter",
                "java.util.zip.ZipFile", "java.util.jar.JarFile",
                "javax.imageio.ImageIO"}) {
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> policy.restrictedContextBuilder(
                            ExecutionProfile.serverJit(), Set.of(className)),
                    "filesystem-capable host class admitted: " + className);
            assertTrue(denied.getMessage().contains("direct Java host I/O"));
        }
    }

    @Test
    void classAllowlistCannotExposeOtherUnscopedHostIoPackages() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP);
        // Each representative exposes or creates an object able to reach
        // host files, sockets, persistent state, or external resources.
        // Rejecting a class name must happen before HostAccess is built.
        for (String className : new String[]{
                "java.util.logging.FileHandler",
                "java.util.logging.SocketHandler",
                "java.util.prefs.Preferences",
                "java.util.zip.ZipInputStream",
                "java.util.jar.JarInputStream",
                "javax.imageio.stream.FileImageInputStream",
                "javax.naming.InitialContext",
                "javax.net.ssl.SSLSocketFactory",
                "java.sql.DriverManager",
                "javax.sql.DataSource",
                "java.rmi.registry.LocateRegistry",
                "javax.xml.parsers.DocumentBuilderFactory",
                "org.xml.sax.InputSource",
                "java.awt.Desktop",
                "java.util.ResourceBundle"}) {
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> policy.restrictedContextBuilder(
                            ExecutionProfile.serverJit(), Set.of(className)),
                    "unsafe host class admitted: " + className);
            assertTrue(denied.getMessage().contains("direct Java host I/O"),
                    "class must be rejected at scoped-I/O boundary: " + className);
        }
    }

    @Test
    void desktopDeviceAndTimerClassesCannotEscapeActorPermissionBoundary() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.THREAD_CREATE);
        // JAVA_INTEROP + even THREAD_CREATE must not authorize unscoped
        // screen/input capture, clipboard, print/audio device operations
        // or construction of unmanaged java.util.Timer worker threads.
        for (String className : new String[]{
                "java.awt.Robot", "java.awt.Toolkit",
                "java.awt.datatransfer.Clipboard", "javax.swing.JFileChooser",
                "javax.print.PrintServiceLookup",
                "javax.sound.sampled.AudioSystem",
                "javax.sound.midi.MidiSystem",
                "java.util.Timer", "java.util.TimerTask"
        }) {
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> policy.restrictedContextBuilder(
                            ExecutionProfile.serverJit(), Set.of(className)),
                    "desktop/device/thread-capable host class admitted: " + className);
            assertTrue(denied.getMessage().contains("direct Java host I/O"),
                    "class must fail before HostAccess construction: " + className);
        }
    }

    @Test
    void hostAsyncUtilitiesCannotBypassSingleFlightActorExecution() {
        // Even with explicit THREAD_CREATE, actor execution must remain on
        // the runtime's serial admission lane. JDK utilities must not create
        // cleaner threads or schedule guest callbacks in a common worker pool.
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.THREAD_CREATE);
        for (String className : new String[]{
                "java.lang.ref.Cleaner",
                "java.lang.ref.Cleaner$Cleanable",
                "java.util.Arrays",
                "java.util.stream.Stream",
                "java.util.stream.IntStream",
                "java.util.stream.LongStream",
                "java.util.stream.DoubleStream",
                "java.util.stream.StreamSupport",
                "java.util.concurrent.Executors",
                "java.util.concurrent.CompletableFuture",
                "java.util.concurrent.ForkJoinPool",
                "java.util.concurrent.ScheduledThreadPoolExecutor"
        }) {
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> policy.restrictedContextBuilder(
                            ExecutionProfile.serverJit(), Set.of(className)),
                    "unmanaged host execution primitive admitted: " + className);
            assertTrue(denied.getMessage().contains("direct Java host I/O"),
                    "class must fail before Graal HostAccess construction: " + className);
        }

        // Existing data-only Java interop must remain available. Blocking
        // the whole java.util package would regress this intended contract.
        assertDoesNotThrow(() -> policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(),
                Set.of("java.util.ArrayList", "java.util.Map$Entry")));
    }

    @Test
    void keystoresAndGlobalSecurityConfigurationCannotBypassScopedAuthority() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);
        // Even with scoped file grants, these public JDK classes expose
        // direct file reads, global security-provider changes, and JAAS
        // login configuration outside Ores-owned authorization boundaries.
        for (String className : new String[]{
                "java.security.KeyStore",
                "java.security.KeyStore$Builder",
                "java.security.Security",
                "javax.security.auth.login.Configuration",
                "javax.security.auth.login.LoginContext",
                "javax.security.auth.login.AppConfigurationEntry"
        }) {
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> policy.restrictedContextBuilder(
                            ExecutionProfile.serverJit(), Set.of(className)),
                    "global security or keystore authority admitted: " + className);
            assertTrue(denied.getMessage().contains("direct Java host I/O"),
                    "class must fail before HostAccess construction: " + className);
        }
    }

    @Test
    void publicNestedHostClassesCannotBypassEnclosingTypeDenials() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.THREAD_CREATE,
                IsolatePolicy.Capability.FILESYSTEM_READ);
        // A direct allowlist entry for Outer$Nested must never bypass a
        // prohibition on Outer. These are public JDK binary class names.
        for (String nested : new String[]{
                "java.util.ResourceBundle$Control",
                "java.util.ServiceLoader$Provider",
                "java.lang.System$LoggerFinder",
                "java.lang.Thread$Builder",
                "java.lang.Thread$Builder$OfVirtual",
                "java.lang.ProcessHandle$Info",
                "java.lang.Runtime$Version",
                "java.lang.ProcessBuilder$Redirect",
                "java.security.KeyStore$Builder"
        }) {
            assertThrows(SecurityException.class,
                    () -> policy.restrictedContextBuilder(
                            ExecutionProfile.serverJit(), Set.of(nested)),
                    "nested blocked authority admitted: " + nested);
        }
        // A binary-name dollar sign is not inherently dangerous: nested
        // classes of safe public data types remain individually allowlistable.
        assertDoesNotThrow(() -> policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(),
                Set.of("java.util.Map$Entry", "java.util.AbstractMap$SimpleEntry")));
    }

    @Test
    void ordinaryNonIoHostClassCanStillUseExplicitInteropAllowlist() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP);

        assertDoesNotThrow(() -> policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(),
                Set.of("java.util.ArrayList", "java.security.MessageDigest")));
    }
}
