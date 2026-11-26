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
| 3 | Marineford | naranja suave `#f9b040` | amarillo anaranjado `#fcc95e`, venas pálidas `#fee899` | `#fde08a` | 0:08, 1:22-1:32, 1:55, 2:29-2:54 |
| 4 | Kagerō | naranja `#ec7821` | amarillo vivo `#f5e024` | pálido `#fff58f` | 0:15-0:17 |
| 5 | Hotarubi | verde `#a3f08f` | verde pálido `#ccfc99` | casi blanco `#f2ffd9` | 1:34-1:53 |
| 6 | sol de Entei | naranja fino `#f47d14` | amarillo `#fee33b` | blanco amarillento `#fffbcc` | 3:04-3:13 |
| 7 | Jūjika | naranja `#f68526` | amarillo `#fdcc54` | pálido `#feeda3` | 2:14 |
| 8 | humo | gris `#8f8a8f` | gris claro `#dbd9db` | `#f5f4f5` | lo que queda de una explosión |

Paleta de cada forma cuando `VolStyle.y` es 0: bola 2 (el sol, 6), puño 1, columna 2, anillos 2, muro 3, chorro 4,
cruz 7, lanza 3, llama 3. Una explosión que se enfría pasa sola a la 8.

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
- **Cuerpo de cubitos** (`MeraVoxelBody`): masa `#fde08a` y borde `#fcc95e` (la paleta 3), planos.
- La luz sobre el mundo (`mera_light`) se queda como está.

## Técnica por técnica

| Técnica | Paleta | Cómo es en el vídeo |
| --- | --- | --- |
| Hiken | 1 | Un puño enorme: masa roja, borde amarillo grueso, líneas amarillas entre los dedos, alguna mancha negra. Detrás, un brazo de fuego tan ancho como el puño. Cintas amarillas finas que se rizan a los lados. |
| Kagerō | 4 | Chorro amarillo vivo con el borde naranja y un resplandor rojizo. |
| Higan | 3 | Rayas amarillo pálido muy largas y finas, con brillo. |
| Hibashira | 2 | Primero un remolino a ras de suelo, medio rojo y medio amarillo. Después una columna recta amarillo pálido, casi blanca en el centro, con fuego rojo y amarillo que se abulta en la base. |
| Enkai | 3 | Un abanico amarillo anaranjado plano que sale de la mano, con rayas más pálidas. |
| Hotarubi | 5 | Bolitas verde pálido con el centro casi blanco y un halo verde. |
| Hidaruma | 2 | Lenguas amarillas con el borde naranja sobre el que arde. |
| Enjōmō | 3 | Un muro amarillo pálido que llena la pantalla. |
| St. Elmo / Shiranui | 2 y 3 | Arcos naranja rojizo con el centro amarillo; las lanzas, rayas amarillas. |
| Jūjika | 7 y 1 | La cruz, dos trazos amarillo pálido con el borde naranja; el estallido, rojo con borde amarillo. |
| Cuerpo en llamas | 3 | Lenguas amarillo crema con el contorno ondulado; la piel se ve dentro. Un volumen AURA (forma 9) en vez de las llamas sueltas de hombros, brazos y espalda. |
| Kyōkaen | 3 | Un muro amarillo anaranjado con venas más pálidas. El vapor, blanco plano. |
| Dai Enkai | 2 | Un aro naranja con anillos amarillos en el suelo; después llamas rojas y amarillas con el centro pálido. |
| Entei | 6 | Un sol amarillo muy pálido, casi blanco, con el borde naranja fino y algunas llamaradas. Al estallar, pantalla en blanco y después una cúpula roja. |
