#!/bin/bash
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

echo -e "${BOLD}${UNDERLINE}Zipaligning..${NC}"
if command -v zipalign &> /dev/null; then
  zipalign -p -f 4 ../build-output/VTLite.apk ../build-output/VTLite.apk_aligned
  check_result
  mv ../build-output/VTLite.apk_aligned ../build-output/VTLite.apk
else
  echo -e "${RED}Zipalign не найден, выравнивание пропущено${NC}"
fi

echo -e "${BOLD}${UNDERLINE}Подписывание апк...${NC}"
cd ../scripts
./apksigner sign --ks debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android ../build-output/VTLite.apk
check_result

cp ../RELEASE_NOTES.md ../build-output/RELEASE_NOTES.md

echo -e "${BOLD}VTosters Lite успешно собран! APK находится в папке build-output${NC}"