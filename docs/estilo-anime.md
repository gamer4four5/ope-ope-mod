# Estilo anime de la Mera Mera no Mi

Objetivo: que el fuego de Ace se vea como en la serie (referencia: el recopilatorio «All Attacks and Abilities», 3:40).
El fuego realista no se quita: queda en la opción «Estilo del fuego: Anime | Realista» (por defecto, Anime).

## Qué tiene el fuego de la serie

- Colores planos. Dos o tres tonos por llama, con el corte limpio entre ellos. Nada de degradados ni de humo.
- Un borde grueso, del mismo ancho por todo el contorno, y por fuera un resplandor corto.
- Venas del color del borde por los surcos entre los lóbulos (en el puño de Hiken, entre los dedos).
- Casi sin humo: solo en las explosiones grandes y es blanco plano, con los surcos gris azulado (0:02).
- Animado «a doses»: la forma cambia 12 veces por segundo, no en cada fotograma.
- Motas sueltas amarillas alrededor (rayitas cortas), no chispas que se apagan en rojo oscuro.

## El shader (`mera_volume.fsh`)

`uniform vec4 VolStyle`:

| Componente | Qué es |
| --- | --- |
| x | 0 = realista (el de siempre, sin cambios), 1 = anime |
| y | paleta; 0 = la de la forma (ver abajo) |
| z | ancho del borde en grosores de llama; 0 = el de la paleta |
| w | el fotograma del golpe, de 0 a 1 (ver abajo); de 2 a 3, solo el fotograma (con fuerza w − 2), sin dibujar la bola; 0 = nada |

En anime se llama a `mfRenderToon` en vez de `mfRender`. Recorre el mismo volumen, pero en vez de sumar luz mide cuánto
se mete el rayo en la forma (el borde y el corazón), dónde la toca primero (las venas) y lo cerca que pasó (el
resplandor). Sale premultiplicado, igual que el realista.

### Paletas

| N.º | Nombre | Borde | Masa | Corazón | Dónde sale en el vídeo |
| --- | --- | --- | --- | --- | --- |
| 1 | Hiken | amarillo `#fdc82a` (y venas) | rojo `#f55749` | manchas negras con filo rojo oscuro: pocas, pequeñas, alargadas y de borde roto, en el centro de cada masa (no un agujero redondo) | 0:24-0:40 |
| 2 | natural | rojo anaranjado `#f14a21`, y por dentro una banda naranja `#f77d1c` | amarillo `#fdcc2e` | amarillo pálido `#fff6b0` | 1:12-1:17, 1:45, 3:00-3:06 |
| 3 | Marineford | amarillo pálido `#fde9a0` (fino; no hay borde oscuro) | dorado `#fbcb5a`, venas pálidas `#feeca8` | `#fcd369` | 0:08, 1:22-1:32, 1:55, 2:29-2:54 |
| 4 | rayo amarillo | naranja `#ec7821` | amarillo vivo `#f5e024` | pálido `#fff58f` | 0:15-0:17 (Ace contra Yamato; ya no es la de Kagerō) |
| 5 | Hotarubi | verde `#73f594` en la orilla | se aclara poco a poco, sin bandas | verde muy pálido `#ccfccf`, halo verde `#57d175` | 1:34-1:53 |
| 6 | sol de Entei | filo amarillo muy fino `#f9db66` | `#fbf8bd` | casi blanco `#feffdb` (casi todo el sol), resplandor ancho | 3:04-3:13 |
| 7 | Jūjika | filo dorado `#fdcc63` | amarillo limón `#fef580` | pálido `#fffda6` | 2:14 |
| 8 | humo | gris azulado `#b3bad4` (fino, y los surcos `#a8b1cf`) | blanco `#e7ecf6` | igual que la masa | lo que queda de una explosión (0:02) |
| 9 | columna | filo naranja `#f6b030` | banda amarilla `#fee63d` | amarillo pálido `#fcf199` (casi todo el haz) | 1:16-1:17 |
| 10 | remolino | no tiene | rojo plano `#de352b` | llamas amarillas `#fdcc2e` con el centro `#fdf68f` y el filo naranja | 1:12-1:13 |
| 11 | trazo | filo dorado `#f5c85a`, blando (sin línea) pero ancho y bien visible: casi un tercio del grosor por cada lado | amarillo pálido `#fdf3a6` | igual que la masa; casi sin resplandor (es un dibujo, no un foco) | 0:57, 2:11 |
| 12 | Hidaruma | filo naranja `#f18113`, blando (sin línea) | amarillo `#fccf1e` | amarillo pálido `#fff160`; poco resplandor, para que cada cuchilla se vea suelta | 1:42 |
| 13 | cúpula | filo naranja fino `#ff8a23` y grietas del mismo color | rojo plano `#f12316` | igual que la masa, con mucho resplandor rojo | 3:20-3:32 |
| 14 | estela | amarillo anaranjado `#fdb238`, un tercio del grosor por cada lado | rojo `#f45d40` por todo el centro del chorro | igual que la masa, sin manchas y casi sin resplandor (un dibujo nítido sobre el cielo) | 0:38-0:40 |
| 15 | Kagerō | amarillo `#febd38` | una banda naranja `#fe852b` y, más adentro, rojo `#de3429` que entra en lenguas | igual que la masa | 0:02-0:05 |
| 16 | golpe | amarillo `#fdc82a`, ancho | rojo `#f55749` plano y limpio: sin venas ni manchas, y el contorno más liso | igual que la masa | 0:36, 1:17 |
| 17 | sol naciendo | una orilla ancha de llamas: amarillo `#fed640` con picos naranjas `#f68f21` que corren por ella, filo naranja por fuera y más pálida hacia dentro | `#fefdd0` | igual que la masa | 3:03-3:08 |
| 18 | cruz de llamas | pálido `#fcde8f` | dorado `#fccc5d`, liso, sin venas | `#fed470`, con un resplandor ancho | 2:22 |
| 19 | hoguera pálida | dorado `#f5c245`, en llamitas pequeñas | amarillo pálido `#fdf1a3`, con llamitas doradas sueltas que suben | igual que la masa | 1:54-1:56 |
| 20 | nubes de la columna | no tiene | rojo plano `#eb3c1e` | llamas amarillas `#fedc33` con el filo naranja `#f58c1e` y el centro pálido `#fff48f`, en lo que mira hacia arriba y cerca del contorno | 1:16-1:18 |
| 21 | estallido de Jūjika | dorado `#f9bd4d`, de filo blando (sin línea) | dorado claro `#f9d678` | casi blanco `#f8f2a6` hacia el centro de la estrella, con un resplandor dorado ancho y suave | 2:23 |
| 22 | humo encendido | melocotón claro `#fad4b0` | melocotón `#f5bd85`, plano, en bolsas pequeñas y sin lenguas | igual que la masa | 1:16-1:18 |

Paleta de cada forma cuando `VolStyle.y` es 0: bola 2 (el sol, 6), puño 1, columna 9 (vista desde dentro, 2), anillos 2
(el remolino, 10), muro 3, chorro 15, cruz 7 (la grande, con el brazo de 0,8 o más, 18), lanza 11, llama 3. Una explosión que se enfría pasa sola a la 8.

### Formas que cambian en anime

- **Columna (2)** vista desde fuera (`prm.z` = 0): un haz recto y liso, sin hueco, que se abre en el pie y arriba
  (1:16), y que en el último cuarto de su alto se deshace en el cielo (de lejos, con el tope a la vista, acababa en
  una bola). El haz ocupa la mitad del radio pedido (en la serie mide algo más de un bloque de ancho: radio pedido 1,3) y
  el pie se abre hasta 1,3 veces ese radio. Con el radio pedido en 2,1 el haz mide unos dos bloques, como en la
  serie, y sigue macizo con la cámara a 4,5 bloques (con 3,0 la cámara quedaba dentro del pie y se veía hueco).
  Lo de alrededor (1:16-1:18) son dos anillos con `prm.w` = 1,25, con el mismo centro que la columna (el pie) y
  añadidos **antes** que ella, en este orden: la cortina de fuego (paleta 20, radio 9, alto 12, medio grosor 1,2) y
  el humo encendido (paleta 22, radio 3,2, alto 8, medio grosor 0,6). De los dos solo se dibuja la mitad de detrás
  del eje, así que nunca tapan la columna: se ve el haz, pegado a él un muro de humo melocotón con la orilla de
  arriba en bolsas, y detrás la masa roja con racimos de llamas amarillas. Las bolas con paleta 20 de antes ya no
  hacen falta (de frente eran globos rojos con una gorra amarilla).
- **Anillo (3)** según `prm.w`:
  - 0: fuego a ras de suelo. Naranja con aros amarillos y la orilla más roja (el disco del Dai Enkai, 2:59). Los
    aros van a uno por bloque más o menos (da igual el tamaño del disco), ondulados, con el centro pálido y algún
    corte, y corren hacia fuera. Con el grosor igual al radio es un disco lleno.
  - 1: la cortina de lenguas de siempre (Enjōmō).
  - 1,25: la misma cortina, pero solo se dibuja lo que queda detrás del eje del anillo visto desde la cámara (la
    cortina de fuego y el humo que rodean la columna de Hibashira). Con la paleta 22 el anillo es un muro de bolsas
    pequeñas, sin lenguas, que además se desvanece cuando la cámara está a menos de 6 a 7,5 bloques del eje: quien
    lanza la columna ve el haz y el fuego rojo de detrás, no una pared melocotón pegada a la cara.
  - 2: el remolino de Hibashira (1:12). Una media luna roja plana que gira sola alrededor del centro (casi una
    vuelta entera, gruesa en el medio y en punta en los extremos), con llamas amarillas que suben por la cabeza.
    `prm`: x = radio (2,6, y hasta 3,5 al crecer), y = alto de la banda (0,55), z = medio ancho de la banda (1,1).
    Origen a 0,3 del suelo. Con esas medidas es la media luna roja ancha y baja de la serie, de unos seis bloques
    de lado a lado; con radio 1,9, alto 1,2 y medio ancho 0,6 salía un aro estrecho con un penacho de cinco bloques.
  - 2 a 2,49: el mismo remolino, creciendo. La parte decimal dividida por 0,49 es cuánto ha crecido, de 0 a 1: las
    llamas amarillas de la cabeza van ganando el aro entero y suben hasta casi siete veces el alto de la banda, hasta
    taparlo casi todo de amarillo pálido justo antes de que salga la columna (1:12 → 1:13): una masa pálida y
    compacta, de unos siete bloques de ancho y tres y medio de alto, sobre la falda roja (1:13,5). Con la paleta del
    remolino, al crecer las llamas se inclinan hacia dentro hasta cerrarse en una cúpula pálida encima de Ace, y el
    penacho de la cabeza sube más (de 4,6 a 5,8 veces el alto de la banda): antes quedaba una media luna amarilla
    plana con un agujero en medio y sin nada encima de él. Con otra paleta ya no
    es rojo con llamas encima sino una sola masa del color de la paleta, casi lisa, con rayas largas un poco más
    claras tendidas a lo largo del giro y sin filo claro: con la 3 es el abanico dorado del Enkai de Marineford
    (1:24-1:25), dorado `#fbcb5a` casi entero.
    **Su esfera envolvente es mayor** que la de los demás anillos y tiene que ser la misma en `MeraVolumes.bound`:
    `hypot(1,25·radio + medio ancho + 1,5 ; alto·(4,4 + 2,8·crecido) + 0,5)`. Solo en anime; en realista, la de siempre.
  - 3 a 3,99: un tajo de fuego (los arcos de Shiranui, 2:00). Un arco de 144 grados: el canto de fuera rojo y con
    flecos, el medio naranja y el de dentro amarillo. `prm`: x = radio (2,2), y = grosor (0,35), z = medio ancho del
    arco (0,45). No gira solo: la parte decimal de `prm.w` dice por dónde va la cabeza del arco, en vueltas, contando
    desde el eje X de la forma hacia su eje Z (la cola queda detrás, hacia ángulos menores). Quien lo emite la va
    subiendo cada fotograma, por ejemplo de 0,25 a 0,72 en 0,35 s. El arco vive en el plano XZ de la forma: con
    `dir` = arriba − 0,3·frente queda de pie y de cara (X = la derecha de Ace, Z = arriba); con `dir` = −arriba +
    0,3·frente, lo mismo pero X = la izquierda de Ace y Z = abajo.
- **Sol (bola con `prm.w` = 2)**: una esfera lisa con unas pocas llamaradas finas pegadas a la superficie. Con la
  paleta 13 es la cúpula roja del final de Entei: el centro en el suelo, para que se vea media esfera. Con la paleta
  17 es el sol mientras nace: la misma esfera con una orilla ancha de llamas pequeñas (3:03-3:08).
- **Cruz (6)**: en anime hay dos, según el brazo (`prm.x`):
  - **Menos de 0,8: la cruz de los dedos** (2:14). Un «+» de brazos iguales, gordo y liso, amarillo limón con el
    centro pálido y mucho resplandor (paleta 7). Centrado un cuarto de brazo por encima del origen: va de −0,75 a
    1,25 brazos en vertical y de −1 a 1 en horizontal. El grosor queda siempre entre 0,27 y 0,32 brazos, pida lo que pida el emisor. Medidas de la serie: brazo
    0,35, justo delante de los dedos.
  - **0,8 o más: la cruz de llamas** (2:22). La cruz latina de siempre (el palo de abajo, más largo), hecha de
    llamas pequeñas: una soga dorada de bultos con la orilla pálida y un resplandor ancho (paleta 18). Medidas: brazo
    1,8 a 2,2. El grosor queda siempre entre 0,10 y 0,13 brazos, pida lo que pida el emisor.
- **Chorro (5) con la paleta 15 (o sin paleta) y radio pedido de 2,0 o más: el haz de Kagerō** (0:02). Un huso
  estrecho y liso, de un cuarto del radio pedido en lo más gordo (cerca de la mano), que acaba en punta: naranja con
  rayas rojas largas que corren por él, el filo amarillo y un resplandor amarillo. Con el radio por debajo de 2,0 es
  el chorro que se abre de siempre (las llamas pequeñas). Con `prm.z` = 1 es el de primera persona: fino en la mano
  y abriéndose con la distancia, que visto desde los ojos es una franja rayada de la mano a donde se apunta (el huso,
  visto a lo largo, era una semilla); va con `nearFade` 0,45.
- **Chorro (5) con la paleta 3: la banda dorada de St. Elmo** (2:09-2:10). Una masa dorada lisa con el canto en olas,
  unas pocas líneas amarillo pálido `#fde68f` finas y largas tendidas a lo largo, a trechos (ni franjas anchas ni
  manchas), y la punta redonda. `prm.z` (el grosor al salir, en partes del
  final) decide la silueta: 0,65 para la banda ancha del barrido (largo 9 y radio 1,0 a 1,3), que es casi igual de
  gruesa de punta a punta. La llama del antebrazo mientras carga (2:09) ya no es este chorro, que salía como una
  vaina lisa: es una fila de bolas con la paleta 18, que dan el borde en nubes de la serie.
- **Lanza (7)**: un trazo largo y limpio que se abre poco a poco desde una punta afilada, con dos rayas sueltas al
  lado. `prm.z` = 0: afilada por delante (las lanzas de Shiranui, 2:11). `prm.z` = 1: afilada por detrás y ancha por
  delante (las balas de Higan, 0:57). `prm.z` = 2: la columna de St. Elmo (2:11-2:12), afilada por delante pero que
  se abre enseguida (en el primer 30 % del largo) y sigue del mismo ancho hasta atrás; de lado es una raya gorda en
  punta y desde arriba una columna pálida de ancho parejo. El grosor máximo es 1,25 veces `prm.y` y, de punta a punta, la lanza mide 1,55 veces
  `prm.x`. Para que se parezca a la serie: bala de Higan, largo 2,6 y radio 0,45; lanza de St. Elmo, `prm.z` = 2, largo 11 y radio 0,38
  (con `prm.z` = 0 y radio 0,45 salía un cono larguísimo y fino). Con la paleta 14 el trazo va ondulado: son los
  chorros rojos que quedan cruzando el aire después de un Hiken (0:38), largo 20 a 28 y radio 0,5 a 0,8 (bandas
  gordas, con el rojo por todo el centro). Cada chorro va centrado en un punto distinto, al azar, alrededor del golpe
  (hasta 6 bloques a cada lado y de 1 a 9 por encima) y con su propia dirección, casi horizontal: así se cruzan como
  una red. Si salen todos del punto del golpe hacia fuera, se ve una estrella y no se parece. Con la paleta 21 es un brazo del estallido de Jūjika
  (2:23): afilado por las dos puntas, sin rayas sueltas, de filo blando y con el centro de la estrella casi blanco;
  con `prm.z` = 1 lo ancho queda hacia el centro.
- **Puño (1)**: la cabeza es casi una bola y se junta con su cuerpo de fuego por un cuello marcado, así que de lado se
  ve una bola con su aro amarillo y el cometa detrás (0:34). El cometa lleva el amarillo tres veces más ancho que el puño (hasta
  un 16 % del alto de la pantalla): de lado se ve casi tanto amarillo como rojo.
- **El borde de las masas (bolas y puño) no adelgaza con la distancia**: de lejos ocupa lo mismo en pantalla (un 3 % del
  alto), hasta 3,5 veces el ancho normal en el puño y 2,2 en las bolas. Con `VolStyle.z` > 0 manda ese valor.
- **Ni engorda al acercarse**: en cualquier forma, el borde no pasa de un 6 % del alto de la pantalla (un chorro visto
  en primera persona es amarillo con el filo naranja, no una mancha naranja).
- **Bola (0) con la paleta 19: la hoguera pálida de Hidaruma** (1:54). Una masa amarillo pálido con la orilla en
  llamitas doradas y, por dentro, llamitas sueltas que suben. Lo que arde dentro (los que han prendido, el suelo) se
  adivina a través del fuego, como las siluetas de la serie; el cielo y lo de detrás, no. Eso, solo con la cámara
  dentro o pegada a la hoguera: de lejos es una masa cerrada (si no, por la base se veía el suelo y parecía hueca).
  Una sola bola que los
  cubra a todos (centro en el medio del grupo, a 1,6 de alto; radio = lo que haga falta para taparlos más 2,5); con
  varias bolas pequeñas se ven los bordes de unas sobre otras.
- **Las bolas grandes** (más de 2,6 bloques de radio) no llevan lenguas más largas que una de 2,6: las bolsas tampoco
  crecen con la bola, y con lenguas de dos bloques sobre bolsas de uno una hoguera vista de lejos era un fleco de
  pelos con escalones. Y ninguna lengua llega a la esfera que envuelve la bola (antes las de los lados salían
  cortadas en arco).
- **Las manchas negras de la paleta 1** salen solo donde el fuego es hondo de verdad. Antes el suelo y lo que
  quedaba dentro de la bola contaban como hondo y salían pintados de negro (un óvalo negro en el suelo y un hueco
  negro alrededor de Ace al lanzar el Hiken). La hondura solo abre la puerta: de frente, con toda la bola detrás,
  siguen siendo esquirlas pequeñas y rotas (antes, un agujero negro redondo en medio del rojo, 0:33).
- **Llama (8) con la paleta 12**: una cuchilla larga y afilada (las de Hidaruma, 1:42), ancha cerca de la raíz y en
  punta arriba. Radio 0,10 a 0,15 y alto 0,9 a 1,4, con el borde (`VolStyle.z`) en 0,22; inclinadas unos 20 grados,
  todas hacia la derecha de quien mira, unas 22 repartidas en un plano de cara a la cámara: hasta 1,2 bloques a cada
  lado del cuerpo, por todo su alto, y casi todas un poco por delante de él (0,3 a 0,6 hacia la cámara). En anillo
  alrededor del cuerpo se amontonaban a los lados y se fundían en una bola amarilla.
  Ojo con `dir`: la llama sube por el eje Y local, no por `dir`. Para que suba inclinada 20 grados hacia un vector
  horizontal `r`, hay que pasar `dir` = `r`·cos 20° − arriba·sen 20°. Pasando (sen 20°, cos 20°, 0) las cuchillas
  salían tumbadas, casi horizontales.

### Con la cámara dentro del fuego

- **El resplandor es un halo, no un velo.** No llega más allá de un 14 % del alto de la pantalla, no se dibuja si el
  rayo empieza ya dentro del fuego y se aclara con el hueco de la cámara. Antes, con la cámara metida entre las
  llamas (las cuchillas de Hidaruma, un estallido, las nubes de la columna), cada volumen ponía su velo naranja y entre
  todos tapaban la pantalla.
- **Con la cámara pegada a las llamas el halo se apaga** (a menos de un grosor de llama de la superficie). Antes
  quedaba un velo naranja con rayas y el borde cortado justo donde la cámara entraba en el fuego (se veía al quedar
  dentro de una hoguera o de un estallido).
- **Dentro de una bola o explosión se ve el mundo**, sin nada pegado a la cara.
- **El fotograma del golpe (`VolStyle.w`)**: en la serie, quien queda dentro de un estallido ve la pantalla roja con
  llamas amarillas que salen del centro del golpe (0:37, 1:14, 1:42). Con `VolStyle.w` > 0 en una bola (forma 0) y la
  cámara dentro de ella, se pinta eso encima de todo, con esa opacidad: el fondo es el color más rojo de la paleta y
  las llamas el más amarillo. Con la paleta 1 son cuñas rectas y finas con esquirlas negras (el Hiken); con las demás,
  lenguas curvas con el centro pálido. Lo pide quien emite la explosión y solo unas décimas: 1 hasta 0,30 s y de ahí
  a 0 en 0,55 s. Con la cámara fuera de la bola no hace nada. Con `VolStyle.w` de 2 a 3 se pinta solo el fotograma
  (con fuerza w − 2) y la bola no se dibuja: para el golpe que lleva otra paleta que su bola (el Hiken: la bola
  limpia con la 16 y, en el mismo sitio, otra con la 1 que solo pone el fotograma de las esquirlas) y para el que no
  tiene bola que ver desde fuera (Hidaruma sobre un solo blanco). Las lenguas curvas son finas y, donde se acaban,
  no queda nada (antes un fallo de cuentas llenaba media pantalla de amarillo pálido al mirar hacia un lado).
- **Dentro de la cúpula de Entei (paleta 13)** todo se ve teñido de rojo, sin tapar la vista (3:22-3:29).
- **El humo no tiene resplandor**: antes oscurecía lo de detrás, como una sombra alrededor de cada bocanada.

### Forma nueva: 9, AURA (el cuerpo en llamas)

El fuego que envuelve a Ace cuando arde. Una funda alrededor del cuerpo de la que salen lenguas grandes y separadas, que
suben, se rizan y llegan a un cuerpo de ancho a cada lado. En anime solo se dibuja la mitad de detrás: el cuerpo se ve entero y el fuego asoma por su contorno, como en la
serie (2:29-2:31 y 2:53-2:55).

- Origen en los pies, `dir` horizontal (hacia donde mira el cuerpo), +Y arriba.
- `prm`: x = radio del cuerpo (0,45), y = alto (1,9), z = fuerza de las llamas de 0 a 1, w = fase de 0 a 1.
- Su esfera envolvente es la de la llama (la rama `default` de `MeraVolumes.bound`): no hay que tocar nada.
- Solo para el estilo anime y solo para quien se ve desde fuera. En realista se sigue usando lo de siempre.

## Lo que no es volumen (Java)

En estilo anime:

- **Humo**: no se emite. La bocanada gris de las explosiones ya la pone el volumen.
- **Bolas y lenguas sueltas** (`fire`, `tongue`, las de `Flame3D`): los tres tonos de la paleta de su técnica, planos:
  por fuera el borde, dentro la masa. Sin mezclar hacia rojo oscuro ni negro al morir: se encogen y desaparecen.
- **Chispas**: motas amarillas `#fdd23a` planas, cortas y alargadas en su dirección. No se oscurecen.
- **Resplandores** (`glow`, `bloom`, el halo del vuelo): a un tercio de su fuerza. En la serie el resplandor es corto.
- **Núcleo blanco** (`core`): amarillo pálido `#fff6b0` plano.
- **Vapor**: blanco plano.
- **Cuerpo de cubitos** (`MeraVoxelBody`): masa `#fbcb5a` y borde `#fde9a0` (la paleta 3), planos.
- La luz sobre el mundo (`mera_light`) se queda como está.

## Técnica por técnica

| Técnica | Paleta | Cómo es en el vídeo |
| --- | --- | --- |
| Hiken | 1, 16 y 14 | Un puño enorme y redondo: masa roja, borde amarillo, líneas amarillas entre los dedos. Va dibujado encima de su cuerpo de fuego, con su propio contorno (0:32). Detrás, un cuerpo de fuego que se ensancha hasta el doble del puño (un cometa, 0:34), con vetas amarillas y cintas amarillas finas que se rizan alrededor. Al salir del brazo, masas rojas con manchas negras (bolas con paleta 1, 0:24). El golpe es una media bola roja y limpia con el filo amarillo (bola con paleta 16, 0:36) y, para quien queda dentro, la pantalla roja con cuñas amarillas y esquirlas negras (otra bola en el mismo sitio con paleta 1 y `VolStyle.w` de 2 a 3, que solo pone el fotograma, 0:37). Después quedan tres segundos y medio de chorros rojos con el borde amarillo cruzándose en el aire como una red: 10 lanzas con paleta 14, de 20 a 28 de largo y 0,5 a 0,8 de radio, cada una centrada en un punto al azar alrededor del golpe y con su propia dirección, y un tronco más gordo por donde pasó el puño, que acaba en el golpe (otra lanza con paleta 14, radio 1,1 y `prm.x` 9 como mucho, que son 14 bloques de punta a punta; no se acerca a menos de 4 bloques de quien lo lanzó, 0:38-0:41). El golpe del Hiken no se enfría en humo blanco: la bola sigue siendo fuego y se apaga pronto (ya no está cuando se ven los chorros). |
| Kagerō | 15 y 2 | Un haz estrecho que acaba en punta, naranja con rayas rojas y el filo amarillo (chorro con radio pedido 2,2 o más, 0:02); al dar en el blanco, una nube blanca y, el primer medio segundo, bolas amarillas con el borde rojo anaranjado (bolas con paleta 2, de 1,6 a 2,4 de radio, 0:17). |
| Higan | 11 | Cuñas amarillo pálido con el filo dorado blando: nacen en punta junto a Ace y se abren hacia delante (lanza con `prm.z` = 1, sin bola en la cabeza). |
| Hibashira | 10, 9, 20 y 22 | Primero el remolino: un aro rojo que gira alrededor de Ace a la altura de la cintura, con llamas amarillas en el brazo (anillo con `prm.w` = 2), que va creciendo hasta ser casi todo llama amarilla pálida (`prm.w` de 2 a 2,49). Al salir la columna, quien está dentro ve la pantalla roja con llamas amarillas (1:14). Después el haz recto amarillo pálido, fino y alto (columna con radio pedido 2,1), pegado a él un muro de humo melocotón (anillo con `prm.w` = 1,25 y paleta 22) y detrás una masa roja con racimos de llamas amarillas (anillo con `prm.w` = 1,25 y paleta 20, 1:16-1:18). |
| Enkai | 3 | Dos brazos de fuego dorado que giran alrededor de Ace y se abren deprisa hasta llenar la pantalla (1:24-1:25): dos remolinos (anillo con `prm.w` de 2 a 2,49 y paleta 3), uno con `dir` al revés que el otro. |
| Hotarubi | 5 | Bolas lisas y redondas (no llamas), de 0,2 a 0,45 de radio: verdes en la orilla, casi blancas en el centro y con un halo verde suave. Una bola (forma 0) con paleta 5 sale ya así. |
| Hidaruma | 12, 16, 3 y 19 | Sobre el que arde, unas 22 cuchillas de fuego finas y afiladas, amarillas con el filo naranja y el centro pálido, sueltas, inclinadas hacia el mismo lado y repartidas por delante del cuerpo y a sus lados (llamas con paleta 12, 1:42). No hay bola roja a la vista: quien queda dentro ve la pantalla roja con llamas amarillas (una bola con paleta 16 y `VolStyle.w` de 2 a 3, que solo pone el fotograma, 1:42-1:43). Cuando prenden varios a la vez: un fogonazo dorado (`VolStyle.w` en una bola con paleta 3, 1:53) y después una hoguera amarillo pálido enorme que los envuelve a todos y deja adivinar sus siluetas (una bola con paleta 19, 1:54-1:56). Dentro de la hoguera no se dibujan las cuchillas de cada víctima: se veían como un garabato naranja en medio del fuego pálido. |
| Enjōmō | 3 | Un muro amarillo pálido que llena la pantalla. |
| St. Elmo / Shiranui | 2, 3, 12 y 11 | Shiranui: una bola de fuego en cada mano (bola con paleta 2, 1:59), después los arcos naranja rojizo con el centro amarillo (2:00) y las lanzas, cometas amarillo vivo con el filo y el halo naranjas, anchos en la cabeza y en punta por detrás (lanza con `prm.z` = 1 y paleta 12, largo 4,5 y radio 0,45 a 0,55, 2:00,5-2:02). St. Elmo: mientras carga, una nube dorada de borde ondulado le sale del antebrazo hacia atrás y se va ensanchando (seis bolas con paleta 18 en fila, de radio 0,3 a 0,9, a lo largo de 3,2 bloques, 2:09); al soltar, el brazo barre y deja una banda dorada ancha que cruza por delante de Ace (chorro con paleta 3, largo, siguiendo a la mano, 2:10); las lanzas, columnas amarillo pálido muy largas y de ancho parejo, afiladas solo en la punta (lanza con `prm.z` = 2, paleta 11, largo 11 y radio 0,38, 2:11), que se clavan en el blanco y dejan fuego bajo en el suelo (2:12). |
| Jūjika | 7, 11, 15, 18, 21 y 3 | Primero la cruz de los dedos: un «+» pequeño y gordo, amarillo limón, delante de las manos cruzadas (cruz con brazo 0,35, paleta 7, 2:14). De ella sale un haz fino amarillo pálido, casi transparente, que llega al blanco en medio segundo con la punta brillante (lanza con `prm.z` = 2, paleta 11 y radio 0,14; con 0,06 de lado era un hilo de un píxel, 2:15-2:16); después, por el haz corre desde las manos una llamarada roja con el filo naranja y amarillo, alargada, que suelta jirones rojos (chorro o bolas con paleta 15, 2:16-2:17). Donde llega, la cruz grande de llamas doradas (cruz con brazo 1,8 a 2,2, paleta 18, 2:22) y enseguida un estallido dorado gordo, en estrella, sin bola en el centro: cuatro brazos grandes en línea con los de la cruz (lanzas con `prm.z` = 1, largo 5 y radio 2,0: con radio 1,1 eran agujas finas) y cuatro menores entre ellos (largo 2,8, radio 1,3), todos con la paleta 21 y casi en el plano de cara a quien la lanzó, con no más de 5 grados de inclinación (2:23). No es una cúpula ni lleva el fotograma del golpe. |
| Cuerpo en llamas | 3 | Lenguas doradas con el filo amarillo pálido y el contorno ondulado; la piel se ve dentro. Un volumen AURA (forma 9) en vez de las llamas sueltas de hombros, brazos y espalda. |
| Kyōkaen | 3 | Un muro dorado con venas amarillo pálido. El vapor, blanco plano. |
| Dai Enkai | 2 | Primero un aro fino de fuego alrededor de Ace, de bloque y medio de radio (2:58); enseguida un disco naranja con dos o tres aros amarillos ondulados a ras de suelo, de unos dos bloques y medio de radio, con Ace de pie en el centro y sin hueco a sus pies (anillo con `prm.w` = 0, radio 1,0, medio grosor 1,5 y alto 0,35, 2:59; con el grosor igual al radio quedaba un agujero en el centro). Después crece y se levantan las llamas rojas y amarillas con el centro pálido. |
| Entei | 17, 6 y 13 | Mientras crece, un sol casi blanco con una orilla ancha de llamas amarillas y naranjas (paleta 17, 3:03-3:08). Ya formado, liso, con un filo amarillo muy fino, un resplandor naranja ancho y unas pocas llamaradas finas, amarillo dorado, onduladas y afiladas por las dos puntas, tendidas sobre su cara (las pinta el sombreador en la superficie; paleta 6, 3:09). En anime el sol no lleva llamas sueltas ni la lengua de la mano al sol. Al estallar, una cúpula roja con grietas naranjas (bola lisa con paleta 13, 3:32); desde dentro todo se ve teñido de rojo. |
