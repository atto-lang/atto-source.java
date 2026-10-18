#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#if defined(__linux__)
#include <sys/syscall.h>
#endif

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#endif
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

enum {
    ORES_IPC_DATA = 1,
    ORES_IPC_CONTROL = 2,
    ORES_IPC_HIGH_FD = 64,
    ORES_CHILD_BOOTSTRAP_FD = 3,
    ORES_CHILD_DATA_READ_FD = 4,
    ORES_CHILD_DATA_WRITE_FD = 5,
    ORES_CHILD_CONTROL_READ_FD = 6,
    ORES_CHILD_CONTROL_WRITE_FD = 7,
    ORES_CHILD_EXECUTABLE_FD = 8
};

typedef struct {
    pid_t pid;
    int data_write_fd;
    int data_read_fd;
    int control_write_fd;
    int control_read_fd;
    pthread_mutex_t data_write_lock;
    pthread_mutex_t data_read_lock;
    pthread_mutex_t control_write_lock;
    pthread_mutex_t control_read_lock;
} ores_ipc_process;

static void secure_zero(void *ptr, size_t length) {
    volatile unsigned char *p = (volatile unsigned char *)ptr;
    while (length-- != 0) *p++ = 0;
}

static void throw_named(JNIEnv *env, const char *class_name, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, class_name);
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static void throw_illegal_state(JNIEnv *env, const char *message) {
    throw_named(env, "java/lang/IllegalStateException", message);
}

static void throw_illegal_argument(JNIEnv *env, const char *message) {
    throw_named(env, "java/lang/IllegalArgumentException", message);
}

static void throw_unsupported(JNIEnv *env, const char *message) {
    throw_named(env, "java/lang/UnsupportedOperationException", message);
}

static int close_fd(int *fd) {
    if (fd == NULL || *fd < 0) return 0;
    int value = *fd;
    *fd = -1;
    while (close(value) != 0) {
        if (errno != EINTR) return -1;
    }
    return 0;
}

static void close_pipe_pair(int pair[2]) {
    close_fd(&pair[0]);
    close_fd(&pair[1]);
}

static int make_pipe(int pair[2]) {
#if defined(__linux__)
    return pipe2(pair, O_CLOEXEC);
#else
    (void)pair;
    errno = ENOTSUP;
    return -1;
#endif
}

static int duplicate_high_and_close(int fd) {
    int duplicate = fcntl(fd, F_DUPFD_CLOEXEC, ORES_IPC_HIGH_FD);
    int saved = errno;
    close(fd);
    errno = saved;
    return duplicate;
}

static int write_all_plain(int fd, const unsigned char *bytes, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(fd, bytes + offset, length - offset);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (written == 0) {
            errno = EIO;
            return -1;
        }
        offset += (size_t)written;
    }
    return 0;
}

/*
 * A broken pipe must never deliver SIGPIPE to an arbitrary JVM carrier.
 * Block SIGPIPE only on this native-calling thread, consume the signal generated
 * by our failed write if necessary, then restore the caller's original mask.
 */
static int write_all_no_sigpipe(
        int fd,
        const unsigned char *bytes,
        size_t length) {
    sigset_t block;
    sigset_t old;
    sigemptyset(&block);
    sigaddset(&block, SIGPIPE);
    if (pthread_sigmask(SIG_BLOCK, &block, &old) != 0) {
        errno = EIO;
        return -1;
    }

    int result = write_all_plain(fd, bytes, length);
    int saved = errno;
    if (result != 0 && saved == EPIPE && !sigismember(&old, SIGPIPE)) {
        struct timespec zero = {0, 0};
        while (sigtimedwait(&block, NULL, &zero) < 0 && errno == EINTR) {
        }
    }
    (void)pthread_sigmask(SIG_SETMASK, &old, NULL);
    errno = saved;
    return result;
}

static int read_exact_plain(int fd, unsigned char *bytes, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t count = read(fd, bytes + offset, length - offset);
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (count == 0) {
            errno = EPIPE;
            return -1;
        }
        offset += (size_t)count;
    }
    return 0;
}

static int64_t monotonic_millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t)now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
}

/*
 * Returns 0 on success, 1 on timeout before consuming any byte, 2 on timeout
 * after a partial read, and -1 on I/O failure. A partial-frame timeout is not
 * recoverable because retrying would start in the middle of the same frame.
 */
static int read_exact_until(
        int fd,
        unsigned char *bytes,
        size_t length,
        int64_t deadline_millis) {
    size_t offset = 0;
    while (offset < length) {
        int64_t now = monotonic_millis();
        if (now < 0) return -1;
        int64_t remaining = deadline_millis - now;
        if (remaining <= 0) return offset == 0 ? 1 : 2;
        int timeout = remaining > INT32_MAX ? INT32_MAX : (int)remaining;

        struct pollfd descriptor;
        descriptor.fd = fd;
        descriptor.events = POLLIN;
        descriptor.revents = 0;
        int polled;
        do {
            polled = poll(&descriptor, 1, timeout);
        } while (polled < 0 && errno == EINTR);
        if (polled == 0) return offset == 0 ? 1 : 2;
        if (polled < 0) return -1;
        if ((descriptor.revents & POLLIN) == 0) {
            errno = EPIPE;
            return -1;
        }

        ssize_t count = read(fd, bytes + offset, length - offset);
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (count == 0) {
            errno = EPIPE;
            return -1;
        }
        offset += (size_t)count;
    }
    return 0;
}

static void put_u32be(unsigned char out[4], uint32_t value) {
    out[0] = (unsigned char)((value >> 24) & 0xffU);
    out[1] = (unsigned char)((value >> 16) & 0xffU);
    out[2] = (unsigned char)((value >> 8) & 0xffU);
    out[3] = (unsigned char)(value & 0xffU);
}

static uint32_t get_u32be(const unsigned char in[4]) {
    return ((uint32_t)in[0] << 24)
            | ((uint32_t)in[1] << 16)
            | ((uint32_t)in[2] << 8)
            | (uint32_t)in[3];
}

#if defined(__linux__)
static int ores_memfd_create(const char *name, unsigned int flags) {
#if defined(SYS_memfd_create)
    return (int)syscall(SYS_memfd_create, name, flags);
#else
    (void)name;
    (void)flags;
    errno = ENOSYS;
    return -1;
#endif
}

/*
 * glibc 2.34+ exposes this GNU extension. A weak declaration makes the native
 * library load on older Linux runtimes; launch then fails closed instead of
 * inheriting unknown descriptors.
 */
extern int posix_spawn_file_actions_addclosefrom_np(
        posix_spawn_file_actions_t *,
        int) __attribute__((weak));

static int make_sealed_executable(
        const unsigned char *artifact,
        size_t artifact_length) {
    int fd = ores_memfd_create(
            "ores-ipc-actor",
            MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) return -1;

    if (write_all_plain(fd, artifact, artifact_length) != 0
            || fchmod(fd, S_IRUSR | S_IXUSR) != 0
            || lseek(fd, 0, SEEK_SET) < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }

    unsigned char *readback =
            (unsigned char *)malloc(artifact_length == 0 ? 1 : artifact_length);
    if (readback == NULL) {
        close(fd);
        errno = ENOMEM;
        return -1;
    }
    int ok = read_exact_plain(fd, readback, artifact_length) == 0
            && memcmp(readback, artifact, artifact_length) == 0;
    secure_zero(readback, artifact_length);
    free(readback);
    if (!ok) {
        int saved = errno == 0 ? EIO : errno;
        close(fd);
        errno = saved;
        return -1;
    }

    const int required_seals =
            F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    if (fcntl(fd, F_ADD_SEALS, required_seals) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    int actual_seals = fcntl(fd, F_GET_SEALS);
    if (actual_seals < 0
            || (actual_seals & required_seals) != required_seals
            || lseek(fd, 0, SEEK_SET) < 0) {
        int saved = errno == 0 ? EPERM : errno;
        close(fd);
        errno = saved;
        return -1;
    }
    return fd;
}

static int add_dup(
        posix_spawn_file_actions_t *actions,
        int source,
        int target) {
    return posix_spawn_file_actions_adddup2(actions, source, target);
}

static void kill_and_reap(pid_t pid) {
    if (pid <= 0) return;
    if (kill(-pid, SIGKILL) != 0 && errno != ESRCH) {
        (void)kill(pid, SIGKILL);
    }
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
    }
}

static int spawn_exact_object(
        int executable_fd,
        int bootstrap_read,
        int data_read,
        int data_write,
        int control_read,
        int control_write,
        pid_t *pid_out) {
    if (posix_spawn_file_actions_addclosefrom_np == NULL) {
        errno = ENOTSUP;
        return -1;
    }

    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attributes;
    int status = posix_spawn_file_actions_init(&actions);
    if (status != 0) {
        errno = status;
        return -1;
    }
    int attributes_initialized = 0;

#define ORES_SPAWN_ACTION(expr) do { \
    status = (expr); \
    if (status != 0) { errno = status; goto fail; } \
} while (0)

    ORES_SPAWN_ACTION(posix_spawn_file_actions_addclose(&actions, STDIN_FILENO));
    ORES_SPAWN_ACTION(posix_spawn_file_actions_addclose(&actions, STDOUT_FILENO));
    ORES_SPAWN_ACTION(posix_spawn_file_actions_addclose(&actions, STDERR_FILENO));

    ORES_SPAWN_ACTION(add_dup(
            &actions, bootstrap_read, ORES_CHILD_BOOTSTRAP_FD));
    ORES_SPAWN_ACTION(add_dup(
            &actions, data_read, ORES_CHILD_DATA_READ_FD));
    ORES_SPAWN_ACTION(add_dup(
            &actions, data_write, ORES_CHILD_DATA_WRITE_FD));
    ORES_SPAWN_ACTION(add_dup(
            &actions, control_read, ORES_CHILD_CONTROL_READ_FD));
    ORES_SPAWN_ACTION(add_dup(
            &actions, control_write, ORES_CHILD_CONTROL_WRITE_FD));
    ORES_SPAWN_ACTION(add_dup(
            &actions, executable_fd, ORES_CHILD_EXECUTABLE_FD));

    /*
     * All source descriptors were moved to >=64. This final action closes every
     * unknown/source descriptor while preserving the explicit 3..8 child
     * contract above.
     */
    ORES_SPAWN_ACTION(
            posix_spawn_file_actions_addclosefrom_np(
                    &actions,
                    ORES_CHILD_EXECUTABLE_FD + 1));

    status = posix_spawnattr_init(&attributes);
    if (status != 0) {
        errno = status;
        goto fail;
    }
    attributes_initialized = 1;

    sigset_t empty_mask;
    sigemptyset(&empty_mask);
    status = posix_spawnattr_setsigmask(&attributes, &empty_mask);
    if (status != 0) {
        errno = status;
        goto fail;
    }

    sigset_t defaults;
    sigemptyset(&defaults);
    for (int signal_number = 1; signal_number < NSIG; signal_number++) {
        if (signal_number != SIGKILL && signal_number != SIGSTOP) {
            (void)sigaddset(&defaults, signal_number);
        }
    }
    status = posix_spawnattr_setsigdefault(&attributes, &defaults);
    if (status != 0) {
        errno = status;
        goto fail;
    }
    status = posix_spawnattr_setpgroup(&attributes, 0);
    if (status != 0) {
        errno = status;
        goto fail;
    }
    status = posix_spawnattr_setflags(
            &attributes,
            POSIX_SPAWN_SETSIGMASK
                    | POSIX_SPAWN_SETSIGDEF
                    | POSIX_SPAWN_SETPGROUP);
    if (status != 0) {
        errno = status;
        goto fail;
    }

    char executable_path[64];
    int chars = snprintf(
            executable_path,
            sizeof executable_path,
            "/proc/self/fd/%d",
            ORES_CHILD_EXECUTABLE_FD);
    if (chars <= 0 || (size_t)chars >= sizeof executable_path) {
        errno = EOVERFLOW;
        goto fail;
    }
    char *argv[] = {(char *)"ores-ipc-actor", NULL};
    char *empty_environment[] = {NULL};

    status = posix_spawn(
            pid_out,
            executable_path,
            &actions,
            &attributes,
            argv,
            empty_environment);
    if (status != 0) {
        errno = status;
        goto fail;
    }

    posix_spawnattr_destroy(&attributes);
    posix_spawn_file_actions_destroy(&actions);
    return 0;

fail:
    if (attributes_initialized) posix_spawnattr_destroy(&attributes);
    posix_spawn_file_actions_destroy(&actions);
    return -1;

#undef ORES_SPAWN_ACTION
}

static int initialize_process_locks(ores_ipc_process *process) {
    if (pthread_mutex_init(&process->data_write_lock, NULL) != 0) return -1;
    if (pthread_mutex_init(&process->data_read_lock, NULL) != 0) {
        pthread_mutex_destroy(&process->data_write_lock);
        return -1;
    }
    if (pthread_mutex_init(&process->control_write_lock, NULL) != 0) {
        pthread_mutex_destroy(&process->data_read_lock);
        pthread_mutex_destroy(&process->data_write_lock);
        return -1;
    }
    if (pthread_mutex_init(&process->control_read_lock, NULL) != 0) {
        pthread_mutex_destroy(&process->control_write_lock);
        pthread_mutex_destroy(&process->data_read_lock);
        pthread_mutex_destroy(&process->data_write_lock);
        return -1;
    }
    return 0;
}

static void destroy_process_locks(ores_ipc_process *process) {
    pthread_mutex_destroy(&process->control_read_lock);
    pthread_mutex_destroy(&process->control_write_lock);
    pthread_mutex_destroy(&process->data_read_lock);
    pthread_mutex_destroy(&process->data_write_lock);
}
#endif

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_NativeActorIpcTransportV1_nativeSupported(
        JNIEnv *env,
        jclass cls) {
    (void)env;
    (void)cls;
#if defined(__linux__)
    return posix_spawn_file_actions_addclosefrom_np != NULL
            ? JNI_TRUE
            : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeActorIpcTransportV1_nativeLaunch(
        JNIEnv *env,
        jclass cls,
        jbyteArray artifact_array,
        jbyteArray bootstrap_array) {
    (void)cls;
#if !defined(__linux__)
    (void)artifact_array;
    (void)bootstrap_array;
    throw_unsupported(
            env,
            "exact-object IPC actor process transport is currently Linux-only");
    return 0;
#else
    if (artifact_array == NULL || bootstrap_array == NULL) {
        throw_illegal_argument(env, "artifact/bootstrap bytes cannot be null");
        return 0;
    }
    if (posix_spawn_file_actions_addclosefrom_np == NULL) {
        throw_unsupported(
                env,
                "Linux runtime lacks posix_spawn closefrom descriptor isolation");
        return 0;
    }

    jsize artifact_length = (*env)->GetArrayLength(env, artifact_array);
    jsize bootstrap_length = (*env)->GetArrayLength(env, bootstrap_array);
    if (artifact_length <= 0 || bootstrap_length <= 0) {
        throw_illegal_argument(env, "artifact/bootstrap bytes cannot be empty");
        return 0;
    }

    unsigned char *artifact =
            (unsigned char *)malloc((size_t)artifact_length);
    unsigned char *bootstrap =
            (unsigned char *)malloc((size_t)bootstrap_length);
    if (artifact == NULL || bootstrap == NULL) {
        free(artifact);
        free(bootstrap);
        throw_illegal_state(env, "failed to allocate IPC launch buffers");
        return 0;
    }
    (*env)->GetByteArrayRegion(
            env,
            artifact_array,
            0,
            artifact_length,
            (jbyte *)artifact);
    (*env)->GetByteArrayRegion(
            env,
            bootstrap_array,
            0,
            bootstrap_length,
            (jbyte *)bootstrap);
    if ((*env)->ExceptionCheck(env)) {
        secure_zero(artifact, (size_t)artifact_length);
        secure_zero(bootstrap, (size_t)bootstrap_length);
        free(artifact);
        free(bootstrap);
        return 0;
    }

    int executable_fd = -1;
    int bootstrap_pipe[2] = {-1, -1};
    int data_in[2] = {-1, -1};
    int data_out[2] = {-1, -1};
    int control_in[2] = {-1, -1};
    int control_out[2] = {-1, -1};
    pid_t pid = -1;
    ores_ipc_process *process = NULL;

    executable_fd =
            make_sealed_executable(artifact, (size_t)artifact_length);
    secure_zero(artifact, (size_t)artifact_length);
    free(artifact);
    artifact = NULL;
    if (executable_fd < 0
            || make_pipe(bootstrap_pipe) != 0
            || make_pipe(data_in) != 0
            || make_pipe(data_out) != 0
            || make_pipe(control_in) != 0
            || make_pipe(control_out) != 0) {
        goto fail;
    }

    executable_fd = duplicate_high_and_close(executable_fd);
    bootstrap_pipe[0] = duplicate_high_and_close(bootstrap_pipe[0]);
    bootstrap_pipe[1] = duplicate_high_and_close(bootstrap_pipe[1]);
    data_in[0] = duplicate_high_and_close(data_in[0]);
    data_in[1] = duplicate_high_and_close(data_in[1]);
    data_out[0] = duplicate_high_and_close(data_out[0]);
    data_out[1] = duplicate_high_and_close(data_out[1]);
    control_in[0] = duplicate_high_and_close(control_in[0]);
    control_in[1] = duplicate_high_and_close(control_in[1]);
    control_out[0] = duplicate_high_and_close(control_out[0]);
    control_out[1] = duplicate_high_and_close(control_out[1]);

    if (executable_fd < 0
            || bootstrap_pipe[0] < 0 || bootstrap_pipe[1] < 0
            || data_in[0] < 0 || data_in[1] < 0
            || data_out[0] < 0 || data_out[1] < 0
            || control_in[0] < 0 || control_in[1] < 0
            || control_out[0] < 0 || control_out[1] < 0) {
        goto fail;
    }

    if (spawn_exact_object(
                executable_fd,
                bootstrap_pipe[0],
                data_in[0],
                data_out[1],
                control_in[0],
                control_out[1],
                &pid) != 0) {
        goto fail;
    }

    /* Parent retains only its four transport endpoints + bootstrap writer. */
    close_fd(&executable_fd);
    close_fd(&bootstrap_pipe[0]);
    close_fd(&data_in[0]);
    close_fd(&data_out[1]);
    close_fd(&control_in[0]);
    close_fd(&control_out[1]);

    if (write_all_no_sigpipe(
                bootstrap_pipe[1],
                bootstrap,
                (size_t)bootstrap_length) != 0) {
        goto fail;
    }
    close_fd(&bootstrap_pipe[1]);
    secure_zero(bootstrap, (size_t)bootstrap_length);
    free(bootstrap);
    bootstrap = NULL;

    process = (ores_ipc_process *)calloc(1, sizeof(*process));
    if (process == NULL) {
        errno = ENOMEM;
        goto fail;
    }
    process->pid = pid;
    process->data_write_fd = data_in[1];
    process->data_read_fd = data_out[0];
    process->control_write_fd = control_in[1];
    process->control_read_fd = control_out[0];
    if (initialize_process_locks(process) != 0) {
        free(process);
        process = NULL;
        errno = ENOMEM;
        goto fail;
    }

    data_in[1] = -1;
    data_out[0] = -1;
    control_in[1] = -1;
    control_out[0] = -1;
    pid = -1;
    return (jlong)(intptr_t)process;

fail: {
        int saved = errno == 0 ? EIO : errno;
        if (artifact != NULL) {
            secure_zero(artifact, (size_t)artifact_length);
            free(artifact);
        }
        if (bootstrap != NULL) {
            secure_zero(bootstrap, (size_t)bootstrap_length);
            free(bootstrap);
        }
        close_fd(&executable_fd);
        close_pipe_pair(bootstrap_pipe);
        close_pipe_pair(data_in);
        close_pipe_pair(data_out);
        close_pipe_pair(control_in);
        close_pipe_pair(control_out);
        if (pid > 0) kill_and_reap(pid);

        char message[256];
        snprintf(
                message,
                sizeof message,
                "exact-object IPC process launch failed: errno=%d (%s)",
                saved,
                strerror(saved));
        throw_illegal_state(env, message);
        return 0;
    }
#endif
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeActorIpcTransportV1_nativePid(
        JNIEnv *env,
        jclass cls,
        jlong handle) {
    (void)env;
    (void)cls;
#if defined(__linux__)
    ores_ipc_process *process =
            (ores_ipc_process *)(intptr_t)handle;
    return process == NULL ? 0 : (jlong)process->pid;
#else
    (void)handle;
    return 0;
#endif
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_NativeActorIpcTransportV1_nativeIsAlive(
        JNIEnv *env,
        jclass cls,
        jlong handle) {
    (void)env;
    (void)cls;
#if defined(__linux__)
    ores_ipc_process *process =
            (ores_ipc_process *)(intptr_t)handle;
    if (process == NULL || process->pid <= 0) return JNI_FALSE;
    if (kill(process->pid, 0) == 0 || errno == EPERM) return JNI_TRUE;
    return JNI_FALSE;
#else
    (void)handle;
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeActorIpcTransportV1_nativeWriteFrame(
        JNIEnv *env,
        jclass cls,
        jlong handle,
        jint lane,
        jbyteArray frame_array,
        jint max_bytes) {
    (void)cls;
#if !defined(__linux__)
    (void)handle;
    (void)lane;
    (void)frame_array;
    (void)max_bytes;
    throw_unsupported(env, "IPC actor native transport is unsupported");
#else
    ores_ipc_process *process =
            (ores_ipc_process *)(intptr_t)handle;
    if (process == NULL || frame_array == NULL) {
        throw_illegal_state(env, "IPC process/frame is unavailable");
        return;
    }
    jsize frame_length = (*env)->GetArrayLength(env, frame_array);
    if (frame_length <= 0
            || max_bytes <= 0
            || frame_length > max_bytes) {
        throw_illegal_argument(env, "IPC frame exceeds native transport bound");
        return;
    }

    int fd;
    pthread_mutex_t *lock;
    if (lane == ORES_IPC_DATA) {
        fd = process->data_write_fd;
        lock = &process->data_write_lock;
    } else if (lane == ORES_IPC_CONTROL) {
        fd = process->control_write_fd;
        lock = &process->control_write_lock;
    } else {
        throw_illegal_argument(env, "unknown IPC transport lane");
        return;
    }

    unsigned char *frame =
            (unsigned char *)malloc((size_t)frame_length);
    if (frame == NULL) {
        throw_illegal_state(env, "failed to allocate IPC frame write buffer");
        return;
    }
    (*env)->GetByteArrayRegion(
            env,
            frame_array,
            0,
            frame_length,
            (jbyte *)frame);
    if ((*env)->ExceptionCheck(env)) {
        free(frame);
        return;
    }

    unsigned char prefix[4];
    put_u32be(prefix, (uint32_t)frame_length);
    pthread_mutex_lock(lock);
    int result = fd < 0
            || write_all_no_sigpipe(fd, prefix, sizeof prefix) != 0
            || write_all_no_sigpipe(
                    fd,
                    frame,
                    (size_t)frame_length) != 0
            ? -1
            : 0;
    int saved = errno;
    pthread_mutex_unlock(lock);
    free(frame);
    if (result != 0) {
        char message[192];
        snprintf(
                message,
                sizeof message,
                "IPC frame write failed: errno=%d (%s)",
                saved,
                strerror(saved));
        throw_illegal_state(env, message);
    }
#endif
}

JNIEXPORT jbyteArray JNICALL
Java_dev_oreslang_runtime_NativeActorIpcTransportV1_nativeReadFrame(
        JNIEnv *env,
        jclass cls,
        jlong handle,
        jint lane,
        jint max_bytes,
        jint timeout_millis) {
    (void)cls;
#if !defined(__linux__)
    (void)handle;
    (void)lane;
    (void)max_bytes;
    (void)timeout_millis;
    throw_unsupported(env, "IPC actor native transport is unsupported");
    return NULL;
#else
    ores_ipc_process *process =
            (ores_ipc_process *)(intptr_t)handle;
    if (process == NULL || max_bytes <= 0 || timeout_millis <= 0) {
        throw_illegal_argument(env, "invalid IPC frame read arguments");
        return NULL;
    }

    int fd;
    pthread_mutex_t *lock;
    if (lane == ORES_IPC_DATA) {
        fd = process->data_read_fd;
        lock = &process->data_read_lock;
    } else if (lane == ORES_IPC_CONTROL) {
        fd = process->control_read_fd;
        lock = &process->control_read_lock;
    } else {
        throw_illegal_argument(env, "unknown IPC transport lane");
        return NULL;
    }

    int64_t now = monotonic_millis();
    if (now < 0) {
        throw_illegal_state(env, "monotonic clock unavailable for IPC read");
        return NULL;
    }
    int64_t deadline = now + (int64_t)timeout_millis;

    unsigned char prefix[4];
    pthread_mutex_lock(lock);
    int prefix_result =
            fd < 0 ? -1 : read_exact_until(
                    fd,
                    prefix,
                    sizeof prefix,
                    deadline);
    if (prefix_result != 0) {
        int saved = errno;
        pthread_mutex_unlock(lock);
        if (prefix_result == 1) return NULL;
        if (prefix_result == 2) {
            throw_illegal_state(
                    env,
                    "IPC frame prefix timed out after partial consumption");
            return NULL;
        }
        char message[192];
        snprintf(
                message,
                sizeof message,
                "IPC frame prefix read failed: errno=%d (%s)",
                saved,
                strerror(saved));
        throw_illegal_state(env, message);
        return NULL;
    }

    uint32_t frame_length = get_u32be(prefix);
    if (frame_length == 0U || frame_length > (uint32_t)max_bytes) {
        pthread_mutex_unlock(lock);
        throw_illegal_state(
                env,
                "IPC peer declared a frame outside native transport bounds");
        return NULL;
    }

    unsigned char *frame =
            (unsigned char *)malloc((size_t)frame_length);
    if (frame == NULL) {
        pthread_mutex_unlock(lock);
        throw_illegal_state(env, "failed to allocate IPC frame read buffer");
        return NULL;
    }
    int body_result = read_exact_until(
            fd,
            frame,
            (size_t)frame_length,
            deadline);
    int saved = errno;
    pthread_mutex_unlock(lock);
    if (body_result != 0) {
        free(frame);
        if (body_result == 1 || body_result == 2) {
            throw_illegal_state(
                    env,
                    "IPC frame body timed out after length admission");
            return NULL;
        }
        char message[192];
        snprintf(
                message,
                sizeof message,
                "IPC frame body read failed: errno=%d (%s)",
                saved,
                strerror(saved));
        throw_illegal_state(env, message);
        return NULL;
    }

    jbyteArray result =
            (*env)->NewByteArray(env, (jsize)frame_length);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(
                env,
                result,
                0,
                (jsize)frame_length,
                (jbyte *)frame);
    }
    free(frame);
    return result;
#endif
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeActorIpcTransportV1_nativeClose(
        JNIEnv *env,
        jclass cls,
        jlong handle) {
    (void)env;
    (void)cls;
#if defined(__linux__)
    ores_ipc_process *process =
            (ores_ipc_process *)(intptr_t)handle;
    if (process == NULL) return;

    close_fd(&process->data_write_fd);
    close_fd(&process->data_read_fd);
    close_fd(&process->control_write_fd);
    close_fd(&process->control_read_fd);
    pid_t pid = process->pid;
    process->pid = -1;
    kill_and_reap(pid);
    destroy_process_locks(process);
    secure_zero(process, sizeof(*process));
    free(process);
#else
    (void)handle;
#endif
}
