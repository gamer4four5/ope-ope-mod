#!/usr/bin/env bash
# RAMA DE PRUEBA. Lanza la galería de la Mera Mera como el juego real (tarea prodMeraGallery) y guarda las capturas.
# Uso: galeria-prod.sh <nombre> <sin|photon> <dia|noche>
NOMBRE="$1"; PACK_MODE="$2"; HORA="$3"
export DISPLAY=:99
pgrep Xvfb > /dev/null || (Xvfb :99 -screen 0 1280x720x24 > /dev/null 2>&1 &)
sleep 2
set +e
rm -f run/saves/Prueba/session.lock run/screenshots/autotest-mera-*.png
mkdir -p run/shaderpacks run/config "capturas/$NOMBRE"
API=https://api.modrinth.com/v2
PACK=""
if [ "$PACK_MODE" = "photon" ]; then
  if ! ls run/shaderpacks/photon*.zip > /dev/null 2>&1; then
    # el Photon del jugador es photon_v1.4.zip; si Modrinth no lo tiene con ese nombre, el más nuevo para 1.21.1
    URL=$(curl -fsSL "$API/project/photon-shader/version" | jq -r '[.[].files[] | select(.filename == "photon_v1.4.zip")][0].url // empty')
    if [ -z "$URL" ]; then
      URL=$(curl -fsSL "$API/project/photon-shader/version?game_versions=%5B%221.21.1%22%5D" | jq -r '.[0].files | (map(select(.primary)) + .)[0].url')
    fi
    echo "photon: $URL"
    curl -fsSL -o "run/shaderpacks/${URL##*/}" "$URL"
  fi
  PACK=$(basename "$(ls run/shaderpacks/photon*.zip | head -1)")
elif [ "$PACK_MODE" = "complementary" ]; then
  # otro shaderpack muy usado, que sí compila sin tarjeta gráfica: con el Iris nuevo, Photon 1.4 no compila aquí
  # (su shader de cómputo pide más memoria compartida de la que da llvmpipe)
  if [ ! -f run/shaderpacks/Complementary.zip ]; then
    URL=$(curl -fsSL "$API/project/complementary-reimagined/version?game_versions=%5B%221.21.1%22%5D" | jq -r '.[0].files | (map(select(.primary)) + .)[0].url')
    echo "complementary: $URL"
    curl -fsSL -o "run/shaderpacks/Complementary.zip" "$URL"
  fi
  PACK=Complementary.zip
fi
if [ -n "$PACK" ]; then
  printf 'enableShaders=true\nshaderPack=%s\n' "$PACK" > run/config/iris.properties
else
  printf 'enableShaders=false\n' > run/config/iris.properties
fi
ls -la run/shaderpacks
EXTRA=()
[ "$HORA" = "noche" ] && EXTRA+=("-Pnoche=true")
# con Photon el procesador dibuja muy despacio (no hay tarjeta gráfica): más margen
timeout 2400 ./gradlew prodMeraGallery --console=plain "-PprodSodium=$SODIUM" "-PprodIris=$IRIS" "-Pfabric_runtime=$FAPI" "${EXTRA[@]}" > "galeria-$NOMBRE.log" 2>&1
echo "exit=$?"
for f in run/screenshots/autotest-mera-*.png; do
  [ -f "$f" ] || continue
  convert "$f" -quality 88 "capturas/$NOMBRE/$(basename "${f%.png}").jpg" || cp -f "$f" "capturas/$NOMBRE/"
done
ls "capturas/$NOMBRE" | wc -l
grep -E "AUTOTEST galería|AUTOTEST FALLO|AUTOTEST mera|Using shaderpack|Failed to create shader|shaderpack failed|Mera:|Exception|Incompatible|requires|Mixin apply" "galeria-$NOMBRE.log" | head -200 || true
echo "----- últimas líneas -----"
grep -vE "Downloading|Download " "galeria-$NOMBRE.log" | tail -40
rm -f run/config/iris.properties
exit 0
