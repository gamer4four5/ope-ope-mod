# Sonidos propios de la Mera Mera no Mi

Los 23 archivos de `src/main/resources/assets/opeope/sounds/mera/` están sintetizados desde cero con
`tools/sonidos_mera.py` (ruido filtrado y senos; nada grabado ni copiado). Son OGG Vorbis mono a 44,1 kHz, así que
valen como sonidos con posición. Para rehacerlos: `python3 tools/sonidos_mera.py <carpeta_wav> <carpeta_ogg>`
(necesita numpy y ffmpeg con libvorbis).

Los tres bucles (`chorro_bucle`, `fuego_bucle`, `sol_bucle`) son periódicos: se repiten sin costura.

## Eventos y dónde usarlos

| Evento | Archivos | Para qué |
| --- | --- | --- |
| `opeope:mera.rugido` | `rugido1-3` | La llamarada grande que sale: Hiken, Hibashira, Kyōkaen, Enjōmō, Dai Enkai, la ola y el golpe al suelo. |
| `opeope:mera.disparo` | `disparo1-3` | Lo que sale silbando: balas de Higan (tono 1,3-1,6 y volumen bajo), lanzas de Shiranui y St. Elmo (tono 0,8-1,0), Jūjika. |
| `opeope:mera.chorro` | `chorro_bucle` | Bucle. Los chorros del vuelo (tono de 0,8 a 1,4 con la velocidad), el lanzallamas de Kagerō mientras dura, el Striker. |
| `opeope:mera.fuego` | `fuego_bucle` | Bucle. Un incendio grande cerca, el cerco del Dai Enkai, el tornado (tono 1,3). |
| `opeope:mera.crepitar` | `crepitar1-3` | Las brasas que quedan tras un impacto, el fuego del suelo de vez en cuando, la roca que se enfría. |
| `opeope:mera.estallido` | `estallido1-2` | Explosiones medianas: impacto del Hiken, Jūjika, Hidaruma, picado, estallido de St. Elmo. |
| `opeope:mera.estallido_grande` | `estallido_grande1-2` | El estallido del Entei (tono 0,7 si va sobrecargado) y el Hiken que parte la montaña. |
| `opeope:mera.encender` | `encender1-2` | Algo que prende: encender el cuerpo, el miembro que se vuelve fuego, calentar un bloque, las luciérnagas (tono 1,6 y volumen bajo). |
| `opeope:mera.vapor` | `vapor1-2` | Agua que hierve: tocar el agua, la lluvia sobre el cuerpo, el mar bajo el Entei. |
| `opeope:mera.golpe` | `golpe1-3` | El puñetazo de fuego que conecta, el contraataque, el enemigo lanzado que choca. |
| `opeope:mera.sol` | `sol_bucle` | Bucle. El sol de Entei mientras se carga (tono de 0,7 a 1,2 y volumen según el radio). |

Los bucles se tocan en el cliente con una instancia de sonido que sigue a quien lo emite y se repite (`repeat = true`,
sin retardo), cambiando volumen y tono cada tick y apagándose en unos 5 ticks al terminar.

## Entradas de `sounds.json`

```json
  "mera.rugido": {
    "subtitle": "subtitles.opeope.mera.rugido",
    "sounds": ["opeope:mera/rugido1", "opeope:mera/rugido2", "opeope:mera/rugido3"]
  },
  "mera.disparo": {
    "subtitle": "subtitles.opeope.mera.disparo",
    "sounds": ["opeope:mera/disparo1", "opeope:mera/disparo2", "opeope:mera/disparo3"]
  },
  "mera.chorro": {
    "subtitle": "subtitles.opeope.mera.chorro",
    "sounds": ["opeope:mera/chorro_bucle"]
  },
  "mera.fuego": {
    "subtitle": "subtitles.opeope.mera.fuego",
    "sounds": [{"name": "opeope:mera/fuego_bucle", "attenuation_distance": 32}]
  },
  "mera.crepitar": {
    "subtitle": "subtitles.opeope.mera.crepitar",
    "sounds": ["opeope:mera/crepitar1", "opeope:mera/crepitar2", "opeope:mera/crepitar3"]
  },
  "mera.estallido": {
    "subtitle": "subtitles.opeope.mera.estallido",
    "sounds": [
      {"name": "opeope:mera/estallido1", "attenuation_distance": 40},
      {"name": "opeope:mera/estallido2", "attenuation_distance": 40}
    ]
  },
  "mera.estallido_grande": {
    "subtitle": "subtitles.opeope.mera.estallido",
    "sounds": [
      {"name": "opeope:mera/estallido_grande1", "attenuation_distance": 96},
      {"name": "opeope:mera/estallido_grande2", "attenuation_distance": 96}
    ]
  },
  "mera.encender": {
    "subtitle": "subtitles.opeope.mera.encender",
    "sounds": ["opeope:mera/encender1", "opeope:mera/encender2"]
  },
  "mera.vapor": {
    "subtitle": "subtitles.opeope.mera.vapor",
    "sounds": ["opeope:mera/vapor1", "opeope:mera/vapor2"]
  },
  "mera.golpe": {
    "subtitle": "subtitles.opeope.mera.golpe",
    "sounds": ["opeope:mera/golpe1", "opeope:mera/golpe2", "opeope:mera/golpe3"]
  },
  "mera.sol": {
    "subtitle": "subtitles.opeope.mera.sol",
    "sounds": [{"name": "opeope:mera/sol_bucle", "attenuation_distance": 64}]
  }
```

## Subtítulos

```json
  "subtitles.opeope.mera.rugido": "Rugen las llamas",
  "subtitles.opeope.mera.disparo": "Silba el fuego",
  "subtitles.opeope.mera.chorro": "Chorro de fuego",
  "subtitles.opeope.mera.fuego": "Arde un incendio",
  "subtitles.opeope.mera.crepitar": "Crepitan las brasas",
  "subtitles.opeope.mera.estallido": "Estalla el fuego",
  "subtitles.opeope.mera.encender": "Algo prende",
  "subtitles.opeope.mera.vapor": "Sisea el vapor",
  "subtitles.opeope.mera.golpe": "Golpe de fuego",
  "subtitles.opeope.mera.sol": "Zumba el sol de Entei"
```

## Niveles

Todos van con el pico por debajo de 0,86 tras descodificar (sin recorte). Los golpes y rugidos rondan −12 dB RMS, los
bucles −15 dB y el crepitar es disperso (−30 dB): si en el juego se queda corto, súbele el volumen al tocarlo.
