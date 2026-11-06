# Ope Ope no Mi · mod de Minecraft (Fabric 1.21.1)

La fruta de Law del juego 3D (`ope-ope-game`), pasada a Minecraft con el mismo esquema de teclas (versión 21).
Necesita **Fabric Loader** y **Fabric API** para Minecraft **1.21.1**.

## Instalar
1. Instala Fabric Loader para 1.21.1 (https://fabricmc.net/use/installer/).
2. Copia `ope-ope-no-mi-1.0.0.jar` y Fabric API (https://modrinth.com/mod/fabric-api, versión para 1.21.1) en la carpeta `mods`.
3. En el juego: pestaña creativa **Ope Ope no Mi**, o `/opeope fruta` con trucos. En supervivencia la suelta a veces el
   Guardián Anciano o el Warden. Cómetela y pulsa **H** para el libro con todos los poderes.

## Qué hay
- **ROOM** (Q): esfera azul con borde brillante. Mantener crece desde ti hasta 160 bloques; Q + rueda agranda o encoge;
  Q + clic la lanza a la mira; Q + clic derecho la trae; un toque la quita.
- **Cortes reales en bloques**: Amputate (clic con la mano vacía, arrastrando: el arco es el plano). Lo que queda sin
  unión con el resto del mundo cae como una pieza entera (casas, torres, árboles). Lo que queda encima de un corte
  inclinado u horizontal resbala por el plano aunque siga pegado al terreno fuera del ROOM: el borde del ROOM también corta
  (la cima de un monte, la parte de arriba de una pared). Bisturí (clic
  derecho mantenido): el tiempo va al 12 % en una partida de un jugador, plano visible, hasta 16 planos encadenados.
  Patrones (Tab 1), alcance (Tab 2), suspender (Tab 3). La Kikoku dentro del ROOM corta con cada tajo.
- **Seres vivos en piezas**: un mob cortado no pierde vida; se ve partido (cada mitad flota separada) y no se mueve
  ni ataca hasta que se recompone (30 s, la Unión o la Cirugía).
- **Shambles** (Alt izquierdo): teletransporte al aire del ROOM, marcar y cambiar, cambiar bloques agachado, piedra
  marcador (G), robar con Scan.
- **Takt** (R): coger seres, objetos, piezas o arrancar un trozo de suelo; R R zona; clic lanza el de delante (mantener:
  ráfaga); clic derecho lo lanza todo; R mantenida + tecla: órbita, aparcar, posar, soltar, zona, lo cortado, rejuntar,
  unir todo y **forjar** lanza, hoja colosal, martillo, estacas o muralla con el material que llevas.
- **Unión** (Tab C): coser piezas, reconstruir (vuelven volando a su sitio y se sellan), Cirugía manteniendo.
- **El suelo del ROOM**: pilar, muralla que sigue la mirada, sima, meseta.
- **Técnicas**: Radio Knife, Injection Shot, Gamma Knife, Scan (contornos a través de paredes), Curtain, Mes (el corazón
  en un cubo), Counter Shock, Sterben, Cambio de almas, Lluvia de cortes.
- **Despertar** (V, se gana venciendo al Wither o al Dragón con la fruta): K-ROOM, Anesthesia, Shock Wille, R-ROOM,
  Puncture Wille, Silent.
- El agua: sumergido, la fruta no funciona y te hundes.

## Teclas
Las mismas del juego, salvo **Shambles en Alt izquierdo** (la E es el inventario de Minecraft). Las teclas solo se las
queda la fruta si la has comido; para cambiarlas edita `config/opeope-teclas.json` (nombres como `key.keyboard.q` o
`key.mouse.4`). Con la fruta, la Q ya no tira objetos y la F no cambia de mano: si las necesitas, cámbialas allí.
Agachado, los clics vuelven a ser los normales de Minecraft. Los botones laterales del ratón hacen de Tab.

## Compilar
`./gradlew build` (Java 21). El .jar queda en `build/libs/`. El flujo de GitHub Actions (`.github/workflows/build.yml`)
lo compila en cada push y deja el .jar como artefacto.

## Lo que no se pasó
El archipiélago, el mar con barcos y las misiones del juego no tienen sentido dentro del mundo de Minecraft; los
estilos de Espada y Pelea son la Kikoku y la mano de siempre.
