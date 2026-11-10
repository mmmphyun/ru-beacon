#!/usr/bin/env bash
set -e

echo "================================================================================"
echo "[Ru-Beacon] 마인크래프트 데모 서버 자동 번들 셋업 (Paper 1.20.4 + ViaVersion)"
echo "================================================================================"

mkdir -p plugins/RuBeaconPlugin

# 1. Paper 1.20.4 빌드 499 다운로드
if [ ! -f "paper-1.20.4-499.jar" ]; then
    echo "[*] Paper 1.20.4-499 다운로드 중..."
    curl -o paper-1.20.4-499.jar "https://api.papermc.io/v2/projects/paper/versions/1.20.4/builds/499/downloads/paper-1.20.4-499.jar"
fi

# 2. ViaVersion 5.x 다운로드 (1.20.4-1.21.x 호환)
if [ ! -f "plugins/ViaVersion.jar" ]; then
    echo "[*] ViaVersion 다운로드 중..."
    curl -L -o "plugins/ViaVersion.jar" "https://hangarcdn.papermc.io/plugins/ViaVersion/ViaVersion/versions/5.2.1/PAPER/ViaVersion-5.2.1.jar"
fi

# 3. ru-beacon-plugin.jar 복사
if [ -f "../../minecraft-plugin/build/libs/ru-beacon-plugin.jar" ]; then
    echo "[*] ru-beacon-plugin.jar 복사 중..."
    cp -f "../../minecraft-plugin/build/libs/ru-beacon-plugin.jar" "plugins/ru-beacon-plugin.jar"
fi

# 4. eula.txt 동의
echo "eula=true" > eula.txt

# 5. run.sh 생성 (Aikar JVM 최적화 플래그 적용)
cat << 'EOF' > run.sh
#!/usr/bin/env bash
exec java -Xms2G -Xmx3G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
  -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=20 \
  -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 \
  -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 \
  -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \
  -jar paper-1.20.4-499.jar --nogui
EOF
chmod +x run.sh

echo "================================================================================"
echo "[V] 셋업 완료! plugins/RuBeaconPlugin/config.yml 설정 후 ./run.sh 를 실행하십시오."
echo "================================================================================"
