#!/usr/bin/env bash
# RAMA DE PRUEBA. Lanza la galería de la Mera Mera como el juego real (tarea prodMeraGallery) y guarda las capturas.
# Uso: galeria-prod.sh <nombre> <sin|photon|complementary> <dia|noche> [externo]
# Con "externo" no usa la tarea del proyecto sino prodJarExterno: el jar ya compilado de build/libs y los mods copiados
# a run/mods-prod, que es como se prueba en el PC del jugador el jar exacto que se va a instalar.
NOMBRE="$1"; PACK_MODE="$2"; HORA="$3"; MODO="$4"
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
TAREA=(prodMeraGallery "-PprodSodium=$SODIUM" "-PprodIris=$IRIS" "-Pfabric_runtime=$FAPI" "${EXTRA[@]}")
if [ "$MODO" = "externo" ]; then
  ./gradlew copiarModsProd --console=plain "-PprodSodium=$SODIUM" "-PprodIris=$IRIS" "-Pfabric_runtime=$FAPI" > "copiar-$NOMBRE.log" 2>&1
  echo "copiarModsProd exit=$?"; ls -la run/mods-prod
  FLAGS="-Dopeope.autotest=true -Dopeope.gallery=true -Dopeope.gallery.mera=true"
  [ "$HORA" = "noche" ] && FLAGS="$FLAGS -Dopeope.gallery.noche=true"
  JAR=$(ls build/libs/*.jar | grep -v -- '-sources' | head -1)
  echo "jar: $JAR"
  TAREA=(prodJarExterno "-Pjar=$JAR" "-PmodsDir=run/mods-prod" "-Pflags=$FLAGS")
fi
timeout 2400 ./gradlew "${TAREA[@]}" --console=plain > "galeria-$NOMBRE.log" 2>&1
echo "exit=$?"
for f in run/screenshots/autotest-mera-*.png; do
  [ -f "$f" ] || continue
  convert "$f" -quality 88 "capturas/$NOMBRE/$(basename "${f%.png}").jpg" || cp -f "$f" "capturas/$NOMBRE/"
done
ls "capturas/$NOMBRE" | wc -l
grep -E "prodJarExterno|AUTOTEST galería|AUTOTEST FALLO|AUTOTEST mera|Using shaderpack|Failed to create shader|shaderpack failed|Mera:|Exception|Incompatible|requires|Mixin apply" "galeria-$NOMBRE.log" | head -200 || true
echo "----- últimas líneas -----"
grep -vE "Downloading|Download " "galeria-$NOMBRE.log" | tail -40
rm -f run/config/iris.properties
exit 0
