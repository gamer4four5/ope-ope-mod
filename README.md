# Ope Ope no Mi · mod de Minecraft (Fabric 1.21.1)

La fruta de Law del juego 3D (`ope-ope-game`), pasada a Minecraft con el mismo esquema de teclas (versión 21).
Necesita **Fabric Loader** y **Fabric API** para Minecraft **1.21.1**.

## Instalar
1. Instala Fabric Loader para 1.21.1 (https://fabricmc.net/use/installer/).
2. Copia `ope-ope-no-mi-1.0.0.jar` y Fabric API (https://modrinth.com/mod/fabric-api, versión para 1.21.1) en la carpeta `mods`.
3. En el juego: pestaña creativa **Ope Ope no Mi**, o `/opeope fruta` con trucos. En supervivencia la suelta a veces el
   Guardián Anciano o el Warden. Cómetela: la tarjeta **Primeros pasos** te enseña lo básico y **H** abre el libro con
   todos los poderes, los ajustes y las teclas.

## Qué hay
- **ROOM** (Q): esfera azul con borde brillante. Mantener crece desde ti hasta 160 bloques; Q + rueda agranda o encoge;
  Q + clic la lanza a la mira; Q + clic derecho la trae; un toque la quita (o un doble toque, si lo eliges en Ajustes).
- **Cortes reales en bloques**: Amputate (clic con la mano vacía: la cámara se queda quieta y el ratón traza el tajo; una
  línea brillante marca lo que se va a cortar; al soltar, una hoja de luz barre el plano y la costura brilla). Lo que queda sin
  unión con el resto del mundo cae como una pieza entera (casas, torres, árboles). Lo que queda encima de un corte
  inclinado u horizontal resbala por el plano aunque siga pegado al terreno fuera del ROOM: el borde del ROOM también corta
  (la cima de un monte, la parte de arriba de una pared). Bisturí (clic
  derecho mantenido): el tiempo va al 12 % en una partida de un jugador y el plano pasa por donde apuntas; cada clic
  corta ya (hasta 16 planos), lo cortado se queda quieto y al soltar cae todo a la vez.
  Patrones (Tab 1), alcance (Tab 2), suspender (Tab 3). La Kikoku dentro del ROOM corta con cada tajo.
- **Seres vivos en piezas**: un mob cortado no pierde vida; se ve partido (cada mitad flota separada) y no se mueve
  ni ataca hasta que se recompone (30 s, la Unión o la Cirugía).
- **Shambles** (Alt izquierdo): teletransporte al aire del ROOM, marcar y cambiar, cambiar bloques agachado, piedra
  marcador (G), robar con Scan.
- **Takt** (R): coger seres, objetos o piezas, o **arrancar un trozo de suelo** que sigue tus cortes (con R mantenida
  se ve antes y al soltarla se arranca; R + rueda cambia su tamaño); R R levanta lo suelto de la zona; clic lanza el de
  delante (mantener: ráfaga; un trozo abre un cráter); clic derecho lo lanza todo; R mantenida + tecla: órbita, aparcar,
  posar (baja a su silueta), soltar, zona, lo cortado, rejuntar, unir todo y **forjar** lanza, hoja colosal, martillo,
  estacas o muralla con el material que llevas.
- **Unión** (Tab C): coser piezas, reconstruir (vuelven volando a su sitio y se sellan), Cirugía manteniendo.
- **El suelo del ROOM**: pilar, muralla que sigue la mirada, sima, meseta.
- **Técnicas**: Radio Knife, Injection Shot, Gamma Knife, Scan (contornos a través de paredes), Curtain, Mes (el corazón
  en un cubo), Counter Shock, Sterben, Cambio de almas, Lluvia de cortes.
- **Despertar** (V, se gana venciendo al Wither o al Dragón con la fruta): K-ROOM, Anesthesia, Shock Wille, R-ROOM,
  Puncture Wille, Silent.
- El agua: sumergido, la fruta no funciona y te hundes.

## Teclas
Las mismas del juego, salvo **Shambles en Alt izquierdo** (la E es el inventario de Minecraft). Las teclas solo se las
queda la fruta si la has comido. Se cambian en la pantalla **Teclas** (en el libro, o en Ajustes → «Teclas…»): clic en
la tecla y pulsa la nueva; Esc la deja sin tecla. Cada tecla avisa si choca con algo: en rojo si nunca funcionaría, en
ámbar si Minecraft también la usa y en gris si solo conviene saberlo. «Restablecer todo» vuelve a las de fábrica y
«Como el juego» pone Shambles en la E (y ofrece pasar el inventario a la I). Se guardan en `config/opeope-teclas.json`
(nombres como `key.keyboard.q` o `key.mouse.4`), que también se puede editar a mano.

El libro (**H**) y los ajustes son teclas normales de Minecraft: están en los controles de Minecraft (Opciones →
Controles), en la categoría «Ope Ope no Mi» («Libro de la fruta» y «Ajustes de la fruta», esta sin tecla de fábrica).
Si habías cambiado la tecla del libro en `opeope-teclas.json`, ese valor ya no se usa: elígela otra vez ahí.

Con la fruta, la Q es del ROOM y la F de Scan: para tirar objetos o cambiar de mano, agáchate (Mayús) y pulsa Q o F.
F3 y sus combinaciones y Ctrl+B siguen siendo de Minecraft. Agachado, los clics vuelven a ser los normales de
Minecraft. Los botones laterales del ratón hacen de Tab. Si pulsas una técnica que aún no está lista, su casilla tiembla
y suena un «no» corto.

## El HUD
Todo el HUD de la fruta está pensado para leerse de un vistazo sin tapar el de Minecraft (vida, comida, barra de objetos,
efectos, barras de jefe, chat y subtítulos), a cualquier tamaño de ventana y de GUI. Con F1 no se ve nada; con F3 solo
lo del centro y los avisos.
- **Barra de técnicas**, abajo a la derecha junto a la barra de objetos: un icono por técnica con su tecla de verdad
  debajo. Mientras se enfría, un velo oscuro baja y se ven los segundos; al volver a estar lista, destella. Si algo no se
  puede usar se ve también por la forma (velo, segundos o candado), no solo por el color. Tras unos segundos sin usar la
  fruta se atenúa.
- **Píldora del ROOM**, arriba a la izquierda: «Sin ROOM», «ROOM 12 m · creciendo», «ROOM 24 m · dentro/fuera», «ROOM →
  37 m» cuando vuela o «ROOM ← contigo» cuando vuelve. Debajo, **chips de estado**: Despertar, Scan con su tiempo,
  Curtain, Silent, el arma forjada, la Unión, el patrón y el alcance del corte, o si estás en el ROOM de otro jugador.
- **Medidor del ROOM** mientras crece o le cambias el tamaño: una escala de 4 m a 160 m con el tamaño exacto; tiembla al
  llegar al máximo o al mínimo.
- **Tarjeta de pistas**, arriba a la derecha: las teclas que te sirven en ese momento (para empezar, en el ROOM, con
  Takt, con el corazón o con la Unión), siempre con tus teclas. Se encoge sola cuando ya las conoces.
- **Primeros pasos**: seis pasos que se marcan solos al hacerlos (abrir el ROOM, cambiarle el tamaño, cortar, Shambles,
  Takt y la segunda fila). Se pueden saltar o repetir en Ajustes.
- **Filas de teclas**: al mantener Tab (segunda fila) o R (órdenes de Takt) sale en el centro qué hace cada tecla, cuánto
  le falta a lo que se enfría y por qué algo no se puede (por ejemplo, «Lluvia necesita un ROOM»).
- **Avisos**: los mensajes de la fruta salen como avisos pequeños encima de la barra de objetos en vez de pisar la barra
  de acción; los momentos del ROOM, como un aviso grande en el centro («ROOM», «ROOM · 24 m», «Aterriza · 37 m»,
  «Contigo», «ROOM cerrado», «Máximo · 160 m»).
- En el centro, además: la mira cambia cuando apuntas dentro o fuera de tu ROOM, la carga de Amputate, lo que llevas
  con Takt y el panel del Bisturí (cortes hechos, giro, ladeo y las teclas). La lista de Scan sale a la izquierda.

## Ajustes y accesibilidad
La pantalla **Ajustes** se abre desde el libro, desde Opciones → Controles → «Ope Ope no Mi…» o con la tecla «Ajustes de
la fruta» si le pones una. Cada opción explica lo que hace al pasar el ratón por encima.
- **Interfaz**: Completa, Sencilla o Mínima; **Tamaño** del HUD (Pequeño, Normal o Grande); **Opacidad** de los paneles;
  **Barra de técnicas** abajo, vertical, solo al usarla u oculta; **Pistas** siempre, solo al empezar o nunca; **Aviso
  grande del ROOM** sí o no.
- **Accesibilidad**: **Contraste** (como el de Minecraft, alto o normal; el alto pone paneles opacos y bordes blancos), **Menos
  movimiento** (sin sacudidas, sin cambios del campo de visión ni rayas), **Sacudidas de cámara** de 0 a 100 %,
  **Destellos** (como Minecraft, sí, suaves o no) y **Narrar el ROOM** con el narrador de Minecraft. Los sonidos del ROOM
  tienen subtítulos.
- **Controles**: el ROOM **Mantener** (como siempre) o **Alternar** (un toque entra y sale del modo ROOM); **Quitar el
  ROOM** con un toque o con doble toque; la **segunda fila** y las **órdenes de Takt** con Mantener, Alternar o «Una
  tecla» (un toque la abre y se cierra tras la siguiente tecla); el **Bisturí** y la **Cirugía** con Mantener o Alternar;
  si Tab mantenida enseña la lista de jugadores en multijugador; y los tiempos del toque de R, del doble toque, de la
  espera de R y de la espera de Cirugía. Lo que se queda fijo se suelta con su tecla, con Esc, al abrir una pantalla, al
  cambiar de ventana o (el ROOM y las filas) tras 10 s sin usarlo.
- **Más**: Teclas…, volver a mostrar las pistas, repetir o saltar los primeros pasos y restablecer los ajustes. Se guardan
  en `config/opeope-ajustes.json`.

## El ROOM se nota
Crear o mover el ROOM ahora se ve, se oye y se siente:
- **Crear**: una semilla de luz sale de tu mano, florece con una onda y el ROOM crece con un zumbido que sube de tono; al
  soltar Q queda fijo con un golpe de campana. Una línea brillante marca dónde toca el suelo y el contorno de la esfera
  se ve siempre.
- **Tamaño**: cada paso de la rueda es un «clic» con un eco de la esfera anterior; en el máximo o el mínimo, un golpe
  seco y el aviso.
- **Lanzar**: sale como un cometa en arco con estela, aterriza con un destello, un anillo en el suelo y un golpe de cámara
  que depende de lo lejos que caiga; el ROOM viejo se pliega.
- **Traer**: un hilo de luz lo arrastra hasta ti y llega con una onda a tus pies.
- **Quitar**: la esfera se cierra hacia dentro con un último destello.
- Al entrar o salir de tu ROOM, el borde de la pantalla se tiñe un momento y suena un tono.
- La cámara da pequeños golpes y sacudidas y el campo de visión se abre al crear y lanzar: todo eso se quita con «Menos
  movimiento» o «Sacudidas de cámara», y respeta «Efectos de distorsión» y «Efectos de campo de visión» de Minecraft.
- Los demás jugadores cercanos ven y oyen tu ROOM (sin los efectos de cámara). Con un servidor sin esta versión del mod
  solo se ve la esfera.

## Moldear el suelo con Takt
- **Cortes que se recuerdan**: cada corte de Amputate, de un patrón o del Bisturí que cruza el suelo dentro del ROOM
  queda marcado tres minutos con una línea tenue (como mucho 24 por jugador). También se recuerdan los del R-ROOM; los
  de alcance corto (la Kikoku, Radio Knife, la hoja colosal o «Alcance: solo el objetivo»), no.
- **Ver antes de arrancar**: con R mantenida y la mira en el suelo de tu ROOM se ve el trozo exacto, y al soltar R se
  arranca justo ese. Si tus cortes encierran una zona (tres o más), es justo esa forma, y su contorno late aunque no
  pulses nada; si no, es un trozo redondo. En una cueva sigue el suelo de la cueva; en una pared o un techo es un
  pedazo tosco. R + rueda cambia su tamaño (de 3 a 18 bloques de ancho). Con las órdenes de Takt en Alternar o «Una
  tecla», al soltar R el trozo sigue a la vista y otro toque de R lo arranca.
- **Arrancar**: un toque de R agrieta el borde; el trozo tiembla, se suelta con polvo y piedras y sube a tu mano
  soltando tierra. Los cortes que le dieron forma se borran; los demás siguen. R R justo después lo deshace.
- **Llevarlo**: pesa. Se inclina al moverlo, flota sobre el terreno, y con los muy grandes la cámara tiembla un poco.
- **Posar** (R + V): una silueta muestra dónde caerá; baja recto y queda en bloques justo ahí, con un sello de luz,
  sin enterrar a nadie.
- **Lanzar**: cae donde apuntas. Si golpea fuerte abre un cráter, amontona la tierra en el borde y se queda clavado. No
  explota y no se pierde ningún bloque.
- **Las rocotas**: lo grande cae como cae una montaña, casi sin frenar con el aire, y no hace falta lanzarlo: soltarlo
  desde lo alto basta. Al llegar abre un cráter de su tamaño, se queda metida dentro, levanta el borde, hace saltar
  terrones que vuelven a ser bloques y, si llega rasante, ara el suelo. La onda empuja y tumba a quien esté cerca (a
  ti no te hace daño). De lejos primero tiembla el suelo y luego llega el estruendo, con su eco; mientras cae se oye el
  aire, y si cae al agua salpica.
- **Unir todo** (R + X): cada trozo vuelve en arco a su hueco, del más nuevo al más viejo, y se sella.
- Los demás jugadores lo ven y lo oyen, y nadie puede quitarte de Takt lo que llevas. Todo respeta «Menos
  movimiento», «Sacudidas de cámara», «Destellos» y «Contraste».

## Compilar
`./gradlew build` (Java 21). El .jar queda en `build/libs/`. El flujo de GitHub Actions (`.github/workflows/build.yml`)
lo compila en cada push y deja el .jar como artefacto.

## Lo que no se pasó
El archipiélago, el mar con barcos y las misiones del juego no tienen sentido dentro del mundo de Minecraft; los
estilos de Espada y Pelea son la Kikoku y la mano de siempre.
