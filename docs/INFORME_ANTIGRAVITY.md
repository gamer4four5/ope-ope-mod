# Informe: lo que se añadió en Antigravity («Spanish Greeting»)

Rama `antigravity-ultima`. Se creó a partir de la carpeta `C:\Users\PC\dev\ope-ope-mod`, la que usa la conversación «Spanish Greeting» de Google Antigravity.

- **Base conocida:** `origin/desmembramiento-law` (último commit `c85b70a`, 18-11-2026, hecho en Claude). Esa rama **no** está fusionada en `main`.
- **Commits nuevos de Antigravity:** 13, del 20 al 22-11-2026.
- **WIP:** un commit más, `8d879f8`, con lo que aún no estaba commiteado.
- **Respecto a `origin/main`:** 29 commits por delante y 0 por detrás. Son 165 archivos, con +27.350 y −272 líneas; unos 70 de esos archivos son JSON de anatomía, que vienen de la rama `desmembramiento-law`.
- **Compilación:** `gradlew build` compila con éxito usando el JDK 21 del launcher de Minecraft. Para usarlo hay que poner `JAVA_HOME="C:\Program Files (x86)\Minecraft Launcher\runtime\java-runtime-delta\windows-x64\java-runtime-delta"`, porque el Java del sistema es la 8 y no sirve.

| Commit | Fecha | Qué |
|---|---|---|
| ebd8ccd | 20-11 | Corte CSG libre, cortes recursivos, formas del Bisturí y tapas compatibles con shaders |
| f7e9b52 | 20-11 | Arreglo de una ConcurrentModificationException al recoger partes |
| 141c26a | 20-11 | Compatibilidad con shaders (Iris) y con CEM/FreshAnimations (EMF/ETF) |
| 419e0d9 | 20-11 | Shambles mejorado, bisección por la mitad del cuerpo y autonomía de las partes |
| 57734df | 20-11 | FX 3D de Shambles, 3 modos de Shambles y animaciones procedurales del jugador |
| 5fdbeb2 | 20-11 | Bloqueo del torso, arreglo de la tecla Alt y nuevos idiomas es_* (**rompe `en_us`**) |
| f497d95 | 21-11 | Recompensa (bounty), rangos de maestría, rachas de muertes y reinicio de Shambles |
| e04d993 | 21-11 | Poderes de Law aplicados a supervivencia (`SurgicalSurvival`) |
| 51cfe59 | 21-11 | Takt mental (Mayús+R) y separación de minerales de 2× a 4× |
| cb2c1a7 | 22-11 | 2.ª fruta, **Zushi Zushi no Mi (Fujitora)**; espada Yakuza Kasen; sistema de dos frutas |
| b343483 | 22-11 | Gravity rehecha sin ROOM y `GravityFxClient` |
| c73fd73 | 22-11 | Roca levitante montable como vehículo |
| a1bf108 | 22-11 | La roca se arranca del mundo y vuela a tus pies |
| 8d879f8 | 22-11 | WIP: meteorito Tengai Shinsei con carga y tamaño, cráteres progresivos y render del meteorito |

---

## 1. Desmembramiento (ebd8ccd, f7e9b52, 141c26a, 419e0d9)

### Para el jugador
- **Amputate:** el tajo ya no se limita a un único plano por el torso. Cada plano corta por donde pasa, en cualquier ángulo, y un ser puede quedar en hasta 6 piezas. Las piezas ya sueltas (`BodyPartEntity`) también se pueden volver a cortar.
- **Bisturí** (clic derecho mantenido con la mano vacía y un ROOM activo):
  - **Formas:** Plano, Cuña en V, Cubo, Cilindro (túnel), Esfera, Rodajas ×3, Cruz ×4 y Lazo libre. Se cambia de forma con Alt + rueda o con el clic central.
  - **Controles de la rueda:**
    - Rueda: gira el plano.
    - Mayús + rueda: lo inclina.
    - Ctrl + rueda: lo desplaza, o cambia el radio de la forma (0,14–1,15 m).
  - **Cortar:** con clic izquierdo. Si mantienes Mayús, hace un hueco en vez de atravesar.
  - **Piezas:** quedan congeladas (`F_FROZEN`) y caen todas a la vez al soltar el clic derecho.
  - **Lazo libre:** se dibuja la silueta manteniendo el clic izquierdo; mientras tanto la cámara se bloquea.
  - Tiene vista previa holográfica (`EntityCutPreview`).
- **Mobs sin JSON de anatomía:** se cortan con una `SyntheticAnatomy`, que trata al mob como un único segmento `body` del tamaño de su hitbox.
- **Takt con partes del cuerpo:**
  - Puede coger `BodyPartEntity`.
  - Si sueltas una agachado contra un bloque, queda clavada (`F_PINNED`).
  - Si la lanzas fuerte contra una pared, se queda clavada en ella.
- **Bisección:** apuntar al núcleo del cuerpo corta por la mitad: `back_half`/`front_half` en cuadrúpedos y `waist_half`/`lower_half` en bípedos. El tajo arrastrado pasa por el centro de la entidad.
- **Postura del ser cortado:** se cae de lado, se inclina o cojea según las patas que le queden.
- **Partes autónomas:** cada parte elige su forma de moverse (`GaitMode`): `BALANCED_WALK`, `HALF_QUAD_LIMP`, `SIDE_OR_ONE_LEG_HOP` o `ARM_CRAWL`. Las cabezas emiten sonidos y dan saltitos.

### Cómo está hecho
- **`anatomy/CutOp`:** interfaz sealed. Cada variante es una función de distancia con signo (SDF) en píxeles de modelo (16 px = 1 bloque); `sdf ≤ 0` significa que el punto se conserva.
  - Variantes: `HalfSpace`, `BoxVolume`, `SphereVolume`, `CylinderVolume` y `PrismVolume` (polígono de 3 a 24 vértices extruido; lo usan el lazo y la cuña).
  - Todas tienen una codificación de texto compacta: `H:`, `B:`, `S:`, `C:` y `P:`.
- **`anatomy/CutVolume`:** guarda `AND(include) AND NOT(OR(AND(grupo de exclude)))`.
  - Se serializa como `inc1;inc2~ex1a;ex1b~ex2`.
  - Tiene una vía rápida cuando todo son semiespacios.
- **`anatomy/VolumeBounds`:** calcula por muestreo (rejillas de 6³ u 8³) la fracción de volumen que queda, la caja recortada y el volumen.
- **`DismemberState`:**
  - Campos nuevos: `sliceVolumes` (UUID de la pieza → su volumen) y `splitParts`, con codecs.
  - Migra los guardados antiguos (`plane`/`slice`).
- **`BodyPartEntity`:** guarda el volumen CSG de la pieza en un TrackedData `VOLUME` y en el NBT `volume`. También tiene `pinned`/`pin_support`.
- **Servidor (`power/Dismember`):**
  - `sweep` → `planeCut`:
    - Elige qué lado se queda el ser (el que tiene más visión, luego más núcleo, luego más volumen).
    - Llama a `applyVolumeExtraction`, que separa la pieza y clasifica cada segmento: si conserva menos del 5 % pasa a `detached`; si conserva menos del 45 % queda `impaired`.
  - `extractShape` hace los huecos; `splitBodyPart(ByOp)` vuelve a cortar piezas ya sueltas.
  - Si un corte apenas roza (deja menos del 20 % de masa), el plano se desplaza como mucho 2,2 px hacia el centro.
- **Red:**
  - Nuevo C2S `Payloads.CutShape(targetId, mode, delayed, thru, float[] ≤96)`, que procesa `Dismember.handleShapeCut`.
  - `DismemberSync` lleva ahora `String volume` (como máximo 8192 caracteres).
- **Cliente:**
  - `FreehandSlicer`: convierte el gesto del ratón en un polígono, lo simplifica con Ramer-Douglas-Peucker hasta 24 vértices y lo envía como prisma.
  - `VolumeSliceConsumer`: sustituye a `SliceConsumer`. Recorta cada quad con Sutherland-Hodgman contra los semiespacios; para las formas usa marching triangles sobre una subdivisión de cada triángulo y aproxima los bordes por bisección.
  - `CapBuilder`: triangula las tapas planas por ear-clipping (carne, hueso al 32 % y borde emisivo) y genera las paredes de túneles y huecos, con caché LRU de 256 entradas.
  - Las capas de render pasan a ser `EntityCutoutNoCull(cut_cap.png)` y `EntityTranslucentEmissive(cut_rim.png)`. **Por eso ahora funciona con Iris/Photon.**
- **Compatibilidad con shaders y EMF (141c26a):**
  - Se soportan vértices encadenados (`vertex().color()…normal()`).
  - Nuevo mixin `VertexConsumerProviderImmediateMixin`: mientras hay un corte activo, envuelve **cualquier** `getBuffer` de QUADS.
  - Nuevo mixin `ModelPartRotateMixin`: vuelve a ocultar los huesos que `animate()` de EMF hace visibles.
  - `ModelParts.EmfCompat` integra EMF/ETF por reflexión, sin dependencia de compilación.
  - `CEM_ALIASES` traduce nombres de huesos CEM (`EMF_head2`, `leg1..4`…).
- **Config (`OpeConfig`):** nuevas `MAX_OPS_PER_VOLUME=6`, `MIN_SLICE_VOLUME_PX3=8`, `SYNTHETIC_ANATOMY=true`. `MAX_PLANES_PER_PIECE` sube a 6, pero ya no se usa.
- **Tests (`AutoTest`):** E1–E8 (corte CSG, lazo, Blaze sintético, reunir partes, Bisturí congelado, pieza clavada), E7c (CME), E9a/E9b (shaders/EMF) y E10a–c (bisección y Shambles).

---

## 2. Shambles, animaciones, rangos y supervivencia (57734df → 51cfe59)

Teclas por defecto: **Shambles = Alt izquierdo** y **Takt = R**.

### Modos de Shambles (57734df, 5fdbeb2)
- **Modo 0, Tú → Destino** (el nuevo por defecto): te teletransportas al punto al que apuntas dentro del ROOM.
- **Modo 1, Tú ↔ Entidad:** intercambias tu posición con la entidad apuntada.
- **Modo 2, Entidad A ↔ B:** el comportamiento antiguo de marcar e intercambiar. El robo de items y la redirección de proyectiles solo funcionan aquí.
- **Cambiar de modo:** Ctrl+Alt, o rueda del ratón con Alt pulsado. El modo se guarda en `PlayerPower.shamblesMode`, que **no persiste**.
- **Sincronización:** flags de estado `SHAMBLES_MODE_SWAP`, `SHAMBLES_MODE_ENTITIES` y `SHAMBLES_MARKED`.
- **Nuevo payload S2C `ShamblesFx`**, con los tipos `SELF_TP`, `SELF_SWAP`, `ENTITY_SWAP`, `TACTICAL`, `MARK` y `CLEAR_MARK`. Se envía a los jugadores que estén a 160 bloques o menos.
- **`ShamblesFxClient`:** dibuja la silueta, las lonchas por un arco Bézier, los portales, la columna, la doble hélice, el anillo en pantalla y la vista previa del punto de llegada.
- **Teletransporte más seguro (419e0d9):**
  - Raycast estricto y luego uno laxo.
  - `emptyPoint` limita el destino a la esfera del ROOM, sube encima de bordes y ajusta al suelo.
  - Conserva parte de la inercia.
  - También puede intercambiar `ItemEntity`.
- **`PlayerAnimClient`:** animaciones procedurales.
  - En 3.ª persona: inclinación al esprintar, ladeo en curvas, rebote, aterrizaje, porte de espada y gesto de chasquido.
  - En 1.ª persona: balanceo y gesto.
  - En la cámara: cabeceo.
  - Se aplica mediante mixins de `BipedEntityModel.setAngles`, `LivingEntityRenderer.setupTransforms` y el brazo en 1.ª persona.
  - 5fdbeb2 bajó las amplitudes y bloqueó el roll del torso.

### Recompensa y rangos (f497d95)
- **Dato persistente:** `ModAttachments.BOUNTY` (Long, se conserva al morir).
- **Rangos:**

  | Rango | Recompensa mínima | Vida | Velocidad | Daño | Cooldowns |
  |---|---|---|---|---|---|
  | Aprendiz | 0 | – | – | – | – |
  | Cirujano de Combate | 5M | +2 | +8 % | – | −10 % |
  | Supernova | 30M | +6 | +14 % | +15 % | −20 % |
  | Shichibukai | 100M | +10 | +20 % | +28 % | −30 % |
  | Yonko | 300M | +16 | +25 % | +45 % | −40 % |

  El descuento de cooldown se aplica a **todas** las técnicas, con un tope de ×0,55.
- **Al matar (`PowerManager.onSurgicalKill`):**
  - Recompensa según la vida de la víctima.
  - Racha: muertes a menos de 240 ticks entre sí suman +25 % cada una, hasta ×3.
  - Si la víctima estaba dentro de tu ROOM: te cura y **pone a 0 el cooldown de Shambles**.

### `SurgicalSurvival` (e04d993, 51cfe59): clase nueva de unas 1140 líneas
- **Scan X-Ray:**
  - El servidor informa en la actionbar de los minerales, spawners y cofres del ROOM.
  - El cliente (`ScanList.scanBlocks`) recorre unos 37³ bloques cada 10 ticks y los dibuja sin test de profundidad, así que se ven a través de las paredes.
- **Shambles minero (`tryExtractOreVein`):**
  - Alt sobre una mena extrae la veta entera con un BFS de hasta 12 + 5·rango bloques.
  - Deja la roca huésped en su sitio.
  - Aplica Fortuna virtual, funde automáticamente con K-ROOM y da recompensa.
  - Mayús+Alt usa toque de seda.
- **Mes en bloques:** extrae spawners, trial spawners, budding amethyst, reinforced deepslate y colmenas conservando su NBT. Con Mayús extrae **cualquier** block entity. El NBT se restaura al colocar el bloque (`PendingBeRestore`).
- **Counter Shock en el entorno:**
  - Carga a los creepers.
  - En un cubo de 3×3×3: los hornos funden 16 items al instante, las fogatas cocinan, los soportes de pociones elaboran, quita la oxidación del cobre y vitrifica bloques (arena → vidrio, etc.).
- **Union de crafteo:** junta los items sueltos cercanos y crea con ellos la receta de más ingredientes que encaje, hasta 16 veces.
- **Cosecha al cortar:** `CutEngine` extrae los minerales y cosecha los cultivos (replantando) que estén cerca del plano de corte.
- **Takt mental, Mayús+R (`ORDER_MIND_BREAK=20`):**
  - Con piezas en Takt: las pulveriza.
  - Apuntando a una mena: extrae la veta.
  - Apuntando a un tronco: tala el árbol entero.
  - En otro caso: excava 3×3×3.
  - **No tiene cooldown.**
- **`givePerfectOreYield`:**
  - Multiplica los drops ×2, ×3 o ×4.
  - Subproductos: el carbón da un **20 % de probabilidad de diamante**, entre otros.

### Idiomas (5fdbeb2)
- Nuevos `es_co` y `es_pe`, y claves nuevas en el resto de `es_*`.
- ⚠️ **Los 10 archivos de idioma, incluido `en_us.json`, son ahora idénticos y están en español.** Se perdió la traducción inglesa; hay que restaurarla desde `5fdbeb2^`.

---

## 3. Zushi Zushi no Mi: gravedad de Fujitora (cb2c1a7 → 8d879f8)

### Sistema de dos frutas
- **Datos:**
  - `ModAttachments.FRUIT_KIND` (persistente; 1 = Ope, 2 = Gravity). `HAS_FRUIT` sigue siendo el interruptor general.
  - `PlayerPower.fruitKind` / `isGravity()`, con estado propio de Gravity: `gravityVector` (0 = +G, 1 = −G, 2 = repeler, 3 = atraer), la carga, `gravityHeld` (equivale al Takt), `gravityMountId` y, en el WIP, `meteorTier`/`meteorCharging`.
- **Items:**
  - `gravity_fruit`: un `FruitItem(2)` que al comerse llama a `grantFruit(p, 2)`.
  - `yakuza_kasen`: espada de netherita con +6 de daño.
  - **Se regalan las dos al entrar al mundo si no las tienes.** No hay recetas nuevas.
- **Cambio de fruta:** `PowerManager.grantFruit` / `switchFruit`. Se hace desde la pantalla de la isla (tecla J, acción 19) y conserva rango y recompensa.
- **Enrutado de acciones:**
  - Con Gravity, `Actions.handle` desvía todo a `handleGravityAction`; las acciones de Law sin equivalente quedan bloqueadas.
  - `RoomOps.press` no hace nada con Gravity, y cualquier ROOM existente se elimina.
- **HUD:**
  - Bits nuevos en `State.flags`: `GRAVITY_FRUIT`, el vector, `RIDING`, `CHARGING` y, en el WIP, `METEOR_*` (bits 24–27).
  - `Theme.refresh()` cambia todo el HUD a morado `0xC266FF`.
  - `RoomPill`, `AbilityBar`, `StatusChips`, `CenterHud` y `Hints` (contextos `grav_*`) se adaptan a Gravity.

### Técnicas: tecla → servidor (`power/GravityPowers.java`)
| Tecla | Técnica | Detalle |
|---|---|---|
| **G mantenida** | Gravito con carga | Carga hasta 60 ticks: potencia 0,45–2,2, radio 4,5 + 2,4·pot. Con +G hace 14 + 18·pot de daño, aturde y deja una zona y un cráter; con −G lanza todo hacia arriba; también tiene repulsión y vórtice. **Sin cooldown.** |
| Mayús+G / G+rueda / Tab+3 | Cambiar vector | Rota entre +G, −G, repeler y atraer |
| **R** | Levitar | Entidad a ≤55 m, o arranca un bloque real de 3×3×3 como `PieceEntity`. Hasta 12 masas orbitando. **Sin cooldown.** |
| Clic izq. / Clic der. / Rueda | Lanzar / Estampar / Distancia | Lanzar a 140 m; estampar hace 22 + 5·rango; la distancia va de 4 a 48 m |
| **Mayús+R** | Prensa | Comprime las rocas: menas ×3–4, obsidiana y 70 % de diamante. **Sin cooldown.** |
| **Alt** | Vuelo en roca | Ver el vehículo más abajo. Mayús+Alt sobre una mena extrae la veta (hasta 14 + 5·rango bloques) |
| **Z mantenida** (WIP) | Tengai Shinsei, el meteorito | La rueda elige entre 5 tamaños (12–85 m); al soltar lo lanza (con Mayús, triple). Daño 60 + 42·nivel + 12·rango, fuego y cráter. Cooldown de 6 s |
| X | Moko | Corredor de 28 m que rompe bloques blandos; 38 + 8·rango de daño |
| C / clic con Yakuza Kasen | Jigoku Tabi | Cráter de radio 5; 46 + 8·rango de daño; cooldown de 400 ticks |
| V | Haki Ciego | 16 s de visión nocturna, Velocidad II y Resistencia I, y 45 % de esquiva |
| B | Escudo de repulsión | Resistencia III y empuje a 4,2 m |
| N / Tab+4 | Vórtice | Atrae en radio 9,5. **Sin cooldown.** |
| Tab+Z/X/V | Escombros de Dressrosa | Levita hasta 8 losas |
| Tab+5 | Meteorito triple | Del nivel actual, sin carga previa |
| Tab+C | Hipercompresión | Items tirados → diamante, obsidiana o fundidos. **Sin cooldown.** |
| M | Despertar | El mismo de Law |

**Zonas activas (`ActiveZone`, en `tickZones`):**
- **+G:** aplasta, aplica Lentitud III, hace daño periódico y desvía proyectiles hacia el suelo.
- **−G:** hace subir lo que hay dentro.
- **Vórtice:** atrae en espiral.

### Vehículo de roca (c73fd73, a1bf108)
**Alt (`rockFlightToggle`):**
1. Si ya vas montado: saltas y la roca sale disparada.
2. Si no: usa una roca ya levitada o, si no hay, arranca una losa real del mundo con `findRealRockSource` (primero el bloque apuntado; si no, busca piedra en 16 direcciones).
3. La losa vuela a tus pies en el modo `PieceEntity.Mode.SUMMON_SURF`. Al llegar, `onSurfRockArrived` te monta con `startRiding` y te da 300 ticks de inmunidad a caídas.

**Modo `SURF`:**
- La roca no tiene gravedad y el jinete la controla (`getControllingPassenger`).
- El pilotaje está en el cliente (`GravitySurfClient`):
  - W sigue la mirada; A/D se desplazan de lado; Espacio sube; Ctrl es turbo.
  - El servidor recibe la posición con el `VehicleMoveC2SPacket` de vanilla.
- Para ir de pie: `BipedEntityModelMixin` y `PlayerAnimClient`.

### Render
- **`GravityFxClient`:**
  - Recibe hasta 36 eventos por el payload S2C `GravityFx` (kind 0–8), enviado a jugadores a 420 m o menos.
  - Dibuja columnas de presión, Moko, grietas, impactos, escudo, carga, vórtice y la vista previa del cráter, con cola de fuego en el WIP.
  - Una pasada sin profundidad dibuja las auras del Haki Ciego.
  - Viñeta y temblores de cámara.
- **`PieceRenderer.renderMeteor`** (WIP): malla cacheada, escala `METEOR_SCALE` (1,15–7,8) y rotación en 3 ejes.
- **No hay entidades nuevas:** todo reutiliza `PieceEntity` con los modos `SURF`, `SUMMON_SURF` y `METEOR`. El WIP sube el tracking range de 16 a 32.

---

## 4. Bugs y deuda técnica detectados (ordenados por importancia)

1. **`en_us.json` está en español.** Los 10 archivos de idioma son el mismo; hay que restaurar el inglés desde `5fdbeb2^`.
2. **WIP del meteorito:**
   - Abrir una pantalla con Z mantenida **lanza** el meteorito, porque se envía `RELEASE` con arg 0. Falta una acción de cancelar.
   - Si te quedas sin fruta o pasas a espectador cargando, el servidor sigue cargando.
   - `meteorTier` nunca se reinicia, así que el siguiente lanzamiento empieza en el máximo.
   - Un meteorito triple de 85 m (~116 m de cráter) cada 6 s, sin respetar protecciones y llenando todo de fuego y magma.
   - `adjustMeteorSize` no comprueba el cooldown.
3. **Datos sin persistir:**
   - `METEOR_SCALE`, `GRAVITY_SURF` y el modo de `PieceEntity` no se guardan en NBT.
   - `METEORS`, `CRATERS` y `ZONES` son estáticas: al recargar el mundo, un meteorito en caída se convierte en una pieza normal de 11³.
   - `shamblesMode` y el estado congelado del Bisturí (`frozenOffset`, `bisturiHeldParts`) tampoco persisten.
4. **`PieceEntity.watchdog()` suelta cada 10 ticks las rocas de Gravity**, porque no están registradas en `Takt.HOLDERS`. Puede causar tirones.
5. **Marcas de Shambles de otros jugadores:** `ShamblesFxClient.receive` no comprueba `userId`. Verás como tuyas las marcas de otros, y su `CLEAR_MARK` borra la tuya.
6. **`CutVolume.complementOfAll`** descarta en silencio los grupos de exclusión de varias operaciones, así que el volumen restante sale mal tras cortes recursivos.
7. **Render global:**
   - `VolumeSliceConsumer` puede dejar un vértice sin volcar en formatos sin normal (por ejemplo, texto o nametags).
   - El mixin envuelve todos los buffers y filtra con `layer.toString().contains(...)`.
   - Hay parches defensivos: `GLOBAL_STACK.size()>8 → clear()` y capturar `IllegalStateException`.
8. **Balance:**
   - Sin cooldown: Gravito, levitar, la prensa, el vórtice, la hipercompresión y el Takt mental.
   - El 20–35 % de diamante sacado del carbón.
   - Mes con Mayús extrae cualquier block entity, por ejemplo un cofre con su contenido.
   - La recompensa se puede farmear con animales pasivos.
   - La fruta Gravity se regala al entrar al mundo.
9. **Otros:**
   - Union puede «robar» la tecla y craftear sin preguntar.
   - Alt + rueda lanza primero Shambles.
   - El escudo B de Gravity también ejecuta `Techniques.curtainTick` de Law.
   - Código inalcanzable: `YAKUZA_KASEN → kikokuCut` en `InputHandler.leftPress`.
   - Varios textos están fijos en español (`Text.literal`).
   - La recompensa no se sincroniza con el HUD.
   - `Bisturi` usa `mode.ordinal()` como protocolo.
   - Las heurísticas de izquierda/derecha y delante/detrás están triplicadas.
10. **Rendimiento:**
    - El X-ray lee unos 50k bloques cada 10 ticks.
    - Los cráteres de nivel 4 cambian miles de bloques por tick.
    - `VolumeBounds` muestrea 216–512 puntos por segmento.
    - `GravityFxClient` recorre todas las entidades en cada frame.

## 5. Mapa rápido de archivos clave
- **Desmembramiento:**
  - `src/main/java/dev/opeope/anatomy/*`
  - `power/Dismember.java`
  - `entity/BodyPartEntity.java`
  - `src/client/java/dev/opeope/client/dismember/*`
  - `client/input/{Bisturi,FreehandSlicer}.java`
  - `client/render/{BodyPartRenderer,EntityCutPreview}.java`
- **Shambles y animaciones:**
  - `power/Shambles.java`
  - `client/render/{ShamblesFxClient,PlayerAnimClient}.java`
- **Supervivencia y rangos:**
  - `power/SurgicalSurvival.java`
  - `power/PowerManager.java` (bounty, rangos, `grantFruit`/`switchFruit`, `syncState`)
- **Gravity:**
  - `power/GravityPowers.java`
  - `entity/PieceEntity.java` (modos `SURF`, `SUMMON_SURF`, `METEOR`)
  - `client/render/{GravityFxClient,GravitySurfClient,PieceRenderer}.java`
  - `client/input/InputHandler.java`
  - `client/hud/*`
- **Red:** `net/Payloads.java` (`CutShape`, `ShamblesFx`, `GravityFx`, flags de `State`) y `power/Actions.java` (acciones 12 y 110–119).
