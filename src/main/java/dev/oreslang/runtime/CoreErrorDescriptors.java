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
        private final String parentName;
        private final int code;
        private final boolean abstractType;

        private Descriptor(String name, String parentName, int code, boolean abstractType) {
            this.name = name;
            this.parentName = parentName;
            this.code = code;
            this.abstractType = abstractType;
        }

        public String name() { return name; }
        /** Canonical direct parent (null only for Error), never guest-derived. */
        public Descriptor parent() { return parentName == null ? null : BY_NAME.get(parentName); }
        /** Nominal ancestry includes the type itself; no structural matching. */
        public boolean isSubtypeOf(Descriptor ancestor) {
            Objects.requireNonNull(ancestor, "ancestor");
            for (Descriptor cursor = this; cursor != null; cursor = cursor.parent()) {
                if (cursor == ancestor) return true;
            }
            return false;
        }
        public int code() { return code; }
        public boolean abstractType() { return abstractType; }
    }

    private static final Map<String, Descriptor> BY_NAME;
    private static final Map<Integer, Descriptor> BY_CODE;

    static {
        Map<String, Descriptor> byName = new LinkedHashMap<>();
        Map<Integer, Descriptor> byCode = new LinkedHashMap<>();
        register(byName, byCode, "Error", null, 0, false);
        register(byName, byCode, "ArgumentError", "Error", 1000, true);
        register(byName, byCode, "InvalidArgumentError", "ArgumentError", 1001, false);
        register(byName, byCode, "MissingArgumentError", "ArgumentError", 1002, false);
        register(byName, byCode, "UnexpectedArgumentError", "ArgumentError", 1003, false);
        register(byName, byCode, "ArityError", "ArgumentError", 1004, false);
        register(byName, byCode, "TypeMismatchError", "ArgumentError", 1005, false);
        register(byName, byCode, "CastError", "ArgumentError", 1006, false);
        register(byName, byCode, "RangeError", "ArgumentError", 1007, false);
        register(byName, byCode, "IndexError", "ArgumentError", 1008, false);
        register(byName, byCode, "StateError", "Error", 2000, true);
        register(byName, byCode, "InvalidStateError", "StateError", 2001, false);
        register(byName, byCode, "AlreadyInitializedError", "StateError", 2002, false);
        register(byName, byCode, "NotInitializedError", "StateError", 2003, false);
        register(byName, byCode, "ClosedError", "StateError", 2004, false);
        register(byName, byCode, "ReadOnlyError", "StateError", 2005, false);
        register(byName, byCode, "ImmutableError", "StateError", 2006, false);
        register(byName, byCode, "ConcurrentModificationError", "StateError", 2007, false);
        register(byName, byCode, "StaleHandleError", "StateError", 2008, false);
        register(byName, byCode, "IOError", "Error", 3000, true);
        register(byName, byCode, "FileSystemError", "IOError", 3090, true);
        register(byName, byCode, "FileNotFoundError", "FileSystemError", 3001, false);
        register(byName, byCode, "FileAlreadyExistsError", "FileSystemError", 3002, false);
        register(byName, byCode, "NotDirectoryError", "FileSystemError", 3003, false);
        register(byName, byCode, "IsDirectoryError", "FileSystemError", 3004, false);
        register(byName, byCode, "DirectoryNotEmptyError", "FileSystemError", 3005, false);
        register(byName, byCode, "ReadOnlyFileSystemError", "FileSystemError", 3006, false);
        register(byName, byCode, "EndOfFileError", "IOError", 3007, false);
        register(byName, byCode, "BrokenPipeError", "IOError", 3008, false);
        register(byName, byCode, "NetworkError", "IOError", 4000, true);
        register(byName, byCode, "DnsResolutionError", "NetworkError", 4001, false);
        register(byName, byCode, "ConnectionRefusedError", "NetworkError", 4002, false);
        register(byName, byCode, "ConnectionResetError", "NetworkError", 4003, false);
        register(byName, byCode, "ConnectionAbortedError", "NetworkError", 4004, false);
        register(byName, byCode, "HostUnreachableError", "NetworkError", 4005, false);
        register(byName, byCode, "NetworkUnreachableError", "NetworkError", 4006, false);
        register(byName, byCode, "TlsHandshakeError", "NetworkError", 4007, false);
        register(byName, byCode, "ProtocolError", "NetworkError", 4008, false);
        register(byName, byCode, "TimingError", "Error", 5000, true);
        register(byName, byCode, "TimeoutError", "TimingError", 5001, false);
        register(byName, byCode, "DeadlineExceededError", "TimingError", 5002, false);
        register(byName, byCode, "WouldBlockError", "TimingError", 5003, false);
        register(byName, byCode, "RetryLimitExceededError", "TimingError", 5004, false);
        register(byName, byCode, "BackpressureError", "TimingError", 5005, false);
        register(byName, byCode, "RateLimitedError", "TimingError", 5006, false);
        register(byName, byCode, "TimerConfigurationError", "TimingError", 5007, false);
        register(byName, byCode, "ClockError", "TimingError", 5008, false);
        register(byName, byCode, "DataError", "Error", 6000, true);
        register(byName, byCode, "ParseError", "DataError", 6001, false);
        register(byName, byCode, "SyntaxError", "DataError", 6002, false);
        register(byName, byCode, "EncodingError", "DataError", 6003, false);
        register(byName, byCode, "DecodingError", "DataError", 6004, false);
        register(byName, byCode, "ValidationError", "DataError", 6005, false);
        register(byName, byCode, "SerializationError", "DataError", 6006, false);
        register(byName, byCode, "DeserializationError", "DataError", 6007, false);
        register(byName, byCode, "SchemaMismatchError", "DataError", 6008, false);
        register(byName, byCode, "SecurityError", "Error", 7000, true);
        register(byName, byCode, "AccessDeniedError", "SecurityError", 7001, false);
        register(byName, byCode, "AuthenticationError", "SecurityError", 7002, false);
        register(byName, byCode, "AuthorizationError", "SecurityError", 7003, false);
        register(byName, byCode, "CapabilityDeniedError", "SecurityError", 7004, false);
        register(byName, byCode, "SignatureVerificationError", "SecurityError", 7005, false);
        register(byName, byCode, "IntegrityError", "SecurityError", 7006, false);
        register(byName, byCode, "SandboxViolationError", "SecurityError", 7007, false);
        register(byName, byCode, "CertificateError", "SecurityError", 7008, false);
        register(byName, byCode, "OperationError", "Error", 8000, true);
        register(byName, byCode, "ArithmeticError", "OperationError", 8001, false);
        register(byName, byCode, "DivisionByZeroError", "OperationError", 8002, false);
        register(byName, byCode, "IntegerOverflowError", "OperationError", 8003, false);
        register(byName, byCode, "NumericConversionError", "OperationError", 8004, false);
        register(byName, byCode, "UnsupportedOperationError", "OperationError", 8005, false);
        register(byName, byCode, "VersionMismatchError", "OperationError", 8006, false);
        register(byName, byCode, "FeatureUnavailableError", "OperationError", 8007, false);
        register(byName, byCode, "DependencyFailureError", "OperationError", 8008, false);
        register(byName, byCode, "KeyNotFoundError", "DataError", 6009, false);
        register(byName, byCode, "DuplicateKeyError", "DataError", 6010, false);
        register(byName, byCode, "InvalidFormatError", "DataError", 6011, false);
        register(byName, byCode, "EmptyCollectionError", "StateError", 2009, false);
        register(byName, byCode, "IteratorExhaustedError", "StateError", 2010, false);
        register(byName, byCode, "DeadlockDetectedError", "StateError", 2011, false);
        register(byName, byCode, "ProcessNotFoundError", "StateError", 2012, false);
        register(byName, byCode, "MailboxFullError", "StateError", 2013, false);
        register(byName, byCode, "NotImplementedError", "OperationError", 8009, false);
        register(byName, byCode, "ResourceExhaustedError", "OperationError", 8010, false);
        register(byName, byCode, "ActorError", "Error", 9000, true);
        register(byName, byCode, "ActorSpawnRejectedError", "ActorError", 9001, false);
        register(byName, byCode, "ActorInitializationError", "ActorError", 9002, false);
        register(byName, byCode, "ActorTerminatedError", "ActorError", 9003, false);
        register(byName, byCode, "ActorMailboxClosedError", "ActorError", 9004, false);
        register(byName, byCode, "ActorMessageRejectedError", "ActorError", 9005, false);
        register(byName, byCode, "ActorProtocolMismatchError", "ActorError", 9006, false);
        register(byName, byCode, "ActorAskTimeoutError", "ActorError", 9007, false);
        register(byName, byCode, "ActorReplyUnavailableError", "ActorError", 9008, false);
        register(byName, byCode, "ActorReferenceExpiredError", "ActorError", 9009, false);
        register(byName, byCode, "ActorNodeUnreachableError", "ActorError", 9010, false);
        register(byName, byCode, "ActorNameConflictError", "ActorError", 9011, false);
        register(byName, byCode, "ActorSpawnQuotaExceededError", "ActorError", 9012, false);
        register(byName, byCode, "ActorSupervisorUnavailableError", "ActorError", 9013, false);
        register(byName, byCode, "ActorOutputClosedError", "ActorError", 9014, false);
        if (byName.size() != 99 || byCode.size() != 99)
            throw new ExceptionInInitializerError("std/error v1 catalog drift");
        BY_NAME = Collections.unmodifiableMap(byName);
        BY_CODE = Collections.unmodifiableMap(byCode);
    }

    private CoreErrorDescriptors() { }

    private static void register(Map<String, Descriptor> byName,
                                 Map<Integer, Descriptor> byCode,
                                 String name, String parentName, int code, boolean abstractType) {
        if (parentName != null && !byName.containsKey(parentName))
            throw new ExceptionInInitializerError("std/error parent catalog drift");
        Descriptor descriptor = new Descriptor(name, parentName, code, abstractType);
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
