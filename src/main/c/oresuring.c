#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#if defined(__linux__)
#include <linux/io_uring.h>
#include <linux/openat2.h>
#include <sys/mman.h>
#include <sys/syscall.h>

#ifndef MAP_POPULATE
#define MAP_POPULATE 0
#endif

typedef enum {
    ORES_IO_READ = 1,
    ORES_IO_EXISTS = 2,
    ORES_IO_WRITE = 3,
} ores_io_kind;

typedef enum {
    ORES_STAGE_OPEN = 1,
    ORES_STAGE_READ = 2,
    ORES_STAGE_WRITE = 3,
    ORES_STAGE_CLOSE = 4,
} ores_io_stage;

typedef struct ores_io_op ores_io_op;
typedef struct ores_uring_ctx ores_uring_ctx;

struct ores_io_op {
    ores_io_op *next;
    uint64_t id;
    ores_io_kind kind;
    int cancelled;
    int fd;
    int terminal_error;
    int terminal_exists;
    int append;
    unsigned char *path;
    size_t path_len;
    unsigned char *buffer;
    size_t capacity;
    size_t limit;
    size_t used;
    struct open_how how;
    uint64_t current_user_data;
};

struct ores_uring_ctx {
    JavaVM *jvm;
    jobject owner;
    jmethodID read_completed;
    jmethodID exists_completed;
    jmethodID write_completed;

    int ring_fd;
    int root_fd;
    void *sq_ring_ptr;
    size_t sq_ring_sz;
    void *cq_ring_ptr;
    size_t cq_ring_sz;
    void *sqes_ptr;
    size_t sqes_sz;

    unsigned *sq_head;
    unsigned *sq_tail;
    unsigned *sq_ring_mask;
    unsigned *sq_ring_entries;
    unsigned *sq_array;
    struct io_uring_sqe *sqes;

    unsigned *cq_head;
    unsigned *cq_tail;
    unsigned *cq_ring_mask;
    struct io_uring_cqe *cqes;

    pthread_mutex_t mutex;
    pthread_cond_t ready;
    pthread_t reactor;
    int reactor_started;
    int reactor_ready;
    int reactor_attach_failed;
    int closed;
    ores_io_op *ops;
};

typedef struct {
    int terminal;
    ores_io_kind kind;
    uint64_t id;
    int error;
    int exists;
    unsigned char *bytes;
    size_t byte_count;
} ores_completion;

#define ORES_UD_TAG_MASK UINT64_C(7)
#define ORES_UD(op, stage) (((uint64_t)(uintptr_t)(op)) | ((uint64_t)(stage) & ORES_UD_TAG_MASK))
#define ORES_UD_OP(value) ((ores_io_op *)(uintptr_t)((value) & ~ORES_UD_TAG_MASK))
#define ORES_UD_STAGE(value) ((ores_io_stage)((value) & ORES_UD_TAG_MASK))

static int ores_enter(int fd, unsigned to_submit, unsigned min_complete, unsigned flags) {
    return (int)syscall(__NR_io_uring_enter, fd, to_submit, min_complete,
            flags, NULL, 0);
}

static void ores_unmap(ores_uring_ctx *ctx) {
    if (ctx->sqes_ptr != NULL && ctx->sqes_ptr != MAP_FAILED)
        munmap(ctx->sqes_ptr, ctx->sqes_sz);
    if (ctx->cq_ring_ptr != NULL && ctx->cq_ring_ptr != MAP_FAILED
            && ctx->cq_ring_ptr != ctx->sq_ring_ptr)
        munmap(ctx->cq_ring_ptr, ctx->cq_ring_sz);
    if (ctx->sq_ring_ptr != NULL && ctx->sq_ring_ptr != MAP_FAILED)
        munmap(ctx->sq_ring_ptr, ctx->sq_ring_sz);
    if (ctx->ring_fd >= 0) close(ctx->ring_fd);
    if (ctx->root_fd >= 0) close(ctx->root_fd);
}

static int ores_setup_ring(ores_uring_ctx *ctx, unsigned entries) {
    struct io_uring_params params;
    memset(&params, 0, sizeof(params));
    int fd = (int)syscall(__NR_io_uring_setup, entries, &params);
    if (fd < 0) return -errno;
    ctx->ring_fd = fd;

    size_t sq_ring_sz = params.sq_off.array + params.sq_entries * sizeof(unsigned);
    size_t cq_ring_sz = params.cq_off.cqes + params.cq_entries * sizeof(struct io_uring_cqe);
    if (params.features & IORING_FEAT_SINGLE_MMAP) {
        if (cq_ring_sz > sq_ring_sz) sq_ring_sz = cq_ring_sz;
        cq_ring_sz = sq_ring_sz;
    }
    ctx->sq_ring_sz = sq_ring_sz;
    ctx->cq_ring_sz = cq_ring_sz;
    ctx->sqes_sz = params.sq_entries * sizeof(struct io_uring_sqe);

    ctx->sq_ring_ptr = mmap(NULL, sq_ring_sz, PROT_READ | PROT_WRITE,
            MAP_SHARED | MAP_POPULATE, fd, IORING_OFF_SQ_RING);
    if (ctx->sq_ring_ptr == MAP_FAILED) return -errno;

    if (params.features & IORING_FEAT_SINGLE_MMAP) {
        ctx->cq_ring_ptr = ctx->sq_ring_ptr;
    } else {
        ctx->cq_ring_ptr = mmap(NULL, cq_ring_sz, PROT_READ | PROT_WRITE,
                MAP_SHARED | MAP_POPULATE, fd, IORING_OFF_CQ_RING);
        if (ctx->cq_ring_ptr == MAP_FAILED) return -errno;
    }

    ctx->sqes_ptr = mmap(NULL, ctx->sqes_sz, PROT_READ | PROT_WRITE,
            MAP_SHARED | MAP_POPULATE, fd, IORING_OFF_SQES);
    if (ctx->sqes_ptr == MAP_FAILED) return -errno;

    ctx->sq_head = (unsigned *)((char *)ctx->sq_ring_ptr + params.sq_off.head);
    ctx->sq_tail = (unsigned *)((char *)ctx->sq_ring_ptr + params.sq_off.tail);
    ctx->sq_ring_mask = (unsigned *)((char *)ctx->sq_ring_ptr + params.sq_off.ring_mask);
    ctx->sq_ring_entries = (unsigned *)((char *)ctx->sq_ring_ptr + params.sq_off.ring_entries);
    ctx->sq_array = (unsigned *)((char *)ctx->sq_ring_ptr + params.sq_off.array);
    ctx->sqes = (struct io_uring_sqe *)ctx->sqes_ptr;

    ctx->cq_head = (unsigned *)((char *)ctx->cq_ring_ptr + params.cq_off.head);
    ctx->cq_tail = (unsigned *)((char *)ctx->cq_ring_ptr + params.cq_off.tail);
    ctx->cq_ring_mask = (unsigned *)((char *)ctx->cq_ring_ptr + params.cq_off.ring_mask);
    ctx->cqes = (struct io_uring_cqe *)((char *)ctx->cq_ring_ptr + params.cq_off.cqes);
    return 0;
}

static struct io_uring_sqe *ores_get_sqe_locked(ores_uring_ctx *ctx) {
    unsigned head = __atomic_load_n(ctx->sq_head, __ATOMIC_ACQUIRE);
    unsigned tail = __atomic_load_n(ctx->sq_tail, __ATOMIC_RELAXED);
    unsigned entries = *ctx->sq_ring_entries;
    if (tail - head >= entries) return NULL;
    unsigned index = tail & *ctx->sq_ring_mask;
    struct io_uring_sqe *sqe = &ctx->sqes[index];
    memset(sqe, 0, sizeof(*sqe));
    ctx->sq_array[index] = index;
    __atomic_store_n(ctx->sq_tail, tail + 1, __ATOMIC_RELEASE);
    return sqe;
}

static void ores_retract_last_sqe_locked(ores_uring_ctx *ctx) {
    /*
     * All SQ publishing is serialized by ctx->mutex and every helper submits
     * exactly the SQE it just appended. io_uring_enter(..., to_submit=1)
     * returning an error/zero means that entry was not consumed, so retract
     * it before its user_data owner can be released.
     */
    unsigned tail = __atomic_load_n(ctx->sq_tail, __ATOMIC_RELAXED);
    unsigned head = __atomic_load_n(ctx->sq_head, __ATOMIC_ACQUIRE);
    if (tail > head) {
        __atomic_store_n(ctx->sq_tail, tail - 1, __ATOMIC_RELEASE);
    }
}

static int ores_submit_one_locked(ores_uring_ctx *ctx) {
    int result;
    do {
        result = ores_enter(ctx->ring_fd, 1, 0, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) {
        int error = errno;
        ores_retract_last_sqe_locked(ctx);
        return -error;
    }
    if (result != 1) {
        ores_retract_last_sqe_locked(ctx);
        return -EIO;
    }
    return 0;
}

static int ores_submit_nop_locked(ores_uring_ctx *ctx) {
    struct io_uring_sqe *sqe = ores_get_sqe_locked(ctx);
    if (sqe == NULL) return -EAGAIN;
    sqe->opcode = IORING_OP_NOP;
    sqe->user_data = 0;
    return ores_submit_one_locked(ctx);
}

static int ores_submit_open_locked(ores_uring_ctx *ctx, ores_io_op *op) {
    struct io_uring_sqe *sqe = ores_get_sqe_locked(ctx);
    if (sqe == NULL) return -EAGAIN;
    uint64_t user_data = ORES_UD(op, ORES_STAGE_OPEN);
    sqe->opcode = IORING_OP_OPENAT2;
    sqe->fd = ctx->root_fd;
    sqe->addr = (uint64_t)(uintptr_t)op->path;
    sqe->off = (uint64_t)(uintptr_t)&op->how;
    sqe->len = (uint32_t)sizeof(op->how);
    sqe->user_data = user_data;
    op->current_user_data = user_data;
    int result = ores_submit_one_locked(ctx);
    if (result < 0) op->current_user_data = 0;
    return result;
}

static int ores_submit_read_locked(ores_uring_ctx *ctx, ores_io_op *op) {
    if (op->used >= op->limit) return -EFBIG;
    if (op->used >= op->capacity) {
        size_t next = op->capacity < 64U * 1024U ? 64U * 1024U : op->capacity * 2U;
        if (next > op->limit) next = op->limit;
        unsigned char *grown = (unsigned char *)realloc(op->buffer, next);
        if (grown == NULL) return -ENOMEM;
        op->buffer = grown;
        op->capacity = next;
    }
    struct io_uring_sqe *sqe = ores_get_sqe_locked(ctx);
    if (sqe == NULL) return -EAGAIN;
    uint64_t user_data = ORES_UD(op, ORES_STAGE_READ);
    sqe->opcode = IORING_OP_READ;
    sqe->fd = op->fd;
    sqe->off = op->used;
    sqe->addr = (uint64_t)(uintptr_t)(op->buffer + op->used);
    sqe->len = (uint32_t)(op->capacity - op->used);
    sqe->user_data = user_data;
    op->current_user_data = user_data;
    int result = ores_submit_one_locked(ctx);
    if (result < 0) op->current_user_data = 0;
    return result;
}

static int ores_submit_write_locked(ores_uring_ctx *ctx, ores_io_op *op) {
    if (op->used >= op->capacity) return 1;
    struct io_uring_sqe *sqe = ores_get_sqe_locked(ctx);
    if (sqe == NULL) return -EAGAIN;
    uint64_t user_data = ORES_UD(op, ORES_STAGE_WRITE);
    sqe->opcode = IORING_OP_WRITE;
    sqe->fd = op->fd;
    sqe->off = op->append ? (uint64_t)-1 : op->used;
    sqe->addr = (uint64_t)(uintptr_t)(op->buffer + op->used);
    sqe->len = (uint32_t)(op->capacity - op->used);
    sqe->user_data = user_data;
    op->current_user_data = user_data;
    int result = ores_submit_one_locked(ctx);
    if (result < 0) op->current_user_data = 0;
    return result;
}

static int ores_submit_close_locked(ores_uring_ctx *ctx, ores_io_op *op) {
    if (op->fd < 0) return 0;
    struct io_uring_sqe *sqe = ores_get_sqe_locked(ctx);
    if (sqe == NULL) return -EAGAIN;
    uint64_t user_data = ORES_UD(op, ORES_STAGE_CLOSE);
    sqe->opcode = IORING_OP_CLOSE;
    sqe->fd = op->fd;
    sqe->user_data = user_data;
    op->current_user_data = user_data;
    int result = ores_submit_one_locked(ctx);
    if (result < 0) op->current_user_data = 0;
    return result;
}

static int ores_submit_cancel_locked(ores_uring_ctx *ctx, uint64_t target) {
    if (target == 0) return -ENOENT;
    struct io_uring_sqe *sqe = ores_get_sqe_locked(ctx);
    if (sqe == NULL) return -EAGAIN;
    sqe->opcode = IORING_OP_ASYNC_CANCEL;
    sqe->addr = target;
    sqe->user_data = 0;
    return ores_submit_one_locked(ctx);
}

static void ores_remove_op_locked(ores_uring_ctx *ctx, ores_io_op *op) {
    ores_io_op **cursor = &ctx->ops;
    while (*cursor != NULL) {
        if (*cursor == op) {
            *cursor = op->next;
            op->next = NULL;
            return;
        }
        cursor = &(*cursor)->next;
    }
}

static ores_completion ores_terminal_locked(ores_uring_ctx *ctx, ores_io_op *op) {
    /*
     * Normal descriptors are closed through IORING_OP_CLOSE. If a later SQE
     * cannot be submitted after the fd is already open, do not leak it while
     * terminating the failed operation. This emergency close runs only on the
     * single native reactor/control path; it is not a guest-visible I/O
     * implementation or worker fallback.
     */
    if (op->fd >= 0) {
        int close_result;
        do {
            close_result = close(op->fd);
        } while (close_result < 0 && errno == EINTR);
        if (close_result < 0 && op->terminal_error == 0) {
            op->terminal_error = errno;
        }
        op->fd = -1;
    }
    ores_completion completion;
    memset(&completion, 0, sizeof(completion));
    completion.terminal = 1;
    completion.kind = op->kind;
    completion.id = op->id;
    completion.error = op->cancelled ? ECANCELED : op->terminal_error;
    completion.exists = op->terminal_exists;
    if (op->kind == ORES_IO_READ && completion.error == 0) {
        completion.bytes = op->buffer;
        completion.byte_count = op->used;
        op->buffer = NULL;
    }
    ores_remove_op_locked(ctx, op);
    free(op->path);
    free(op->buffer);
    free(op);
    return completion;
}

static ores_completion ores_handle_cqe_locked(
        ores_uring_ctx *ctx, struct io_uring_cqe *cqe) {
    ores_completion none;
    memset(&none, 0, sizeof(none));
    if (cqe->user_data == 0) return none;

    ores_io_op *op = ORES_UD_OP(cqe->user_data);
    ores_io_stage stage = ORES_UD_STAGE(cqe->user_data);
    op->current_user_data = 0;
    int res = cqe->res;
    if (ctx->closed) op->cancelled = 1;

    if (stage == ORES_STAGE_OPEN) {
        if (res >= 0) op->fd = res;
        if (op->cancelled) {
            op->terminal_error = ECANCELED;
            if (op->fd >= 0) {
                int close_result = ores_submit_close_locked(ctx, op);
                if (close_result == 0) return none;
                op->terminal_error = -close_result;
            }
            return ores_terminal_locked(ctx, op);
        }
        if (res < 0) {
            int error = -res;
            if (op->kind == ORES_IO_EXISTS && error == ENOENT) {
                op->terminal_exists = 0;
                op->terminal_error = 0;
            } else {
                op->terminal_error = error;
            }
            return ores_terminal_locked(ctx, op);
        }

        if (op->kind == ORES_IO_EXISTS) {
            op->terminal_exists = 1;
            int close_result = ores_submit_close_locked(ctx, op);
            if (close_result == 0) return none;
            op->terminal_error = -close_result;
            return ores_terminal_locked(ctx, op);
        }

        if (op->kind == ORES_IO_WRITE) {
            if (op->capacity == 0) {
                int close_result = ores_submit_close_locked(ctx, op);
                if (close_result == 0) return none;
                op->terminal_error = -close_result;
                return ores_terminal_locked(ctx, op);
            }
            int write_result = ores_submit_write_locked(ctx, op);
            if (write_result == 0) return none;
            if (write_result > 0) op->terminal_error = 0;
            else op->terminal_error = -write_result;
            int close_result = ores_submit_close_locked(ctx, op);
            if (close_result == 0) return none;
            if (op->terminal_error == 0) op->terminal_error = -close_result;
            return ores_terminal_locked(ctx, op);
        }

        int read_result = ores_submit_read_locked(ctx, op);
        if (read_result == 0) return none;
        op->terminal_error = -read_result;
        int close_result = ores_submit_close_locked(ctx, op);
        if (close_result == 0) return none;
        return ores_terminal_locked(ctx, op);
    }

    if (stage == ORES_STAGE_READ) {
        if (op->cancelled) {
            op->terminal_error = ECANCELED;
        } else if (res < 0) {
            op->terminal_error = -res;
        } else if (res == 0) {
            op->terminal_error = 0;
        } else {
            op->used += (size_t)res;
            if (op->used >= op->capacity) {
                op->terminal_error = EFBIG;
            } else {
                int read_result = ores_submit_read_locked(ctx, op);
                if (read_result == 0) return none;
                op->terminal_error = -read_result;
            }
        }
        int close_result = ores_submit_close_locked(ctx, op);
        if (close_result == 0) return none;
        if (op->terminal_error == 0) op->terminal_error = -close_result;
        return ores_terminal_locked(ctx, op);
    }

    if (stage == ORES_STAGE_WRITE) {
        if (op->cancelled) {
            op->terminal_error = ECANCELED;
        } else if (res < 0) {
            op->terminal_error = -res;
        } else if (res == 0) {
            op->terminal_error = EIO;
        } else {
            size_t remaining = op->capacity - op->used;
            if ((size_t)res > remaining) {
                op->terminal_error = EIO;
            } else {
                op->used += (size_t)res;
                if (op->used < op->capacity) {
                    int write_result = ores_submit_write_locked(ctx, op);
                    if (write_result == 0) return none;
                    if (write_result < 0) op->terminal_error = -write_result;
                }
            }
        }
        int close_result = ores_submit_close_locked(ctx, op);
        if (close_result == 0) return none;
        if (op->terminal_error == 0) op->terminal_error = -close_result;
        return ores_terminal_locked(ctx, op);
    }

    if (stage == ORES_STAGE_CLOSE) {
        op->fd = -1;
        if (res < 0 && op->terminal_error == 0) op->terminal_error = -res;
        return ores_terminal_locked(ctx, op);
    }

    op->terminal_error = EPROTO;
    return ores_terminal_locked(ctx, op);
}

static void ores_notify_completion(JNIEnv *env, ores_uring_ctx *ctx,
        ores_completion *completion) {
    if (!completion->terminal) return;
    if (completion->kind == ORES_IO_READ) {
        jbyteArray bytes = NULL;
        if (completion->error == 0) {
            if (completion->byte_count > (size_t)INT32_MAX) {
                completion->error = EFBIG;
            } else {
                bytes = (*env)->NewByteArray(env, (jsize)completion->byte_count);
                if (bytes == NULL) {
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    completion->error = ENOMEM;
                } else if (completion->byte_count != 0) {
                    (*env)->SetByteArrayRegion(env, bytes, 0,
                            (jsize)completion->byte_count,
                            (const jbyte *)completion->bytes);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->ExceptionClear(env);
                        (*env)->DeleteLocalRef(env, bytes);
                        bytes = NULL;
                        completion->error = EIO;
                    }
                }
            }
        }
        (*env)->CallVoidMethod(env, ctx->owner, ctx->read_completed,
                (jlong)completion->id, bytes, (jint)completion->error);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        if (bytes != NULL) (*env)->DeleteLocalRef(env, bytes);
    } else if (completion->kind == ORES_IO_EXISTS) {
        (*env)->CallVoidMethod(env, ctx->owner, ctx->exists_completed,
                (jlong)completion->id,
                completion->exists ? JNI_TRUE : JNI_FALSE,
                (jint)completion->error);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
    } else {
        (*env)->CallVoidMethod(env, ctx->owner, ctx->write_completed,
                (jlong)completion->id, (jint)completion->error);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
    }
    free(completion->bytes);
    completion->bytes = NULL;
}

static void ores_free_ctx(JNIEnv *env, ores_uring_ctx *ctx) {
    if (ctx == NULL) return;
    ores_io_op *op = ctx->ops;
    while (op != NULL) {
        ores_io_op *next = op->next;
        if (op->fd >= 0) close(op->fd);
        free(op->path);
        free(op->buffer);
        free(op);
        op = next;
    }
    ctx->ops = NULL;
    if (ctx->owner != NULL) (*env)->DeleteGlobalRef(env, ctx->owner);
    ores_unmap(ctx);
    pthread_cond_destroy(&ctx->ready);
    pthread_mutex_destroy(&ctx->mutex);
    free(ctx);
}

static void *ores_reactor_main(void *raw) {
    ores_uring_ctx *ctx = (ores_uring_ctx *)raw;
    JNIEnv *env = NULL;
    JavaVMAttachArgs attach;
    memset(&attach, 0, sizeof(attach));
    attach.version = JNI_VERSION_1_8;
    attach.name = "ores-io-uring";
    jint attached = (*ctx->jvm)->AttachCurrentThreadAsDaemon(
            ctx->jvm, (void **)&env, &attach);
    pthread_mutex_lock(&ctx->mutex);
    ctx->reactor_ready = 1;
    ctx->reactor_attach_failed = attached != JNI_OK || env == NULL;
    pthread_cond_broadcast(&ctx->ready);
    pthread_mutex_unlock(&ctx->mutex);
    if (attached != JNI_OK || env == NULL) return NULL;

    for (;;) {
        int enter_result;
        do {
            enter_result = ores_enter(ctx->ring_fd, 0, 1, IORING_ENTER_GETEVENTS);
        } while (enter_result < 0 && errno == EINTR);

        for (;;) {
            unsigned head = __atomic_load_n(ctx->cq_head, __ATOMIC_RELAXED);
            unsigned tail = __atomic_load_n(ctx->cq_tail, __ATOMIC_ACQUIRE);
            if (head == tail) break;
            struct io_uring_cqe cqe = ctx->cqes[head & *ctx->cq_ring_mask];
            __atomic_store_n(ctx->cq_head, head + 1, __ATOMIC_RELEASE);

            pthread_mutex_lock(&ctx->mutex);
            ores_completion completion = ores_handle_cqe_locked(ctx, &cqe);
            int should_exit = ctx->closed && ctx->ops == NULL;
            pthread_mutex_unlock(&ctx->mutex);

            ores_notify_completion(env, ctx, &completion);
            if (should_exit) {
                JavaVM *jvm = ctx->jvm;
                ores_free_ctx(env, ctx);
                (*jvm)->DetachCurrentThread(jvm);
                return NULL;
            }
        }

        if (enter_result < 0) {
            pthread_mutex_lock(&ctx->mutex);
            ctx->closed = 1;
            ores_io_op *cursor = ctx->ops;
            while (cursor != NULL) {
                cursor->cancelled = 1;
                cursor = cursor->next;
            }
            int empty = ctx->ops == NULL;
            pthread_mutex_unlock(&ctx->mutex);
            if (empty) {
                JavaVM *jvm = ctx->jvm;
                ores_free_ctx(env, ctx);
                (*jvm)->DetachCurrentThread(jvm);
                return NULL;
            }
        }
    }
}

static int ores_copy_path(JNIEnv *env, jbyteArray encoded, unsigned char **out, size_t *out_len) {
    if (encoded == NULL) return EINVAL;
    jsize length = (*env)->GetArrayLength(env, encoded);
    if (length < 1 || length > 4096) return ENAMETOOLONG;
    unsigned char *bytes = (unsigned char *)calloc((size_t)length + 1, 1);
    if (bytes == NULL) return ENOMEM;
    (*env)->GetByteArrayRegion(env, encoded, 0, length, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        free(bytes);
        return EINVAL;
    }
    if (bytes[0] != '/') {
        free(bytes);
        return EINVAL;
    }
    for (jsize i = 0; i < length; i++) {
        if (bytes[i] == 0) {
            free(bytes);
            return EINVAL;
        }
    }
    size_t relative_len = (size_t)length - 1;
    memmove(bytes, bytes + 1, relative_len);
    if (relative_len == 0) {
        bytes[0] = '.';
        relative_len = 1;
    }
    bytes[relative_len] = 0;
    *out = bytes;
    *out_len = relative_len;
    return 0;
}

static ores_io_op *ores_new_op(JNIEnv *env, uint64_t id, ores_io_kind kind,
        jbyteArray encoded_path, size_t max_bytes, jbyteArray write_bytes,
        int append, int *error) {
    ores_io_op *op = (ores_io_op *)calloc(1, sizeof(*op));
    if (op == NULL) {
        *error = ENOMEM;
        return NULL;
    }
    op->id = id;
    op->kind = kind;
    op->fd = -1;
    *error = ores_copy_path(env, encoded_path, &op->path, &op->path_len);
    if (*error != 0) {
        free(op);
        return NULL;
    }
    memset(&op->how, 0, sizeof(op->how));
    op->how.resolve = RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS;
    if (kind == ORES_IO_READ) {
        op->how.flags = O_RDONLY | O_CLOEXEC;
        if (max_bytes == 0 || max_bytes > 64U * 1024U * 1024U) {
            *error = EINVAL;
            free(op->path);
            free(op);
            return NULL;
        }
        op->limit = max_bytes + 1;
        op->capacity = op->limit < 64U * 1024U ? op->limit : 64U * 1024U;
        op->buffer = (unsigned char *)malloc(op->capacity);
        if (op->buffer == NULL) {
            *error = ENOMEM;
            free(op->path);
            free(op);
            return NULL;
        }
    } else if (kind == ORES_IO_WRITE) {
        if (write_bytes == NULL) {
            *error = EINVAL;
            free(op->path);
            free(op);
            return NULL;
        }
        jsize length = (*env)->GetArrayLength(env, write_bytes);
        if (length < 0 || (size_t)length > 64U * 1024U * 1024U) {
            *error = EFBIG;
            free(op->path);
            free(op);
            return NULL;
        }
        op->capacity = (size_t)length;
        op->limit = op->capacity;
        op->append = append != 0;
        if (op->capacity != 0) {
            op->buffer = (unsigned char *)malloc(op->capacity);
            if (op->buffer == NULL) {
                *error = ENOMEM;
                free(op->path);
                free(op);
                return NULL;
            }
            (*env)->GetByteArrayRegion(env, write_bytes, 0, length,
                    (jbyte *)op->buffer);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                *error = EINVAL;
                free(op->path);
                free(op->buffer);
                free(op);
                return NULL;
            }
        }
        op->how.flags = O_WRONLY | O_CREAT | O_CLOEXEC
                | (op->append ? O_APPEND : O_TRUNC);
        op->how.mode = 0666;
    } else {
        op->how.flags = O_PATH | O_CLOEXEC;
    }
    return op;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeCreate(
        JNIEnv *env, jclass cls, jobject owner, jint entries) {
    (void)cls;
    if (owner == NULL || entries < 8 || entries > 1024) return 0;
    ores_uring_ctx *ctx = (ores_uring_ctx *)calloc(1, sizeof(*ctx));
    if (ctx == NULL) return 0;
    ctx->ring_fd = -1;
    ctx->root_fd = -1;
    if (pthread_mutex_init(&ctx->mutex, NULL) != 0) {
        free(ctx);
        return 0;
    }
    if (pthread_cond_init(&ctx->ready, NULL) != 0) {
        pthread_mutex_destroy(&ctx->mutex);
        free(ctx);
        return 0;
    }

    if ((*env)->GetJavaVM(env, &ctx->jvm) != JNI_OK || ctx->jvm == NULL) {
        ores_unmap(ctx);
        pthread_cond_destroy(&ctx->ready);
        pthread_mutex_destroy(&ctx->mutex);
        free(ctx);
        return 0;
    }
    ctx->owner = (*env)->NewGlobalRef(env, owner);
    if (ctx->owner == NULL) {
        ores_unmap(ctx);
        pthread_cond_destroy(&ctx->ready);
        pthread_mutex_destroy(&ctx->mutex);
        free(ctx);
        return 0;
    }
    jclass owner_class = (*env)->GetObjectClass(env, owner);
    if (owner_class == NULL) {
        ores_free_ctx(env, ctx);
        return 0;
    }
    ctx->read_completed = (*env)->GetMethodID(env, owner_class,
            "nativeReadCompleted", "(J[BI)V");
    ctx->exists_completed = (*env)->GetMethodID(env, owner_class,
            "nativeExistsCompleted", "(JZI)V");
    ctx->write_completed = (*env)->GetMethodID(env, owner_class,
            "nativeWriteCompleted", "(JI)V");
    (*env)->DeleteLocalRef(env, owner_class);
    if (ctx->read_completed == NULL || ctx->exists_completed == NULL
            || ctx->write_completed == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        ores_free_ctx(env, ctx);
        return 0;
    }

    ctx->root_fd = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (ctx->root_fd < 0 || ores_setup_ring(ctx, (unsigned)entries) < 0) {
        ores_free_ctx(env, ctx);
        return 0;
    }

    if (pthread_create(&ctx->reactor, NULL, ores_reactor_main, ctx) != 0) {
        ores_free_ctx(env, ctx);
        return 0;
    }
    ctx->reactor_started = 1;
    pthread_mutex_lock(&ctx->mutex);
    while (!ctx->reactor_ready) pthread_cond_wait(&ctx->ready, &ctx->mutex);
    int attach_failed = ctx->reactor_attach_failed;
    pthread_mutex_unlock(&ctx->mutex);
    if (attach_failed) {
        pthread_join(ctx->reactor, NULL);
        ores_free_ctx(env, ctx);
        return 0;
    }
    pthread_detach(ctx->reactor);
    return (jlong)(intptr_t)ctx;
}

static jboolean ores_submit(JNIEnv *env, jlong handle, jlong id,
        jbyteArray path, ores_io_kind kind, jint max_bytes,
        jbyteArray write_bytes, int append) {
    ores_uring_ctx *ctx = (ores_uring_ctx *)(intptr_t)handle;
    if (ctx == NULL || id <= 0) return JNI_FALSE;
    int error = 0;
    ores_io_op *op = ores_new_op(env, (uint64_t)id, kind, path,
            max_bytes < 0 ? 0U : (size_t)max_bytes,
            write_bytes, append, &error);
    if (op == NULL) return JNI_FALSE;

    pthread_mutex_lock(&ctx->mutex);
    if (ctx->closed) {
        pthread_mutex_unlock(&ctx->mutex);
        free(op->path);
        free(op->buffer);
        free(op);
        return JNI_FALSE;
    }
    for (ores_io_op *cursor = ctx->ops; cursor != NULL; cursor = cursor->next) {
        if (cursor->id == op->id) {
            pthread_mutex_unlock(&ctx->mutex);
            free(op->path);
            free(op->buffer);
            free(op);
            return JNI_FALSE;
        }
    }
    op->next = ctx->ops;
    ctx->ops = op;
    int submit_result = ores_submit_open_locked(ctx, op);
    if (submit_result < 0) ores_remove_op_locked(ctx, op);
    pthread_mutex_unlock(&ctx->mutex);

    if (submit_result < 0) {
        free(op->path);
        free(op->buffer);
        free(op);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeSubmitRead(
        JNIEnv *env, jclass cls, jlong handle, jlong id,
        jbyteArray path, jint max_bytes) {
    (void)cls;
    return ores_submit(env, handle, id, path, ORES_IO_READ, max_bytes,
            NULL, 0);
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeSubmitExists(
        JNIEnv *env, jclass cls, jlong handle, jlong id, jbyteArray path) {
    (void)cls;
    return ores_submit(env, handle, id, path, ORES_IO_EXISTS, 0,
            NULL, 0);
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeSubmitWrite(
        JNIEnv *env, jclass cls, jlong handle, jlong id,
        jbyteArray path, jbyteArray value, jboolean append) {
    (void)cls;
    return ores_submit(env, handle, id, path, ORES_IO_WRITE, 0,
            value, append == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeCancel(
        JNIEnv *env, jclass cls, jlong handle, jlong id) {
    (void)env;
    (void)cls;
    ores_uring_ctx *ctx = (ores_uring_ctx *)(intptr_t)handle;
    if (ctx == NULL || id <= 0) return JNI_FALSE;
    pthread_mutex_lock(&ctx->mutex);
    ores_io_op *match = NULL;
    for (ores_io_op *cursor = ctx->ops; cursor != NULL; cursor = cursor->next) {
        if (cursor->id == (uint64_t)id) {
            match = cursor;
            break;
        }
    }
    if (match == NULL) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }
    int cancel_result = ores_submit_cancel_locked(
            ctx, match->current_user_data);
    if (cancel_result == 0) match->cancelled = 1;
    pthread_mutex_unlock(&ctx->mutex);
    return cancel_result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeClose(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    ores_uring_ctx *ctx = (ores_uring_ctx *)(intptr_t)handle;
    if (ctx == NULL) return;
    pthread_mutex_lock(&ctx->mutex);
    if (!ctx->closed) {
        ctx->closed = 1;
        for (ores_io_op *cursor = ctx->ops; cursor != NULL; cursor = cursor->next) {
            int cancel_result = ores_submit_cancel_locked(
                    ctx, cursor->current_user_data);
            if (cancel_result == 0) cursor->cancelled = 1;
        }
        (void)ores_submit_nop_locked(ctx);
    }
    pthread_mutex_unlock(&ctx->mutex);
}

#else

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeCreate(
        JNIEnv *env, jclass cls, jobject owner, jint entries) {
    (void)env; (void)cls; (void)owner; (void)entries; return 0;
}
JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeSubmitRead(
        JNIEnv *env, jclass cls, jlong handle, jlong id, jbyteArray path, jint max_bytes) {
    (void)env; (void)cls; (void)handle; (void)id; (void)path; (void)max_bytes; return JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeSubmitExists(
        JNIEnv *env, jclass cls, jlong handle, jlong id, jbyteArray path) {
    (void)env; (void)cls; (void)handle; (void)id; (void)path; return JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeSubmitWrite(
        JNIEnv *env, jclass cls, jlong handle, jlong id,
        jbyteArray path, jbyteArray value, jboolean append) {
    (void)env; (void)cls; (void)handle; (void)id;
    (void)path; (void)value; (void)append;
    return JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeCancel(
        JNIEnv *env, jclass cls, jlong handle, jlong id) {
    (void)env; (void)cls; (void)handle; (void)id; return JNI_FALSE;
}
JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_LinuxUringFileIoProvider_nativeClose(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; (void)handle;
}

#endif
