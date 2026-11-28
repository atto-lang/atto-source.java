#!/usr/bin/env bash
set -euo pipefail
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac -d "$work/compiler" src/compiler/readonly/AttoReadonlyReferenceCompiler.java
compile() {
  java -cp "$work/compiler" atto.compiler.readonly.AttoReadonlyReferenceCompiler "$1" --source-sha256 "$(sha256sum "$1" | cut -d' ' -f1)" --out "$2"
}
cat > "$work/guest.atto" <<'EOF'
 // atto-ownership: borrow-default-v1
define class Parcel as pub let int padding = 99; pub let int value = 21; end
fnc get(Parcel p): int { return p.value; }
fnc forward(Parcel p): int { return get(p); }
pub fnc main(): int { let p = new Parcel(); return forward(p) + get(p); }
EOF
sed -i '1s/^ //' "$work/guest.atto"
compile "$work/guest.atto" "$work/AttoReadonlyGuest.java"
javac -d "$work/classes" "$work/AttoReadonlyGuest.java"
test "$(java -cp "$work/classes" AttoReadonlyGuest)" = 42
javap -c -p -classpath "$work/classes" AttoReadonlyGuest > "$work/bytecode"
test "$(grep -c 'getfield' "$work/bytecode")" = 2
grep -F 'Math.addExact' "$work/bytecode"
if grep -Eq 'private static long f[0-9]+\(|invokestatic.*// Method f[0-9]+:' "$work/bytecode"; then exit 1; fi
grep -F 'long r=p.f1;' "$work/AttoReadonlyGuest.java"
grep -F 'r=Math.addExact(r,p.f1);' "$work/AttoReadonlyGuest.java"
native-image --no-fallback -cp "$work/classes" -o "$work/guest" AttoReadonlyGuest
test "$("$work/guest")" = 42
sed 's/value = 21/value = 9223372036854775807/' "$work/guest.atto" > "$work/overflow.atto"
mkdir "$work/overflow"
compile "$work/overflow.atto" "$work/overflow/AttoReadonlyGuest.java"
javac -d "$work/overflow" "$work/overflow/AttoReadonlyGuest.java"
if java -cp "$work/overflow" AttoReadonlyGuest; then exit 1; fi
native-image --no-fallback -cp "$work/overflow" -o "$work/overflow/guest" AttoReadonlyGuest
if "$work/overflow/guest"; then exit 1; fi
for mode in cycle field computed; do
  case "$mode" in
    cycle) sed 's/return get(p);/return forward(p);/' "$work/guest.atto";;
    field) sed 's/return p.value;/return p.unknown;/' "$work/guest.atto";;
    computed) sed 's/return get(p);/return get(new Parcel());/' "$work/guest.atto";;
  esac > "$work/$mode.atto"
  if compile "$work/$mode.atto" "$work/reject.java"; then exit 1; fi
  test ! -e "$work/reject.java"
done
cat > "$work/order.atto" <<'EOF'
// atto-ownership: borrow-default-v1
define class Parcel as pub let int padding = 99; pub let int value = 57; end
fnc h0(Parcel p): int { return p.padding; }
fnc h1(Parcel p): int { return p.value; }
fnc h2(Parcel p): int { return h0(p); }
fnc h3(Parcel p): int { return h2(p); }
fnc h4(Parcel p): int { return h3(p); }
fnc h5(Parcel p): int { return h4(p); }
fnc h6(Parcel p): int { return h5(p); }
fnc h7(Parcel p): int { return h6(p); }
pub fnc main(): int { let p = new Parcel(); return h7(p) - h1(p); }
EOF
mkdir "$work/order"
compile "$work/order.atto" "$work/order/AttoReadonlyGuest.java"
grep -F 'long r=p.f0;' "$work/order/AttoReadonlyGuest.java"
grep -F 'r=Math.subtractExact(r,p.f1);' "$work/order/AttoReadonlyGuest.java"
javac -d "$work/order" "$work/order/AttoReadonlyGuest.java"
test "$(java -cp "$work/order" AttoReadonlyGuest)" = 42
native-image --no-fallback -cp "$work/order" -o "$work/order/guest" AttoReadonlyGuest
test "$("$work/order/guest")" = 42
echo "JVM_AND_NATIVE_IMAGE_READONLY_PASS result=42 overflow_and_negative_controls=pass"
