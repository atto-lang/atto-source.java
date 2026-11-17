#!/usr/bin/env bash
# Host bytecode audit only: NOT an AOT binary proof.
set -euo pipefail
classes_root="${1:-target/classes}"
mapfile -d '' class_files < <(
  find "${classes_root}/dev/oreslang/compiler" -maxdepth 1     -type f -name 'AotScalarWireV1*.class' -print0 | sort -z
)
if (( ${#class_files[@]} == 0 )); then
  echo 'AOT_WIRE_NOT_EXECUTED: compile classes before host linkage audit' >&2
  exit 3
fi
tmp="$(mktemp)"
trap 'rm -f -- "${tmp}"' EXIT
for class_file in "${class_files[@]}"; do
  javap -verbose "${class_file}" >> "${tmp}"
done
# Deliberately check direct references, not the JDK's record-generated
# ObjectMethods.bootstrap bootstrap/MethodHandles.Lookup descriptors. Those
# are standard java.lang.Record machinery and need separate Native Image proof.
for forbidden in   'java/lang/reflect/' 'java/beans/Introspector'   'java/lang/reflect/Proxy' 'Class.forName' 'ClassLoader.loadClass'   'Lookup.find' 'LambdaMetafactory' 'sun/misc/Unsafe'   'java/lang/ProcessBuilder'; do
  if grep -Fq -- "${forbidden}" "${tmp}"; then
    echo "AOT_WIRE_HOST_LINKAGE_DENIED: ${forbidden}" >&2
    exit 1
  fi
done
echo "AOT_WIRE_HOST_LINKAGE_PASS classes=${#class_files[@]}"
echo 'AOT_WIRE_NATIVE_IMAGE_NOT_EXECUTED'
