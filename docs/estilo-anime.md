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
| w | el fotograma del golpe, de 0 a 1 (ver abajo); 0 = nada |

En anime se llama a `mfRenderToon` en vez de `mfRender`. Recorre el mismo volumen, pero en vez de sumar luz mide cuánto
se mete el rayo en la forma (el borde y el corazón), dónde la toca primero (las venas) y lo cerca que pasó (el
resplandor). Sale premultiplicado, igual que el realista.

### Paletas

| N.º | Nombre | Borde | Masa | Corazón | Dónde sale en el vídeo |
| --- | --- | --- | --- | --- | --- |
| 1 | Hiken | amarillo `#fdc82a` (y venas) | rojo `#f55749` | manchas negras con filo rojo oscuro | 0:24-0:40 |
| 2 | natural | rojo anaranjado `#f14a21`, y por dentro una banda naranja `#f77d1c` | amarillo `#fdcc2e` | amarillo pálido `#fff6b0` | 1:12-1:17, 1:45, 3:00-3:06 |
| 3 | Marineford | amarillo pálido `#fde9a0` (fino; no hay borde oscuro) | dorado `#fbcb5a`, venas pálidas `#feeca8` | `#fcd369` | 0:08, 1:22-1:32, 1:55, 2:29-2:54 |
| 4 | rayo amarillo | naranja `#ec7821` | amarillo vivo `#f5e024` | pálido `#fff58f` | 0:15-0:17 (Ace contra Yamato; ya no es la de Kagerō) |
| 5 | Hotarubi | verde `#73f594` en la orilla | se aclara poco a poco, sin bandas | verde muy pálido `#ccfccf`, halo verde `#57d175` | 1:34-1:53 |
| 6 | sol de Entei | filo amarillo muy fino `#f9db66` | `#fbf8bd` | casi blanco `#feffdb` (casi todo el sol), resplandor ancho | 3:04-3:13 |
| 7 | Jūjika | filo dorado `#fdcc63` | amarillo limón `#fef580` | pálido `#fffda6` | 2:14 |
| 8 | humo | gris azulado `#b3bad4` (fino, y los surcos `#a8b1cf`) | blanco `#e7ecf6` | igual que la masa | lo que queda de una explosión (0:02) |
| 9 | columna | filo naranja `#f6b030` | banda amarilla `#fee63d` | amarillo pálido `#fcf199` (casi todo el haz) | 1:16-1:17 |
| 10 | remolino | no tiene | rojo plano `#de352b` | llamas amarillas `#fdcc2e` con el centro `#fdf68f` y el filo naranja | 1:12-1:13 |
| 11 | trazo | filo dorado `#f5c85a`, blando (sin línea) | amarillo pálido `#fdf3a6` | igual que la masa | 0:57, 2:11 |
| 12 | Hidaruma | filo naranja `#f18113`, blando (sin línea) | amarillo `#fccf1e` | amarillo pálido `#fff160` | 1:42 |
| 13 | cúpula | filo naranja fino `#ff8a23` y grietas del mismo color | rojo plano `#f12316` | igual que la masa, con mucho resplandor rojo | 3:20-3:32 |
| 14 | estela | amarillo anaranjado `#fdb238`, ancho | rojo `#f45d40` (solo en lo gordo del chorro) | igual que la masa, sin manchas | 0:38-0:40 |
| 15 | Kagerō | amarillo `#febd38` | una banda naranja `#fe852b` y, más adentro, rojo `#de3429` que entra en lenguas | igual que la masa | 0:02-0:05 |
| 16 | golpe | amarillo `#fdc82a`, ancho | rojo `#f55749` plano y limpio: sin venas ni manchas, y el contorno más liso | igual que la masa | 0:36, 1:17 |
| 17 | sol naciendo | una orilla ancha de llamas: amarillo `#fed640` con picos naranjas `#f68f21` que corren por ella, filo naranja por fuera y más pálida hacia dentro | `#fefdd0` | igual que la masa | 3:03-3:08 |
| 18 | cruz de llamas | pálido `#fcde8f` | dorado `#fccc5d`, liso, sin venas | `#fed470`, con un resplandor ancho | 2:22 |
| 19 | hoguera pálida | dorado `#f5c245`, en llamitas pequeñas | amarillo pálido `#fdf1a3`, con llamitas doradas sueltas que suben | igual que la masa | 1:54-1:56 |

Paleta de cada forma cuando `VolStyle.y` es 0: bola 2 (el sol, 6), puño 1, columna 9 (vista desde dentro, 2), anillos 2
(el remolino, 10), muro 3, chorro 15, cruz 7 (la grande, con el brazo de 0,8 o más, 18), lanza 11, llama 3. Una explosión que se enfría pasa sola a la 8.

### Formas que cambian en anime

- **Columna (2)** vista desde fuera (`prm.z` = 0): un haz recto y liso, sin hueco, que se abre en el pie y arriba
  (1:16). El haz ocupa la mitad del radio pedido. La nube roja y amarilla de alrededor son bolas aparte (paleta 2).
- **Anillo (3)** según `prm.w`:
  - 0: fuego a ras de suelo. Naranja con aros amarillos y la orilla más roja (el disco del Dai Enkai, 2:59). Los
    aros van a uno por bloque más o menos (da igual el tamaño del disco), ondulados, con el centro pálido y algún
    corte, y corren hacia fuera. Con el grosor igual al radio es un disco lleno.
  - 1: la cortina de lenguas de siempre (Enjōmō).
  - 2: el remolino de Hibashira (1:12). Una media luna roja plana que gira sola alrededor del centro (casi una
    vuelta entera, gruesa en el medio y en punta en los extremos), con llamas amarillas que suben por la cabeza.
    `prm`: x = radio (1,9), y = alto de la banda (1,0), z = medio ancho de la banda (0,6). Origen a la altura de la
    cintura menos medio alto. Con esas medidas se lee un aro alrededor de Ace, como en la serie; más ancho y más bajo
    parece un charco.
  - 2 a 2,49: el mismo remolino, creciendo. La parte decimal dividida por 0,49 es cuánto ha crecido, de 0 a 1: las
    llamas amarillas de la cabeza van ganando el aro entero y suben hasta casi siete veces el alto de la banda, hasta
    taparlo casi todo de amarillo pálido justo antes de que salga la columna (1:12 → 1:13). Con otra paleta las
    llamas van en sus colores: con la 3 es el remolino dorado del Enkai de Marineford (1:24).
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
- **Chorro (5) con la paleta 3: la banda dorada de St. Elmo** (2:09-2:10). Una masa dorada con el canto en olas, rayas
  amarillo pálido onduladas que corren a lo largo y la punta redonda. `prm.z` (el grosor al salir, en partes del
  final) decide la silueta: 0,35 para la llama que le sale del antebrazo (largo 3,2 y radio 0,9); 0,65 para la banda
  ancha del barrido (largo 9 y radio 1,0 a 1,3), que es casi igual de gruesa de punta a punta.
- **Lanza (7)**: un trazo largo y limpio que se abre poco a poco desde una punta afilada, con dos rayas sueltas al
  lado. `prm.z` = 0: afilada por delante (las lanzas de Shiranui, 2:11). `prm.z` = 1: afilada por detrás y ancha por
  delante (las balas de Higan, 0:57). El grosor máximo es 1,25 veces `prm.y`. Para que se parezca a la serie: bala
  de Higan, largo 2,6 y radio 0,45; lanza de Shiranui, largo 9 o más y radio 0,5. Con la paleta 14 el trazo va
  ondulado: son los chorros rojos que quedan cruzando el aire después de un Hiken (0:38), largo 6 a 8 y radio 0,6 a 1.
- **Puño (1)**: la cabeza es casi una bola y se junta con su cuerpo de fuego por un cuello marcado, así que de lado se
  ve una bola con su aro amarillo y el cometa detrás (0:34). El cometa lleva el amarillo tres veces más ancho que el puño (hasta
  un 16 % del alto de la pantalla): de lado se ve casi tanto amarillo como rojo.
- **El borde de las masas (bolas y puño) no adelgaza con la distancia**: de lejos ocupa lo mismo en pantalla (un 3 % del
  alto), hasta 3,5 veces el ancho normal en el puño y 2,2 en las bolas. Con `VolStyle.z` > 0 manda ese valor.
- **Ni engorda al acercarse**: en cualquier forma, el borde no pasa de un 6 % del alto de la pantalla (un chorro visto
  en primera persona es amarillo con el filo naranja, no una mancha naranja).
- **Bola (0) con la paleta 19: la hoguera pálida de Hidaruma** (1:54). Una masa amarillo pálido con la orilla en
  llamitas doradas y, por dentro, llamitas sueltas que suben. Lo que arde dentro (los que han prendido, el suelo) se
  adivina a través del fuego, como las siluetas de la serie; el cielo y lo de detrás, no. Una sola bola que los
  cubra a todos (centro en el medio del grupo, a 1,6 de alto; radio = lo que haga falta para taparlos más 2,5); con
  varias bolas pequeñas se ven los bordes de unas sobre otras.
- **Llama (8) con la paleta 12**: una cuchilla larga y afilada (las de Hidaruma, 1:42), ancha cerca de la raíz y en
  punta arriba. Radio 0,15 a 0,21 y alto 1,3 a 1,9; inclinadas unos 20 grados, todas hacia el mismo lado.

### Con la cámara dentro del fuego

- **El resplandor es un halo, no un velo.** No llega más allá de un 14 % del alto de la pantalla, no se dibuja si el
  rayo empieza ya dentro del fuego y se aclara con el hueco de la cámara. Antes, con la cámara metida entre las
  llamas (las cuchillas de Hidaruma, un estallido, las nubes de la columna), cada volumen ponía su velo naranja y entre
  todos tapaban la pantalla.
- **Dentro de una bola o explosión se ve el mundo**, sin nada pegado a la cara.
- **El fotograma del golpe (`VolStyle.w`)**: en la serie, quien queda dentro de un estallido ve la pantalla roja con
  llamas amarillas que salen del centro del golpe (0:37, 1:14, 1:42). Con `VolStyle.w` > 0 en una bola (forma 0) y la
  cámara dentro de ella, se pinta eso encima de todo, con esa opacidad: el fondo es el color más rojo de la paleta y
  las llamas el más amarillo. Con la paleta 1 son cuñas rectas y finas con esquirlas negras (el Hiken); con las demás,
  lenguas curvas con el centro pálido. Lo pide quien emite la explosión y solo unas décimas: 1 hasta 0,30 s y de ahí
  a 0 en 0,55 s. Con la cámara fuera de la bola no hace nada.
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
| Hiken | 1, 16 y 14 | Un puño enorme y redondo: masa roja, borde amarillo, líneas amarillas entre los dedos. Va dibujado encima de su cuerpo de fuego, con su propio contorno (0:32). Detrás, un cuerpo de fuego que se ensancha hasta el doble del puño (un cometa, 0:34), con vetas amarillas y cintas amarillas finas que se rizan alrededor. Al salir del brazo, masas rojas con manchas negras (bolas con paleta 1, 0:24). El golpe es una media bola roja y limpia con el filo amarillo (bola con paleta 16, 0:36) y, para quien queda dentro, la pantalla roja con cuñas amarillas y esquirlas negras (`VolStyle.w` con paleta 1, 0:37). Después quedan unos segundos chorros rojos con el borde amarillo cruzando el aire (lanzas con paleta 14, 0:38-0:40). |
| Kagerō | 15 | Un chorro naranja con el filo amarillo y vetas rojas por el centro (0:02); al dar en el blanco, masas rojas y naranjas con el filo amarillo que lo envuelven (bolas con paleta 15, 0:03-0:05). |
| Higan | 11 | Cuñas amarillo pálido con el filo dorado blando: nacen en punta junto a Ace y se abren hacia delante (lanza con `prm.z` = 1, sin bola en la cabeza). |
| Hibashira | 10, 9 y 16 | Primero el remolino: un aro rojo que gira alrededor de Ace a la altura de la cintura, con llamas amarillas en el brazo (anillo con `prm.w` = 2), que va creciendo hasta ser casi todo llama amarilla pálida (`prm.w` de 2 a 2,49). Al salir la columna, quien está dentro ve la pantalla roja con llamas amarillas (1:14). Después el haz recto amarillo pálido, fino y alto (columna), y a los lados nubes rojas con el filo amarillo, sin manchas negras (bolas con paleta 16, 1:17). |
| Enkai | 3 | Dos brazos de fuego dorado que giran alrededor de Ace y se abren deprisa hasta llenar la pantalla (1:24-1:25): dos remolinos (anillo con `prm.w` de 2 a 2,49 y paleta 3), uno con `dir` al revés que el otro. |
| Hotarubi | 5 | Bolas lisas y redondas (no llamas), de 0,2 a 0,45 de radio: verdes en la orilla, casi blancas en el centro y con un halo verde suave. Una bola (forma 0) con paleta 5 sale ya así. |
| Hidaruma | 12, 16, 3 y 19 | Sobre el que arde, 12 a 16 cuchillas de fuego largas y afiladas, amarillas con el filo naranja y el centro pálido, inclinadas hacia el mismo lado y tapándole el cuerpo (llamas con paleta 12, 1:42). Quien queda dentro ve la pantalla roja con llamas amarillas (`VolStyle.w` en una bola con paleta 16, 1:42-1:43). Cuando prenden varios a la vez: un fogonazo dorado (`VolStyle.w` en una bola con paleta 3, 1:53) y después una hoguera amarillo pálido enorme que los envuelve a todos y deja adivinar sus siluetas (una bola con paleta 19, 1:54-1:56). |
| Enjōmō | 3 | Un muro amarillo pálido que llena la pantalla. |
| St. Elmo / Shiranui | 2, 3 y 11 | Shiranui: una bola de fuego en cada mano (bola con paleta 2, 1:59) y después los arcos naranja rojizo con el centro amarillo (2:00). St. Elmo: mientras carga, una llama dorada con rayas pálidas le sale del antebrazo hacia atrás (chorro con paleta 3, 2:09); al soltar, el brazo barre y deja una banda dorada ancha que cruza por delante de Ace (el mismo chorro, largo, siguiendo a la mano, 2:10); las lanzas, trazos amarillo pálido muy largos, afilados por delante (paleta 11, radio 0,45, 2:11), que se clavan en el blanco y dejan fuego bajo en el suelo (2:12). |
| Jūjika | 7, 11, 15, 18 y 3 | Primero la cruz de los dedos: un «+» pequeño y gordo, amarillo limón, delante de las manos cruzadas (cruz con brazo 0,35, paleta 7, 2:14). De ella sale un haz fino amarillo pálido, casi transparente, que llega al blanco en medio segundo con la punta brillante (lanza con paleta 11 y radio 0,06, 2:15-2:16); después, por el haz corre desde las manos una llamarada roja con el filo naranja y amarillo, alargada, que suelta jirones rojos (chorro o bolas con paleta 15, 2:16-2:17). Donde llega, la cruz grande de llamas doradas (cruz con brazo 1,8 a 2,2, paleta 18, 2:22) y enseguida un estallido dorado con cuatro rayos en cruz (bola con paleta 3 y cuatro lanzas, 2:23). |
| Cuerpo en llamas | 3 | Lenguas doradas con el filo amarillo pálido y el contorno ondulado; la piel se ve dentro. Un volumen AURA (forma 9) en vez de las llamas sueltas de hombros, brazos y espalda. |
| Kyōkaen | 3 | Un muro dorado con venas amarillo pálido. El vapor, blanco plano. |
| Dai Enkai | 2 | Primero un aro fino de fuego alrededor de Ace, de bloque y medio de radio (2:58); enseguida un disco naranja con aros amarillos ondulados a ras de suelo, de unos 4 bloques de radio, con Ace en el centro (anillo con `prm.w` = 0, grosor = radio, alto 0,35, 2:59). Después crece y se levantan las llamas rojas y amarillas con el centro pálido. |
| Entei | 17, 6 y 13 | Mientras crece, un sol casi blanco con una orilla ancha de llamas amarillas y naranjas (paleta 17, 3:03-3:08). Ya formado, liso, con un filo amarillo muy fino, un resplandor naranja ancho y unas pocas llamaradas finas (paleta 6, 3:09). Al estallar, una cúpula roja con grietas naranjas (bola lisa con paleta 13, 3:32); desde dentro todo se ve teñido de rojo. |
