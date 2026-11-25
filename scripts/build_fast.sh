#!/bin/bash
# F-00 fast path: replace classesN.dex / version.properties inside the last unsigned APK
# (build-output/VTLite-base.apk) instead of running apktool b. Full build is still required
# after any change under smali/ (sources, resources, manifest) - see docs/BUILD.md.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[fast] F-00 fast path: patch existing APK, no apktool b"

BASE="build-output/VTLite-base.apk"
UNSIGNED="build-output/VTLite-fast-unsigned.apk"
ALIGNED="build-output/VTLite-fast-aligned.apk"
OUT="build-output/VTLite.apk"

if [ ! -f "$BASE" ]; then
    echo "[fast] missing $BASE - run a full build first: ./gradlew buildVTLDev"
    exit 1
fi

if find smali/res smali/apktool.yml smali/AndroidManifest.xml -newer "$BASE" -print -quit 2>/dev/null | grep -q .; then
    echo "[fast] smali resources/manifest are newer than the base APK - run a full build: ./gradlew buildVTLDev"
    exit 1
fi

repl=()
count=0
shopt -s nullglob
for f in app/classes*.dex; do
    count=$((count + 1))
    repl+=("$(basename "$f")" "$f")
done
if [ "$count" -eq 0 ]; then
    echo "[fast] no app/classes*.dex - Gradle exportDex did not run"
    exit 1
fi
if [ -f app/version.properties ]; then
    repl+=("assets/version.properties" "app/version.properties")
fi

echo "[fast] dex entries to replace: $count"
java -Xmx2g scripts/ApkPatch.java "$BASE" "$UNSIGNED" "${repl[@]}"

ZIPALIGN="$(command -v zipalign || true)"
if [ -z "$ZIPALIGN" ]; then
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [ -n "$root" ] && [ -d "$root/build-tools" ] || continue
        candidate="$(ls -1 "$root/build-tools" | sort -V | tail -1)"
        [ -x "$root/build-tools/$candidate/zipalign" ] && ZIPALIGN="$root/build-tools/$candidate/zipalign"
    done
fi
if [ -z "$ZIPALIGN" ]; then
    echo "[fast] zipalign not found (install build-tools or set ANDROID_HOME)"
    exit 1
fi
echo "[fast] zipalign: $ZIPALIGN"
"$ZIPALIGN" -p -f 4 "$UNSIGNED" "$ALIGNED"
rm -f "$UNSIGNED"

java -jar scripts/apksigner.jar sign --ks scripts/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android "$ALIGNED"
mv -f "$ALIGNED" "$OUT"
rm -f "$ALIGNED.idsig"
echo "[fast] OK: $OUT"
