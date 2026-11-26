# Estilo anime de la Mera Mera no Mi

Objetivo: que el fuego de Ace se vea como en la serie (referencia: el recopilatorio «All Attacks and Abilities», 3:40).
El fuego realista no se quita: queda en la opción «Estilo del fuego: Anime | Realista» (por defecto, Anime).

## Qué tiene el fuego de la serie

- Colores planos. Dos o tres tonos por llama, con el corte limpio entre ellos. Nada de degradados ni de humo.
- Un borde grueso, del mismo ancho por todo el contorno, y por fuera un resplandor corto.
- Venas del color del borde por los surcos entre los lóbulos (en el puño de Hiken, entre los dedos).
- Casi sin humo: solo en las explosiones grandes y es gris claro plano, con los surcos más oscuros.
- Animado «a doses»: la forma cambia 12 veces por segundo, no en cada fotograma.
- Motas sueltas amarillas alrededor (rayitas cortas), no chispas que se apagan en rojo oscuro.

## El shader (`mera_volume.fsh`)

`uniform vec4 VolStyle`:

| Componente | Qué es |
| --- | --- |
| x | 0 = realista (el de siempre, sin cambios), 1 = anime |
| y | paleta; 0 = la de la forma (ver abajo) |
| z | ancho del borde en grosores de llama; 0 = el de la paleta |
| w | sin usar |

En anime se llama a `mfRenderToon` en vez de `mfRender`. Recorre el mismo volumen, pero en vez de sumar luz mide cuánto
se mete el rayo en la forma (el borde y el corazón), dónde la toca primero (las venas) y lo cerca que pasó (el
resplandor). Sale premultiplicado, igual que el realista.

### Paletas

| N.º | Nombre | Borde | Masa | Corazón | Dónde sale en el vídeo |
| --- | --- | --- | --- | --- | --- |
| 1 | Hiken | amarillo `#fdc82a` (y venas) | rojo `#f55749` | manchas negras con filo rojo oscuro | 0:24-0:40 |
| 2 | natural | rojo anaranjado `#f14a21` | amarillo `#fdcc2e` | amarillo pálido `#fff6b0` | 1:12-1:17, 1:42, 3:00-3:06 |
| 3 | Marineford | amarillo pálido `#fde9a0` (fino; no hay borde oscuro) | dorado `#fbcb5a`, venas pálidas `#feeca8` | `#fcd369` | 0:08, 1:22-1:32, 1:55, 2:29-2:54 |
| 4 | Kagerō | naranja `#ec7821` | amarillo vivo `#f5e024` | pálido `#fff58f` | 0:15-0:17 |
| 5 | Hotarubi | verde `#a3f08f` | verde pálido `#ccfc99` | casi blanco `#f2ffd9` | 1:34-1:53 |
| 6 | sol de Entei | filo amarillo muy fino `#f9db66` | `#fbf8bd` | casi blanco `#feffdb` (casi todo el sol), resplandor ancho | 3:04-3:13 |
| 7 | Jūjika | filo dorado `#fdcc63` | amarillo limón `#fef580` | pálido `#fffda6` | 2:14 |
| 8 | humo | gris `#8f8a8f` | gris claro `#dbd9db` | `#f5f4f5` | lo que queda de una explosión |
| 9 | columna | filo naranja `#f6b030` | banda amarilla `#fee63d` | amarillo pálido `#fcf199` (casi todo el haz) | 1:16-1:17 |
| 10 | remolino | no tiene | rojo plano `#de352b` | llamas amarillas `#fdcc2e` con el centro `#fdf68f` y el filo naranja | 1:12-1:13 |

Paleta de cada forma cuando `VolStyle.y` es 0: bola 2 (el sol, 6), puño 1, columna 9 (vista desde dentro, 2), anillos 2
(el remolino, 10), muro 3, chorro 4, cruz 7, lanza 3, llama 3. Una explosión que se enfría pasa sola a la 8.

### Formas que cambian en anime

- **Columna (2)** vista desde fuera (`prm.z` = 0): un haz recto y liso, sin hueco, que se abre en el pie y arriba
  (1:16). El haz ocupa la mitad del radio pedido. La nube roja y amarilla de alrededor son bolas aparte (paleta 2).
- **Anillo (3)** según `prm.w`:
  - 0: fuego a ras de suelo. Naranja con aros amarillos finos y la orilla más roja (el disco del Dai Enkai, 2:59).
    Con el grosor igual al radio es un disco lleno.
  - 1: la cortina de lenguas de siempre (Enjōmō).
  - 2: el remolino de Hibashira (1:12). Una media luna roja plana que gira sola alrededor del centro (casi una
    vuelta entera, gruesa en el medio y en punta en los extremos), con llamas amarillas que suben por la cabeza.
    `prm`: x = radio (2), y = alto de la banda (0,5), z = medio ancho de la banda (0,95). Origen a la altura de la
    cintura menos medio alto.
- **Sol (bola con `prm.w` = 2)**: una esfera lisa con unas pocas llamaradas finas pegadas a la superficie.

### Forma nueva: 9, AURA (el cuerpo en llamas)

El fuego que envuelve a Ace cuando arde. Una funda alrededor del cuerpo de la que salen lenguas grandes que suben y se
rizan. En anime solo se dibuja la mitad de detrás: el cuerpo se ve entero y el fuego asoma por su contorno, como en la
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
| Hiken | 1 | Un puño enorme y redondo: masa roja, borde amarillo, líneas amarillas entre los dedos. Va dibujado encima de su cuerpo de fuego, con su propio contorno (0:32). Detrás, un cuerpo de fuego que se ensancha hasta el doble del puño (un cometa, 0:34), con vetas amarillas y cintas amarillas finas que se rizan alrededor. Al salir del brazo, masas rojas con manchas negras (bolas con paleta 1, 0:24). |
| Kagerō | 4 | Chorro amarillo vivo con el borde naranja y un resplandor rojizo. |
| Higan | 3 | Rayas amarillo pálido muy largas y finas, con brillo. |
| Hibashira | 10, 9 y 2 | Primero el remolino: una media luna roja que gira alrededor de Ace a la altura de la cintura, con llamas amarillas en el brazo (anillo con `prm.w` = 2). Después el haz recto amarillo pálido (columna) y, alrededor de su pie y de su punta, una nube de bolas rojas y amarillas (paleta 2). |
| Enkai | 3 | Un abanico amarillo anaranjado plano que sale de la mano, con rayas más pálidas. |
| Hotarubi | 5 | Bolitas verde pálido con el centro casi blanco y un halo verde. |
| Hidaruma | 2 | Lenguas amarillas con el borde naranja sobre el que arde. |
| Enjōmō | 3 | Un muro amarillo pálido que llena la pantalla. |
| St. Elmo / Shiranui | 2 y 3 | Arcos naranja rojizo con el centro amarillo; las lanzas, rayas amarillas. |
| Jūjika | 7 y 1 | La cruz, dos trazos gruesos amarillo limón con el centro pálido y un resplandor (el grosor, un quinto del brazo); el estallido, rojo con borde amarillo. |
| Cuerpo en llamas | 3 | Lenguas doradas con el filo amarillo pálido y el contorno ondulado; la piel se ve dentro. Un volumen AURA (forma 9) en vez de las llamas sueltas de hombros, brazos y espalda. |
| Kyōkaen | 3 | Un muro dorado con venas amarillo pálido. El vapor, blanco plano. |
| Dai Enkai | 2 | Primero un disco naranja con aros amarillos a ras de suelo, de unos 4 bloques de radio, con Ace en el centro (anillo con `prm.w` = 0, grosor = radio, alto 0,35). Después crece y se levantan las llamas rojas y amarillas con el centro pálido. |
| Entei | 6 | Un sol casi blanco, liso, con un filo amarillo muy fino, un resplandor naranja ancho y unas pocas llamaradas finas. Al estallar, pantalla en blanco y después una cúpula roja. |
