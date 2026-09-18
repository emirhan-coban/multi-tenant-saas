#!/bin/bash
cd "$(dirname "$0")" || exit 1
docker compose --profile app down
echo "Durduruldu (veriler korunur)."; read -r -p "Enter..." _
