#!/bin/bash
# Backend derleme/test izleyicisi.
# Çift tıklayın ve pencereyi açık bırakın: .run-tests dosyası oluştuğunda
# içindeki Maven argümanlarıyla derleme/test çalışır, çıktı test-output.log'a yazılır.
cd "$(dirname "$0")" || exit 1
set -a; [ -f .env ] && . ./.env; set +a
echo "Backend test izleyicisi çalışıyor. Durdurmak için Ctrl+C."
run() {
  echo "$(date '+%H:%M:%S') çalışıyor: mvnw $*"
  docker compose run --rm --no-deps -T \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    -e TESTCONTAINERS_RYUK_DISABLED=true \
    backend ./mvnw -B "$@" > test-output.log 2>&1
  echo "EXIT=$?" >> test-output.log
  echo "$(date '+%H:%M:%S') bitti → test-output.log ($(grep -c . test-output.log) satır)"
}
echo "-o -DskipTests test-compile" > .run-tests
while true; do
  if [ -f .run-tests ]; then
    args=$(cat .run-tests); rm -f .run-tests
    # shellcheck disable=SC2086
    run $args
  fi
  sleep 3
done
