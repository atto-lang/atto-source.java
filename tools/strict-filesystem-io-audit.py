#!/usr/bin/env python3
"""Fail closed if strict async filesystem I/O regresses to worker/blocking paths."""
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def deny(message: str) -> None:
    print("STRICT_FILESYSTEM_IO=DENIED", file=sys.stderr)
    print("DENIED: " + message, file=sys.stderr)
    raise SystemExit(1)


def text(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        deny("required file missing: " + path)
    return target.read_text(encoding="utf-8")


eval_source = text("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java")
fs_switch_start = eval_source.find("if (receiver instanceof FileSystemFacade fs)")
fs_switch_end = eval_source.find("if (receiver instanceof NetworkFacade network)", fs_switch_start)
if fs_switch_start < 0 or fs_switch_end < 0:
    deny("filesystem member dispatch boundary not found")
fs_switch = eval_source[fs_switch_start:fs_switch_end]
if "context.nativeIo().submit" in fs_switch:
    deny("filesystem async member dispatch reaches worker-offloaded AsyncNativeIo")
for required in (
    'case "read_text_async" -> (Invokable) fs::readTextAsync',
    'case "exists_async" -> (Invokable) fs::existsAsync',
    'case "write_text_async" -> (Invokable) fs::writeTextAsync',
    'case "append_text_async" -> (Invokable) fs::appendTextAsync',
):
    if required not in fs_switch:
        deny("missing strict async dispatch: " + required)

facade_start = eval_source.find("private record FileSystemFacade(OresContext context)")
facade_end = eval_source.find("private record NetworkFacade(OresContext context)", facade_start)
if facade_start < 0 or facade_end < 0:
    deny("filesystem facade boundary not found")
facade = eval_source[facade_start:facade_end]
if "context.nativeIo().submit" in facade:
    deny("filesystem facade reaches blocking-worker adapter")
for required in (
    "context.strictFileIo().readText",
    "context.strictFileIo().exists",
    "context.strictFileIo().writeText",
    "context.strictFileIo().appendText",
    "context.requireStrictPathPermission",
):
    if required not in facade:
        deny("filesystem facade missing strict provider edge: " + required)

provider = text("src/main/java/dev/oreslang/runtime/LinuxUringFileIoProvider.java")
if provider.count("() -> cancelNative(id), () -> { }") < 2:
    deny("read/exists Future cancellation must require native revocation admission")
if "MAX_RESERVED_BYTES = 64L * 1024 * 1024" not in provider:
    deny("strict filesystem provider lacks aggregate native-buffer reservation bound")
if "reserveBytes(MAX_READ_BYTES)" not in provider:
    deny("strict reads do not reserve their full native growth ceiling")
if "reserveBytes(bytes.length)" not in provider:
    deny("strict writes do not reserve their actual UTF-8 payload bytes")
if "releaseBytes(removed.reservedBytes)" not in provider:
    deny("strict submission/close paths do not release byte reservations")
if provider.count("new OresFuture<>(() -> false, () -> { })") < 1:
    deny("strict filesystem mutations must explicitly decline guest cancellation")
for forbidden in (
    "ExecutorService",
    "Executors.",
    "Thread.ofVirtual",
    "AsynchronousFileChannel",
    "Files.read",
    "Files.write",
):
    if forbidden in provider:
        deny("strict provider contains worker/blocking fallback: " + forbidden)

permissions = text("src/main/java/dev/oreslang/runtime/RuntimePermissions.java")
strict_start = permissions.find("public String requireStrictPath(")
strict_end = permissions.find("public boolean allows(", strict_start)
if strict_start < 0 or strict_end < 0:
    deny("strict path permission boundary not found")
strict_permission = permissions[strict_start:strict_end]
for forbidden in ("Files.", "toRealPath", "exists("):
    if forbidden in strict_permission:
        deny("strict runtime path admission performs filesystem I/O: " + forbidden)

native = text("src/main/c/oresuring.c")
for required in (
    "IORING_OP_OPENAT2",
    "IORING_OP_READ",
    "IORING_OP_WRITE",
    "IORING_OP_ASYNC_CANCEL",
    "IORING_OP_CLOSE",
    "RESOLVE_BENEATH",
    "RESOLVE_NO_MAGICLINKS",
    "RESOLVE_NO_SYMLINKS",
):
    if required not in native:
        deny("native provider missing required io_uring primitive: " + required)
if native.count("pthread_create(") != 1:
    deny("strict filesystem provider must own exactly one reactor pthread")
if "ores_retract_last_sqe_locked" not in native:
    deny("failed io_uring submission must retract its unconsumed SQE")
if "if (ctx->closed) op->cancelled = 1;" not in native:
    deny("closed provider must not advance an operation to another I/O stage")


build = text("scripts/build-native-thread.sh")
if "src/main/c/oresuring.c" not in build:
    deny("native build does not link io_uring provider")

metadata = json.loads(text(
    "src/main/resources/META-INF/native-image/dev.oreslang/oreslang/reachability-metadata.json"))
jni = {
    entry.get("type"): entry
    for entry in metadata.get("jni", [])
    if isinstance(entry, dict)
}
entry = jni.get("dev.oreslang.runtime.LinuxUringFileIoProvider")
if not entry:
    deny("Native Image JNI metadata missing io_uring callback owner")
method_names = {
    method.get("name")
    for method in entry.get("methods", [])
    if isinstance(method, dict)
}
if not {"nativeReadCompleted", "nativeExistsCompleted", "nativeWriteCompleted"} <= method_names:
    deny("Native Image JNI callback methods are incomplete")

# This PR is deliberately staged: legacy synchronous filesystem APIs remain
# until their own completion providers exist. Assert the debt explicitly so a
# release cannot accidentally describe this as the global strict boundary.
if 'case "read_text" -> (Invokable) fs::readText' not in fs_switch:
    deny("staged legacy-sync marker changed; update migration/audit together")

print("STRICT_FILESYSTEM_IO=PASS")
print("strict_async_operations=read_text_async,exists_async,write_text_async,append_text_async")
print("legacy_sync_surface=STAGED_NOT_STRICT")
