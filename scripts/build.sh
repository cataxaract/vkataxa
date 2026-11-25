#!/bin/bash
set -euo pipefail
RED='\033[31m'
GREEN='\033[92m'
NC='\033[0m'
BOLD='\033[1m'
UNDERLINE='\033[4m'

check_result() {
    if [ $? -eq 0 ]; then
    	echo -e "${GREEN}Успех!${NC}\n"
    else
    	echo -e "${RED}Провал.${NC}\n"
    	exit 1
    fi
}

echo -e "${BOLD}${UNDERLINE}Сборка apk..${NC}"
cd smali/
mkdir -p ../build-output
java -Xmx4g -Djava.awt.headless=true -jar ../scripts/apktool.jar b . -o ../build-output/VTLite.apk --use-aapt2 -p ../scripts/framework/
check_result
cp ../build-output/VTLite.apk ../build-output/VTLite-base.apk

echo -e "${BOLD}${UNDERLINE}Zipaligning..${NC}"
ZIPALIGN="$(command -v zipalign || true)"
if [ -z "$ZIPALIGN" ]; then
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$root" ] && [ -d "$root/build-tools" ] || continue
    bt="$(ls -1 "$root/build-tools" | sort -V | tail -1)"
    [ -x "$root/build-tools/$bt/zipalign" ] && ZIPALIGN="$root/build-tools/$bt/zipalign"
  done
fi
if [ -z "$ZIPALIGN" ]; then
  echo -e "${RED}zipalign не найден (установи build-tools или задай ANDROID_HOME)${NC}"
  exit 1
fi
"$ZIPALIGN" -p -f 4 ../build-output/VTLite.apk ../build-output/VTLite.apk_aligned
check_result
mv ../build-output/VTLite.apk_aligned ../build-output/VTLite.apk

echo -e "${BOLD}${UNDERLINE}Подписывание апк...${NC}"
cd ../scripts
./apksigner sign --ks debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android ../build-output/VTLite.apk
check_result
./apksigner verify ../build-output/VTLite.apk
check_result
test -f ../build-output/VTLite.apk

cp ../RELEASE_NOTES.md ../build-output/RELEASE_NOTES.md

echo -e "${BOLD}VTosters Lite успешно собран! APK находится в папке build-output${NC}"