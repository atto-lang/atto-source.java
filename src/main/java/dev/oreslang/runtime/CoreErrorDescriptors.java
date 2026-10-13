package dev.oreslang.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler/runtime-owned v1 std/error catalog. This is a generated trust
 * anchor, NOT a guest-provided class name, code() method or Serializable
 * object. Keep in exact parity with contracts/v1/error-catalog.json.
 */
public final class CoreErrorDescriptors {
    public static final int SCHEMA_VERSION = 1;

    /** Runtime-owned value type; guest source cannot allocate a descriptor. */
    public static final class Descriptor {
        private final String name;
        private final int code;
        private final boolean abstractType;

        private Descriptor(String name, int code, boolean abstractType) {
            this.name = name;
            this.code = code;
            this.abstractType = abstractType;
        }

        public String name() { return name; }
        public int code() { return code; }
        public boolean abstractType() { return abstractType; }
    }

    private static final Map<String, Descriptor> BY_NAME;
    private static final Map<Integer, Descriptor> BY_CODE;

    static {
        Map<String, Descriptor> byName = new LinkedHashMap<>();
        Map<Integer, Descriptor> byCode = new LinkedHashMap<>();
        register(byName, byCode, "Error", 0, false);
        register(byName, byCode, "ArgumentError", 1000, true);
        register(byName, byCode, "InvalidArgumentError", 1001, false);
        register(byName, byCode, "MissingArgumentError", 1002, false);
        register(byName, byCode, "UnexpectedArgumentError", 1003, false);
        register(byName, byCode, "ArityError", 1004, false);
        register(byName, byCode, "TypeMismatchError", 1005, false);
        register(byName, byCode, "CastError", 1006, false);
        register(byName, byCode, "RangeError", 1007, false);
        register(byName, byCode, "IndexError", 1008, false);
        register(byName, byCode, "StateError", 2000, true);
        register(byName, byCode, "InvalidStateError", 2001, false);
        register(byName, byCode, "AlreadyInitializedError", 2002, false);
        register(byName, byCode, "NotInitializedError", 2003, false);
        register(byName, byCode, "ClosedError", 2004, false);
        register(byName, byCode, "ReadOnlyError", 2005, false);
        register(byName, byCode, "ImmutableError", 2006, false);
        register(byName, byCode, "ConcurrentModificationError", 2007, false);
        register(byName, byCode, "StaleHandleError", 2008, false);
        register(byName, byCode, "IOError", 3000, true);
        register(byName, byCode, "FileSystemError", 3090, true);
        register(byName, byCode, "FileNotFoundError", 3001, false);
        register(byName, byCode, "FileAlreadyExistsError", 3002, false);
        register(byName, byCode, "NotDirectoryError", 3003, false);
        register(byName, byCode, "IsDirectoryError", 3004, false);
        register(byName, byCode, "DirectoryNotEmptyError", 3005, false);
        register(byName, byCode, "ReadOnlyFileSystemError", 3006, false);
        register(byName, byCode, "EndOfFileError", 3007, false);
        register(byName, byCode, "BrokenPipeError", 3008, false);
        register(byName, byCode, "NetworkError", 4000, true);
        register(byName, byCode, "DnsResolutionError", 4001, false);
        register(byName, byCode, "ConnectionRefusedError", 4002, false);
        register(byName, byCode, "ConnectionResetError", 4003, false);
        register(byName, byCode, "ConnectionAbortedError", 4004, false);
        register(byName, byCode, "HostUnreachableError", 4005, false);
        register(byName, byCode, "NetworkUnreachableError", 4006, false);
        register(byName, byCode, "TlsHandshakeError", 4007, false);
        register(byName, byCode, "ProtocolError", 4008, false);
        register(byName, byCode, "TimingError", 5000, true);
        register(byName, byCode, "TimeoutError", 5001, false);
        register(byName, byCode, "DeadlineExceededError", 5002, false);
        register(byName, byCode, "WouldBlockError", 5003, false);
        register(byName, byCode, "RetryLimitExceededError", 5004, false);
        register(byName, byCode, "BackpressureError", 5005, false);
        register(byName, byCode, "RateLimitedError", 5006, false);
        register(byName, byCode, "TimerConfigurationError", 5007, false);
        register(byName, byCode, "ClockError", 5008, false);
        register(byName, byCode, "DataError", 6000, true);
        register(byName, byCode, "ParseError", 6001, false);
        register(byName, byCode, "SyntaxError", 6002, false);
        register(byName, byCode, "EncodingError", 6003, false);
        register(byName, byCode, "DecodingError", 6004, false);
        register(byName, byCode, "ValidationError", 6005, false);
        register(byName, byCode, "SerializationError", 6006, false);
        register(byName, byCode, "DeserializationError", 6007, false);
        register(byName, byCode, "SchemaMismatchError", 6008, false);
        register(byName, byCode, "SecurityError", 7000, true);
        register(byName, byCode, "AccessDeniedError", 7001, false);
        register(byName, byCode, "AuthenticationError", 7002, false);
        register(byName, byCode, "AuthorizationError", 7003, false);
        register(byName, byCode, "CapabilityDeniedError", 7004, false);
        register(byName, byCode, "SignatureVerificationError", 7005, false);
        register(byName, byCode, "IntegrityError", 7006, false);
        register(byName, byCode, "SandboxViolationError", 7007, false);
        register(byName, byCode, "CertificateError", 7008, false);
        register(byName, byCode, "OperationError", 8000, true);
        register(byName, byCode, "ArithmeticError", 8001, false);
        register(byName, byCode, "DivisionByZeroError", 8002, false);
        register(byName, byCode, "IntegerOverflowError", 8003, false);
        register(byName, byCode, "NumericConversionError", 8004, false);
        register(byName, byCode, "UnsupportedOperationError", 8005, false);
        register(byName, byCode, "VersionMismatchError", 8006, false);
        register(byName, byCode, "FeatureUnavailableError", 8007, false);
        register(byName, byCode, "DependencyFailureError", 8008, false);
        register(byName, byCode, "KeyNotFoundError", 6009, false);
        register(byName, byCode, "DuplicateKeyError", 6010, false);
        register(byName, byCode, "InvalidFormatError", 6011, false);
        register(byName, byCode, "EmptyCollectionError", 2009, false);
        register(byName, byCode, "IteratorExhaustedError", 2010, false);
        register(byName, byCode, "DeadlockDetectedError", 2011, false);
        register(byName, byCode, "ProcessNotFoundError", 2012, false);
        register(byName, byCode, "MailboxFullError", 2013, false);
        register(byName, byCode, "NotImplementedError", 8009, false);
        register(byName, byCode, "ResourceExhaustedError", 8010, false);
        register(byName, byCode, "ActorError", 9000, true);
        register(byName, byCode, "ActorSpawnRejectedError", 9001, false);
        register(byName, byCode, "ActorInitializationError", 9002, false);
        register(byName, byCode, "ActorTerminatedError", 9003, false);
        register(byName, byCode, "ActorMailboxClosedError", 9004, false);
        register(byName, byCode, "ActorMessageRejectedError", 9005, false);
        register(byName, byCode, "ActorProtocolMismatchError", 9006, false);
        register(byName, byCode, "ActorAskTimeoutError", 9007, false);
        register(byName, byCode, "ActorReplyUnavailableError", 9008, false);
        register(byName, byCode, "ActorReferenceExpiredError", 9009, false);
        register(byName, byCode, "ActorNodeUnreachableError", 9010, false);
        register(byName, byCode, "ActorNameConflictError", 9011, false);
        register(byName, byCode, "ActorSpawnQuotaExceededError", 9012, false);
        register(byName, byCode, "ActorSupervisorUnavailableError", 9013, false);
        register(byName, byCode, "ActorOutputClosedError", 9014, false);
        if (byName.size() != 99 || byCode.size() != 99)
            throw new ExceptionInInitializerError("std/error v1 catalog drift");
        BY_NAME = Collections.unmodifiableMap(byName);
        BY_CODE = Collections.unmodifiableMap(byCode);
    }

    private CoreErrorDescriptors() { }

    private static void register(Map<String, Descriptor> byName,
                                 Map<Integer, Descriptor> byCode,
                                 String name, int code, boolean abstractType) {
        Descriptor descriptor = new Descriptor(name, code, abstractType);
        if (byName.putIfAbsent(name, descriptor) != null
                || byCode.putIfAbsent(code, descriptor) != null)
            throw new ExceptionInInitializerError("duplicate std/error identity");
    }

    /**
     * Only known catalog identities are admitted. Names are lookup keys, not
     * trusted identity claims; downstream callers must bind the returned
     * descriptor to a VERIFIED compiler-linked nominal class.
     */
    public static Descriptor requireKnown(String name) {
        Objects.requireNonNull(name, "error type name");
        Descriptor descriptor = BY_NAME.get(name);
        if (descriptor == null) throw new IllegalArgumentException("unrecognized core error type");
        return descriptor;
    }

    public static Descriptor requireKnownCode(int code) {
        Descriptor descriptor = BY_CODE.get(code);
        if (descriptor == null) throw new IllegalArgumentException("unrecognized core error code");
        return descriptor;
    }

    public static int size() { return BY_NAME.size(); }
}
