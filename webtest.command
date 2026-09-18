#!/bin/bash
# Web tarafı için test/derleme izleyicisi (Faz 10).
# Finder'da çift tıklayın; açık bıraktığınız sürece `.run-web` dosyasına yazılan
# komutları web konteynerinde çalıştırır ve çıktıyı web-output.log dosyasına yazar.
#
# Konteyner kendi node_modules'unu kullanır (-v /app/node_modules): Mac'teki
# node_modules'a dokunulmaz, iki platform birbirini bozmaz.
cd "$(dirname "$0")" || exit 1
[ -f .env ] || cp .env.example .env
set -a; . ./.env; set +a
echo "Web izleyicisi hazır. Bu pencereyi açık bırakın. (Çıkmak için Ctrl+C)"

run() {
  docker compose run --rm --no-deps -T \
    -v /app/node_modules \
    -w /app frontend sh -lc "npm ci --no-audit --no-fund && $*" > web-output.log 2>&1
  echo "EXIT=$?" >> web-output.log
  echo "$(date '+%H:%M:%S')  bitti: $*"
}

while true; do
  if [ -f .run-web ]; then
    cmd=$(cat .run-web)
    rm -f .run-web
    echo "$(date '+%H:%M:%S')  çalışıyor: $cmd"
    run "$cmd"
  fi
  sleep 2
done
