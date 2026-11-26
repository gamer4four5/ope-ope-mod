#version 150

// El fuego 3D de la Mera Mera no Mi (Portgas D. Ace): raymarching de un volumen de fuego dentro de una caja.
// La caja la dibuja MeraVolumes (solo sus caras de detrás); aquí, para cada píxel, se recorre el rayo de la mirada
// dentro de la forma, sumando la luz del fuego y su opacidad (alfa premultiplicado), y se para donde está el mundo.

uniform sampler2D Sampler0;   // la profundidad del mundo (una copia del depth buffer)
uniform mat4 InvViewProj;     // pantalla → mundo (relativo a la cámara)
uniform vec2 ViewSize;
uniform float VolTime;
uniform vec3 VolCenter;       // origen de la forma (relativo a la cámara)
uniform vec3 VolX;            // ejes locales de la forma (unitarios)
uniform vec3 VolY;
uniform vec3 VolZ;
uniform vec3 VolMin;          // la caja local que la envuelve
uniform vec3 VolMax;
uniform vec4 VolPrm;          // los parámetros de la forma
uniform vec4 VolInfo;         // forma, semilla, intensidad, bloques junto a la cámara donde se aclara
uniform float VolSky;         // la luz que hay alrededor (1 a pleno día, casi 0 de noche): tiñe el humo
uniform vec4 VolStyle;        // x: 0 = fuego realista, 1 = anime (colores planos, como en la serie) · y: paleta (0 = la de la forma) · z: ancho del borde (0 = el de la paleta)

out vec4 fragColor;

// =====================================================================
//  MERA MERA NO MI  -  el fuego volumétrico de Ace (versión final)
//
//  Como el fuego de verdad: casi todo es una MASA que brilla, como humo
//  encendido que se enrolla en bolsas, con pliegues oscuros de hollín.
//  Solo donde esa masa se enfría y se deshace (los bordes, la punta, la
//  cola) se rompe en lenguas de llama, y al final queda humo.
//  Color por temperatura (cuerpo negro) con bandas suaves como en el
//  anime, borde más rojo y un halo de luz.
//
//  Espacio local: 1 unidad = 1 bloque, +Y arriba, las formas con
//  dirección miran hacia +Z.
//
//    float mfBound (float shape, vec4 prm);
//    vec4  mfRender(vec3 ro, vec3 rd, float tNear, float tFar,
//                   float shape, vec4 prm, float time, float seed,
//                   float intensity, float nearFade);
//
//  nearFade: a menos de estos bloques de la cámara el fuego se aclara
//  (para el fuego que sale de la mano en primera persona); 0 = nada.
//
//  mfRender devuelve color PREMULTIPLICADO + cobertura (0..1);
//  mezcla con ONE, ONE_MINUS_SRC_ALPHA.  intensity 0..1 (hasta 2 = destello).
//
//  Parámetros de cada forma (prm):
//    0 FIREBALL  x radio, y enfriado de explosión 0..1, z velocidad 0..1,
//                w humo claro y fino 0..1 (los golpes pequeños: una bocanada gris); 2 = el sol de Entei
//    1 FIST      x tamaño, y largo de la estela
//    2 PILLAR    x radio, y alto, z 1 = visto desde el hueco de dentro
//    3 RING      x radio, y alto, z grosor, w 1 = cortina de lenguas (el cerco de Enjōmō)
//    4 WALL      x medio ancho, y alto, z grosor (una cortina de lenguas)
//    5 JET       x largo, y radio final, z grosor al salir (en partes del final; 0 = fino, 0.1)
//    6 CROSS     x brazo, y grosor, z hasta dónde baja el brazo de abajo (el suelo; 0 = 1,3 brazos)
//    7 LANCE     x largo, y radio (la punta en +x/2, centrada)
//    8 FLAME     x radio, y alto, z 1 = sale del suelo (la base, metida en él: varias lenguas), 2 = pegada
//                a una pared (igual, con la base que se deshace abajo), w el ritmo de su vaivén 0..1
// =====================================================================

// ---------------------------------------------------------------------
//  Ruido (hash sin senos de Dave Hoskins, ruido de gradiente)
// ---------------------------------------------------------------------
vec3 mf_hash33(vec3 p3)
{
    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yxx) * p3.zyx);
}

// ruido de gradiente 3D, curva quíntica, salida más o menos -1..1
float mf_gnoise(vec3 x)
{
    vec3 i = floor(x);
    vec3 f = x - i;
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);

    vec3 g000 = mf_hash33(i + vec3(0.0, 0.0, 0.0)) * 2.0 - 1.0;
    vec3 g100 = mf_hash33(i + vec3(1.0, 0.0, 0.0)) * 2.0 - 1.0;
    vec3 g010 = mf_hash33(i + vec3(0.0, 1.0, 0.0)) * 2.0 - 1.0;
    vec3 g110 = mf_hash33(i + vec3(1.0, 1.0, 0.0)) * 2.0 - 1.0;
    vec3 g001 = mf_hash33(i + vec3(0.0, 0.0, 1.0)) * 2.0 - 1.0;
    vec3 g101 = mf_hash33(i + vec3(1.0, 0.0, 1.0)) * 2.0 - 1.0;
    vec3 g011 = mf_hash33(i + vec3(0.0, 1.0, 1.0)) * 2.0 - 1.0;
    vec3 g111 = mf_hash33(i + vec3(1.0, 1.0, 1.0)) * 2.0 - 1.0;

    float v000 = dot(g000, f - vec3(0.0, 0.0, 0.0));
    float v100 = dot(g100, f - vec3(1.0, 0.0, 0.0));
    float v010 = dot(g010, f - vec3(0.0, 1.0, 0.0));
    float v110 = dot(g110, f - vec3(1.0, 1.0, 0.0));
    float v001 = dot(g001, f - vec3(0.0, 0.0, 1.0));
    float v101 = dot(g101, f - vec3(1.0, 0.0, 1.0));
    float v011 = dot(g011, f - vec3(0.0, 1.0, 1.0));
    float v111 = dot(g111, f - vec3(1.0, 1.0, 1.0));

    float a = mix(mix(v000, v100, u.x), mix(v010, v110, u.x), u.y);
    float b = mix(mix(v001, v101, u.x), mix(v011, v111, u.x), u.y);
    return mix(a, b, u.z) * 1.6;
}

// 4 octavas giradas (sin artefactos de ejes) que además se deforman con
// el tiempo; lo = forma grande (octavas 1-2), hi = detalle (3-4).
void mf_fbm2(vec3 p, float time, out float lo, out float hi)
{
    const mat3 m = mat3( 0.00,  0.80,  0.60,
                        -0.80,  0.36, -0.48,
                        -0.60, -0.48,  0.64);
    float o0 = mf_gnoise(p);
    p = m * p * 2.03 + vec3(0.0, 0.0, time * 0.37);
    float o1 = mf_gnoise(p);
    p = m * p * 2.01 + vec3(0.0, 0.0, time * 0.74);
    float o2 = mf_gnoise(p);
    p = m * p * 2.02 + vec3(0.0, 0.0, time * 1.11);
    float o3 = mf_gnoise(p);
    lo = (o0 + 0.5 * o1) / 1.5;
    hi = (o2 + 0.5 * o3) / 1.5;
}

// Bolsas de la masa: suma de octavas en valor absoluto (coliflor a
// varias escalas). tb 0..~1 (media ~0.35): alto = bolsa, ~0 = pliegue.
void mf_billow(vec3 p, float time, out float tb, out float det)
{
    const mat3 m = mat3( 0.00,  0.80,  0.60,
                        -0.80,  0.36, -0.48,
                        -0.60, -0.48,  0.64);
    float o0 = mf_gnoise(p);
    p = m * p * 2.03 + vec3(0.0, 0.0, time * 0.37);
    float o1 = mf_gnoise(p);
    p = m * p * 2.01 + vec3(0.0, 0.0, time * 0.74);
    float o2 = mf_gnoise(p);
    tb = (abs(o0) + 0.5 * abs(o1) + 0.25 * abs(o2)) / 1.75;
    det = o2;
}

// ---------------------------------------------------------------------
//  Formas (SDF)
// ---------------------------------------------------------------------
float mf_sdRoundBox(vec3 p, vec3 b, float r)
{
    vec3 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r;
}

float mf_sdCapsule(vec3 p, vec3 a, vec3 b, float r)
{
    vec3 pa = p - a;
    vec3 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h) - r;
}

// cono redondeado sobre +Y: esfera r1 en el origen hasta esfera r2 a altura h
float mf_sdRoundCone(vec3 p, float r1, float r2, float h)
{
    float b = (r1 - r2) / h;
    float a = sqrt(max(1.0 - b * b, 0.0));
    vec2 q = vec2(length(p.xz), p.y);
    float k = dot(q, vec2(-b, a));
    if (k < 0.0) return length(q) - r1;
    if (k > a * h) return length(q - vec2(0.0, h)) - r2;
    return dot(q, vec2(a, b)) - r1;
}

float mf_smin(float a, float b, float k)
{
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

vec2 mf_rot(vec2 v, float a)
{
    float c = cos(a);
    float s = sin(a);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

// El puño en espacio unidad: nudillos hacia +Z, dorso +Y, pulgar en -X.
float mf_sdFist(vec3 q)
{
    float d = mf_sdRoundBox(q - vec3(0.0, 0.0, -0.10), vec3(0.40, 0.23, 0.30), 0.15);
    float fing = 1e5;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float x  = -0.285 + 0.19 * fi;
        float sc = (i == 3) ? 0.86 : 1.0;
        float zo = (i == 3) ? -0.04 : ((i == 1) ? 0.02 : 0.0);
        float f = mf_sdRoundBox(q - vec3(x, -0.05, 0.23 + zo),
                                vec3(0.080, 0.21, 0.13) * sc, 0.072 * sc);
        float k = length(q - vec3(x, 0.15, 0.25 + zo)) - 0.105 * sc;
        fing = min(fing, min(f, k));
    }
    d = mf_smin(d, fing, 0.06);
    float th = mf_sdCapsule(q, vec3(-0.44, -0.06, -0.14), vec3(-0.32, -0.20, 0.25), 0.115);
    th = min(th, mf_sdCapsule(q, vec3(-0.32, -0.20, 0.25), vec3(-0.02, -0.235, 0.37), 0.085));
    d = mf_smin(d, th, 0.05);
    float w = mf_sdCapsule(q, vec3(0.0, -0.02, -0.30), vec3(0.0, -0.02, -0.75), 0.25);
    d = mf_smin(d, w, 0.12);
    return d;
}

// El puño dibujado (estilo anime): un puño de dibujos, con cuatro dedos gordos doblados y surcos claros entre ellos
// (por ahí van las líneas amarillas), el pulgar cruzado por debajo y la muñeca ancha.
float mf_sdFistToon(vec3 q)
{
    // (0:32) casi una bola, con los cuatro dedos como bultos gordos por delante y por abajo
    float d = mf_sdRoundBox(q - vec3(0.0, 0.03, -0.06), vec3(0.30, 0.28, 0.26), 0.26);
    float fing = 1e5;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float x  = -0.33 + 0.22 * fi;
        float sc = (i == 3) ? 0.88 : ((i == 0) ? 0.95 : 1.0);
        vec3 k0 = vec3(x, 0.30, 0.20);
        vec3 k1 = vec3(x, 0.10, 0.50);
        vec3 k2 = vec3(x, -0.24, 0.40);
        fing = min(fing, min(mf_sdCapsule(q, k0, k1, 0.130 * sc), mf_sdCapsule(q, k1, k2, 0.125 * sc)));
    }
    d = mf_smin(d, length(q - vec3(0.0, 0.02, 0.02)) - 0.54, 0.10);
    d = mf_smin(d, fing, 0.05);
    float th = mf_sdCapsule(q, vec3(-0.50, -0.02, -0.10), vec3(-0.44, -0.28, 0.26), 0.135);
    th = min(th, mf_sdCapsule(q, vec3(-0.44, -0.28, 0.26), vec3(-0.08, -0.38, 0.38), 0.115));
    d = mf_smin(d, th, 0.04);
    float w = mf_sdCapsule(q, vec3(0.0, 0.0, -0.30), vec3(0.0, 0.0, -0.55), 0.30);
    d = mf_smin(d, w, 0.12);
    return d;
}

// ---------------------------------------------------------------------
//  Campos de cada forma.
//   d   : distancia a la envoltura (bloques), antes de la turbulencia
//   L   : grosor típico de la llama: paso, opacidad y escala del ruido
//   u   : 0 = donde nace el fuego (caliente) ... 1 = donde se enfría y
//         se deshace (>1 = humo)
//   nb  : coordenada del ruido del CUERPO (bolsas grandes, casi redondas)
//   nq  : coordenada del ruido de las LENGUAS (estiradas con el flujo)
//   amp : amplitud de la turbulencia (bloques)
// ---------------------------------------------------------------------
// Para saltar el espacio vacío cuando d no es una distancia exacta (la columna: sus bandas en hélice cambian varias
// veces más rápido que la distancia; saltar con d tal cual se pasaba del borde y salían anillos como vetas de madera):
//   mf_lip  cuánto más rápido que una distancia puede cambiar d (1 = distancia de verdad)
//   mf_dFar otra distancia segura, buena de lejos (-1e9 = ninguna)
// 1 mientras se dibuja en estilo anime: algunas formas cambian un poco (la columna, más maciza)
float mf_toon = 0.0;
float mf_lip;
float mf_dFar;
// la columna de Hibashira vista desde el hueco (primera persona): el ruido de sus rendijas (mf_str) y cuánto se mete
// cada punto en su banda de fuego, en grosores de pared (mf_core): el centro de cada lengua, más caliente
// (estilo anime) las cintas finas que acompañan a algunas formas: distancia a la más cercana (1e5 = no hay)
float mf_rib;
// (estilo anime) la parte que se dibuja como una capa encima del resto (el puño, sobre su cuerpo de fuego): su distancia (1e5 = no hay)
float mf_part;
float mf_str;
float mf_core;

void mf_shape(vec3 p, float shape, vec4 prm, float time,
              out float d, out float L, out float u, out vec3 nb, out vec3 nq, out float amp)
{
    mf_lip = 1.0;
    mf_dFar = -1e9;
    mf_str = 0.0;
    mf_core = 0.0;
    mf_rib = 1.0e5;
    mf_part = 1.0e5;
    if (shape < 0.5) {
        // ---------------- 0: BOLA / SOL / EXPLOSIÓN ----------------
        float R  = max(prm.x, 0.05);
        float cl = clamp(prm.y, 0.0, 1.0);          // explosión que se enfría
        float mv = clamp(prm.z, 0.0, 1.0);          // va lanzada: la cola atrás
        vec3 q = p;
        q.y -= cl * cl * R * 0.55;                   // sube como un hongo
        q.y *= 1.0 + 0.35 * cl;                      // y se aplasta
        float back = smoothstep(0.0, -R, q.z);
        q.z /= 1.0 + 0.6 * mv * back;                // estirada hacia atrás
        float r = length(q);
        vec3 dir = q / (r + 1e-4);
        d = r - R;
        L = R * 0.32;
        u = clamp((r - R * 0.70) / (R * 0.55), 0.0, 1.3);
        vec3 pr = q;
        pr.xz = mf_rot(pr.xz, time * 0.12 * (1.0 - mv));
        float up = smoothstep(-0.2, 0.6, dir.y) * (1.0 - mv);
        vec3 st = vec3(1.0, mix(0.85, 0.45, up), mix(1.0, 0.45, mv * back));
        vec3 flow = vec3(0.0, 0.9 * (1.0 - mv) + 0.25 * cl, -2.4 * mv);
        // el tamaño de las bolsas no crece sin fin con la bola: una explosión enorme también tiene bolsas pequeñas
        float Rn = min(R, 2.6);
        nq = (pr * st) / (Rn * 0.38) - flow * (time * 0.6);
        nb = pr / (Rn * (0.62 - 0.22 * cl)) - flow * (time * 0.22);   // al enfriarse, bolsas más pequeñas (coliflor)
        // al enfriarse, el borde se deshilacha más (el humo no tiene borde liso)
        amp = R * (0.11 + 0.30 * cl * cl + 0.50 * smoothstep(R * 0.88, R * 1.5, r)) * (1.0 + 1.3 * max(dir.y, 0.0) * (1.0 - mv) + 1.2 * mv * back);
        if (mf_toon > 0.5 && prm.w > 1.5) {
            for (int i = 0; i < 5; i++) {
                float fi = float(i);
                vec3 n = normalize(vec3(sin(fi * 2.4 + 0.6), 0.9 * cos(fi * 1.7 + 0.2), cos(fi * 2.4 + 0.6)));
                vec3 a = normalize(cross(n, vec3(0.0, 1.0, 0.0)));
                vec3 b = cross(n, a);
                vec2 q2 = vec2(dot(p, a), dot(p, b));
                float tt = fract((atan(q2.y, q2.x) + time * (0.22 + 0.08 * fi) + fi * 2.1) / 6.2832) / 0.20;
                if (tt < 1.0) {
                    float sn = sin(3.1416 * tt);
                    mf_rib = min(mf_rib, length(vec2(length(q2) - R * (1.03 + 0.06 * sn), dot(p, n) - R * 0.18 * (fi - 2.0))) - R * 0.024 * sn);
                }
            }
        }
    }
    else if (shape < 1.5) {
        // ---------------- 1: PUÑO (Hiken) ----------------
        float S = max(prm.x, 0.2);
        float tail = max(prm.y, 0.0);
        float df = ((mf_toon > 0.5) ? mf_sdFistToon(p / S) : mf_sdFist(p / S)) * S;
        mf_part = df;
        float z0 = -0.45 * S;
        float v = clamp((z0 - p.z) / max(tail, 0.01), 0.0, 1.0);
        // (en anime, detrás va un brazo de fuego casi tan ancho como el puño, no una cola fina)
        float rr = mix(0.34 * S, 0.06 * S, pow(v, 0.7));
        // (en anime, detrás va un cuerpo de fuego que se ensancha, más ancho que el puño: un cometa, como en el 0:34)
        if (mf_toon > 0.5) { rr = S * (0.40 + 0.80 * smoothstep(0.0, 1.5 * S, z0 - p.z)) * (1.0 - 0.80 * smoothstep(0.65, 1.0, v)); mf_lip = 1.9; }
        float dt = max(length(p.xy - vec2(0.0, -0.02 * S)) - rr, p.z - z0 - 0.1 * S);
        dt = max(dt, z0 - tail - p.z);
        d = mf_smin(df, dt, ((mf_toon > 0.5) ? 0.09 : 0.25) * S);
        L = 0.20 * S;
        u = clamp((-0.30 * S - p.z) / max(tail + 0.2 * S, 0.01), 0.0, 1.0) * 0.95;
        nq = vec3(p.x / (L * 1.3), p.y / (L * 1.3) - time * 0.6, p.z / (L * 2.6) + time * 3.2);
        nb = vec3(p.x / (L * 2.1), p.y / (L * 2.1) - time * 0.3, p.z / (L * 2.4) + time * 1.6);
        float body = smoothstep(-0.1 * S, -0.6 * S, p.z);
        amp = L * mix(0.16, 0.50 + 0.60 * v, body);
        if (mf_toon > 0.5) {
            // el puño, limpio (se leen los dedos); el brazo, con lenguas grandes
            amp = L * mix(0.04, 0.50 + 0.85 * v, body);
            // cintas amarillas finas que salen de los lados del puño y se quedan atrás, ondulando
            float zr = (-0.15 * S - p.z) / max(tail + 0.3 * S, 0.01);
            if (zr > 0.0 && zr < 1.0) {
                for (int i = 0; i < 4; i++) {
                    float fi = float(i);
                    float a = 1.571 * fi + 0.7 + 0.9 * sin(time * 1.3 + fi * 2.1) + zr * (1.6 + 0.5 * fi);
                    float R = S * (0.72 + 0.80 * sqrt(zr) + 0.10 * sin(zr * 9.0 + fi * 1.9 + time * 5.0));
                    float th = S * 0.040 * smoothstep(0.0, 0.10, zr) * (1.0 - zr) * (0.6 + 0.4 * sin(fi * 3.7 + 1.0));
                    mf_rib = min(mf_rib, length(p.xy - R * vec2(cos(a), sin(a))) - th);
                }
            }
        }
    }
    else if (shape < 2.5) {
        // ---------------- 2: COLUMNA (Hibashira): un remolino de fuego hueco ----------------
        // una pared gruesa de fuego que gira y sube alrededor de Ace; dentro queda el hueco donde está él
        float R = max(prm.x, 0.1);
        float H = max(prm.y, 0.5);
        float v = clamp(p.y / H, 0.0, 1.0);
        vec2 c = p.xz + vec2(sin(p.y * 0.35 + time * 0.9), cos(p.y * 0.28 + time * 0.7)) * R * 0.08 * v;
        float r = length(c);
        float Ro = R * (1.0 + 0.40 * pow(1.0 - v, 8.0) - 0.22 * v * v);   // se abre pegado al suelo
        float Ri = R * (0.44 - 0.10 * v);                                   // el hueco de dentro
        float th = 0.5 * (Ro - Ri);
        float d0 = abs(r - 0.5 * (Ro + Ri)) - th;
        // bandas de fuego en hélice que suben girando, con huecos entre ellas (se ve a través)
        float ang = atan(c.y, c.x);
        float band = sin(5.0 * ang + p.y / R * 1.6 - time * 4.0);
        float band2 = sin(9.0 * ang - p.y / R * 2.6 + time * 2.6);
        // (pegado al eje no: ahí dentro está Ace, y allí las bandas cambiarían demasiado deprisa)
        float g = smoothstep(0.22 * R, 0.55 * R, r);
        // desde el hueco (primera persona) las bandas bajan hasta el suelo: entre ellas se ve el suelo con llamas
        // sueltas, no un anillo continuo que desde dentro parecía un charco de lava
        float zS = clamp(prm.z, 0.0, 1.0);
        float sy = smoothstep(0.4 - 0.6 * zS, 1.4 - 0.9 * zS, p.y) * g;
        // visto desde el hueco (prm.z = 1, la primera persona de Ace) los huecos entre las bandas son más anchos: se
        // ven las cintas de fuego subiendo alrededor y el mundo entre ellas, no una pantalla llena de fuego
        d = d0 + th * (1.35 * band + 0.55 * band2 + 0.45 + 0.7 * zS) * sy * mix(1.0, 0.30, mf_toon * (1.0 - zS));
        // y desde ahí la pared se rompe en lenguas que suben: un ruido sobre la superficie del cilindro (el mismo en todo
        // el grosor, así las rendijas la atraviesan y se ve el mundo), estirado hacia arriba, que sube y gira con las
        // bandas (si no, de cerca la pared parecía lava lisa)
        float sy2 = smoothstep(-0.3, 0.5, p.y) * g;
        float nS = 0.0;
        if (zS > 0.001) {
            vec2 sS = mf_rot(c, p.y / R * 0.32 - time * 0.8);
            float n1 = mf_gnoise(vec3(sS / max(length(sS), 1e-3) * (R * 1.6), p.y * 0.45 - time * 1.6));
            mf_str = n1;
            nS = zS * 1.2;
            d += th * nS * (-0.1 - n1) * sy2;
            mf_core = (d0 - d) / th;
        }
        float yCut = max(p.y - H, -p.y - 0.3 * R);
        d = max(d, yCut);
        // las bandas acercan la pared como mucho 1.45 th, y cambian hasta (1.35*5 + 0.55*9)·th/r por bloque
        // (y ese ruido, hasta 1.1·nS·th más, y cambia hasta 2.5·1.6·R/r·nS·th por bloque)
        mf_lip = 1.0 + th * (7.0 * sy / max(r, 0.22 * R) + 9.0 * g * (1.0 - g) / R + nS * sy2 * 4.0 * R / max(r, 0.3 * R));
        mf_dFar = max(d0 - (1.45 * sy + 1.1 * nS * sy2) * th, yCut);
        L = clamp(th * 0.30, 0.18, 0.40);
        u = p.y / H;
        // el remolino: el ruido gira alrededor del eje, se tuerce en hélice y se estira hacia arriba (vetas)
        vec2 sw = mf_rot(p.xz, p.y / R * 0.55 - time * 2.2);
        // desde dentro (primera persona) la pared está a un paso: vetas más finas y más largas hacia arriba, como
        // lenguas de fuego que suben (si no, de cerca parecía lava lisa)
        float fin = mix(1.0, 0.6, clamp(prm.z, 0.0, 1.0));
        nq = vec3(sw.x / (L * 0.8 * fin), p.y / (L * 3.6) - time * 3.0, sw.y / (L * 0.8 * fin));
        nb = vec3(sw.x / (L * 1.2 * fin), p.y / (L * 4.2) - time * 1.6, sw.y / (L * 1.2 * fin));
        amp = L * (0.75 + 0.90 * v);
        if (mf_toon > 0.5 && zS < 0.5) {
            // (anime) la columna de la serie (1:16): un haz recto y liso que se abre en el pie y arriba, sin hueco
            float foot = pow(1.0 - v, 7.0);
            float top = smoothstep(0.78, 1.0, v);
            float Rc = R * (0.50 + 0.80 * foot + 0.55 * top * top);
            d = max(length(c) - Rc, yCut);
            mf_lip = 1.0 + 7.5 * R / H;
            mf_dFar = -1e9;
            mf_str = 0.0; mf_core = 0.0;
            L = clamp(R * 0.30, 0.18, 0.90);
            nq = vec3(sw.x / (L * 0.9), p.y / (L * 3.0) - time * 3.0, sw.y / (L * 0.9));
            nb = vec3(sw.x / (L * 1.3), p.y / (L * 2.2) - time * 1.6, sw.y / (L * 1.3));
            amp = L * (0.10 + 0.85 * foot + 0.80 * top);
        }
    }
    else if (shape < 3.5) {
        // ---------------- 3: CERCO (Enjomo) ----------------
        float R  = max(prm.x, 0.5);
        float H0 = max(prm.y, 0.3);
        float th = (prm.z > 0.01) ? prm.z : 0.5;
        // w = 1 (el cerco de Enjōmō): como el muro, una cortina de lenguas que suben, unas más altas que otras y
        // cambiando, con la base seguida y muy caliente (antes, bolas sueltas como palomitas con el borde de arriba
        // recto). Los demás anillos (el remolino que se cierra, las ondas) siguen como estaban.
        float cur = clamp(prm.w, 0.0, 1.0);
        float hv = clamp(mf_gnoise(vec3(p.x * 0.30, time * 0.7, p.z * 0.30)) * 1.5, -1.0, 1.0);
        float H = H0 * mix(1.0, 0.86 + 0.18 * hv, cur);
        float v = clamp(p.y / H, 0.0, 1.0);
        float rd = length(p.xz) - R;
        d = abs(rd) - th * (1.0 - 0.45 * v);
        d = max(d, p.y - H);
        d = max(d, -p.y - 0.2);
        // (la altura cambia de un sitio a otro: la distancia hasta arriba ya no es exacta)
        mf_lip = mix(1.0, 1.4, cur);
        L = mix(max(th, 0.25 * H0) * 0.9, max(th * 1.1, 0.5), cur);
        if (mf_toon > 0.5) L = min(L, max(H0 * 1.3, 0.3));
        u = p.y / H;
        // en la cortina, el ruido estirado hacia arriba: vetas y lenguas que suben, no bolas redondas
        nq = vec3(p.x / (L * mix(0.8, 0.7, cur)), p.y / (L * mix(2.0, 3.0, cur)) - time * mix(2.6, 3.2, cur), p.z / (L * mix(0.8, 0.7, cur)));
        nb = vec3(p.x / (L * mix(1.5, 1.1, cur)), p.y / (L * mix(1.5, 3.6, cur)) - time * mix(1.2, 1.9, cur), p.z / (L * mix(1.5, 1.1, cur)));
        // y abajo casi sin huecos: la masa se abre en lenguas sobre todo en la mitad de arriba
        amp = mix(L * mix(0.55, 0.45, cur), H * mix(0.45, 0.34, cur), mix(v, v * v, cur));
        if (mf_toon > 0.5 && prm.w > 1.5) {
            // (anime) el remolino de Hibashira (1:12): una media luna plana que gira alrededor de Ace, gruesa en el
            // medio y en punta en los extremos, con la cola más abierta
            // (w = 3: un arco corto que barre deprisa, los tajos de fuego de Shiranui, 2:00)
            float arc = step(2.5, prm.w);
            float span = mix(0.86, 0.40, arc);
            float spd = mix(4.5, 7.0, arc);
            float lag = fract((time * spd - atan(p.z, p.x)) / 6.2832) / span;      // 0 la cabeza · 1 la cola
            float prof = (lag < 1.0) ? pow(max(sin(3.1416 * lag), 0.0), 0.55) * (1.0 - 0.25 * lag) : 0.0;
            float Rc = R * (0.85 + 0.40 * min(lag, 1.0));
            // hacia la cabeza (el brazo de Ace) el fuego sube en llamas
            float rise = (1.0 - smoothstep(0.02, 0.50, lag)) * prof * (1.0 - arc);
            float top = H0 * (0.35 + 0.65 * prof) + H0 * 3.2 * rise;
            vec2 e = vec2(abs(length(p.xz) - Rc) - th * prof, abs(p.y - 0.5 * top) - 0.5 * top);
            d = min(max(e.x, e.y), 0.0) + length(max(e, 0.0));
            if (lag >= 1.0) d += R * min(lag - 1.0, 1.0 / span - lag) * 3.0 * span / 0.86;
            mf_lip = 2.2;
            L = min(th * 0.5, 0.40);
            u = min(lag, 1.0);
            vec2 sr = mf_rot(p.xz, -time * spd);
            nq = vec3(sr.x / (L * 1.6), p.y / L, sr.y / (L * 1.6));
            nb = vec3(sr.x / (L * 1.8), p.y / (L * 1.2), sr.y / (L * 1.8));
            float hi = clamp(p.y / H0 - 0.8, 0.0, 2.5) * rise;
            u = max(u, 0.45 + 0.4 * hi);
            nq.y -= time * 2.4;
            amp = L * (0.25 + 0.45 * min(lag, 1.0) + 1.3 * hi);
            // (el arco: flecos de llama por el canto de fuera)
            if (arc > 0.5) amp = L * (0.20 + 1.1 * smoothstep(-0.2, 1.0, (length(p.xz) - Rc) / max(th, 0.01)));
        }
    }
    else if (shape < 4.5) {
        // ---------------- 4: MURO (Kyokaen): una cortina de fuego ----------------
        // abajo una franja seguida y muy caliente (de ahí sale el fuego); encima se abre en lenguas que suben, unas
        // más altas que otras y cambiando, y en los extremos más baja (antes, una caja de bolas como palomitas con el
        // borde de arriba recto)
        float W  = max(prm.x, 0.5);
        float H0 = max(prm.y, 0.5);
        float T  = max(prm.z, 0.1);
        float hv = clamp(mf_gnoise(vec3(p.x * 0.30, time * 0.7, 2.7)) * 1.5, -1.0, 1.0);
        float ends = smoothstep(W * 0.30, W, abs(p.x));
        float H = H0 * (0.86 + 0.18 * hv) * (1.0 - 0.45 * ends);
        float v = clamp(p.y / H, 0.0, 1.0);
        vec3 c = vec3(p.x, p.y - H * 0.5, p.z);
        d = mf_sdRoundBox(c, vec3(W, H * 0.5, T * (1.0 - 0.35 * v)), min(T * 0.9, H * 0.3));
        // (la altura cambia a lo largo: la distancia hasta arriba ya no es exacta)
        mf_lip = 1.5;
        L = max(T * 1.1, 0.5);
        u = p.y / H;
        // el ruido estirado hacia arriba: vetas y lenguas que suben, no bolas redondas
        nq = vec3(p.x / (L * 0.7), p.y / (L * 3.0) - time * 3.2, p.z / (L * 0.7));
        nb = vec3(p.x / (L * 1.1), p.y / (L * 3.6) - time * 1.9, p.z / (L * 1.1));
        // y abajo casi sin huecos: la masa se abre en lenguas sobre todo en la mitad de arriba
        amp = mix(L * 0.45, H * 0.32, v * v);
    }
    else if (shape < 5.5) {
        // ---------------- 5: CHORRO (Kagero) ----------------
        float Lj = max(prm.x, 1.0);
        float Re = max(prm.y, 0.3);
        vec3 q = p;
        float v = clamp(q.z / Lj, 0.0, 1.0);
        q.y -= v * v * Lj * 0.06;                       // el calor lo levanta
        // fino al salir de la mano (z = grosor al salir, en partes del final), se abre y al final se hincha en una nube
        float s0 = (prm.z > 0.01) ? prm.z : 0.10;
        float rr = Re * (s0 + (0.80 - s0) * v + 0.20 * smoothstep(0.6, 1.0, v));
        d = length(q.xy) - rr;
        d = max(d, -q.z - 0.15 * Re);
        d = max(d, q.z - Lj);
        L = max(rr, 0.22) * 0.9;
        u = clamp(q.z / Lj, 0.0, 1.3);
        float ns = max(rr * 0.75, 0.16);
        // vetas largas a lo largo del chorro, que corren con él (como un lanzallamas; con bolsas redondas parecía
        // una ristra de globos)
        nq = vec3(q.x / ns, q.y / ns - time * 1.2, q.z / (ns * 3.4) - time * 5.0);
        nb = vec3(q.x / (ns * 1.5), q.y / (ns * 1.5) - time * 0.5, q.z / (ns * 4.0) - time * 3.6);
        amp = L * (0.40 + 0.65 * v);
    }
    else if (shape < 6.5) {
        // ---------------- 6: CRUZ (Jujika) ----------------
        float A  = max(prm.x, 0.3);
        float th = (prm.y > 0.01) ? prm.y : 0.3;
        if (mf_toon > 0.5) th = max(th, 0.10 * A);        // (anime) dos trazos gruesos, un quinto del brazo
        float r  = th * 0.8;
        // el brazo de abajo baja 1,3 A, o hasta el suelo si z lo dice (antes se metía en él)
        float lo = (prm.z > 0.01) ? min(1.3 * A, prm.z) : 1.3 * A;
        float b1 = mf_sdRoundBox(p - vec3(0.0, 0.5 * (A - lo), 0.0), vec3(th, 0.5 * (A + lo), th), r);
        float b2 = mf_sdRoundBox(p - vec3(0.0, 0.25 * A, 0.0), vec3(A * 0.85, th, th), r);
        d = mf_smin(b1, b2, th * 0.6);
        L = th * 1.4;
        u = clamp(0.25 + 0.30 * (p.y / A) + 0.35 * max(-p.z, 0.0) / L, 0.0, 1.2);
        nq = vec3(p.x / (L * 1.4), p.y / (L * 2.6) - time * 2.2, p.z / (L * 1.6) + time * 1.2);
        nb = vec3(p.x / (L * 2.0), p.y / (L * 2.0) - time * 0.9, p.z / (L * 2.0) + time * 0.8);
        float topA = smoothstep(0.0, th * 1.2, p.y - 0.25 * A) * smoothstep(th, th * 2.0, abs(p.x));
        float topV = smoothstep(0.0, th * 1.2, p.y - A);
        amp = L * (0.30 + 1.1 * max(topA, topV));
    }
    else if (shape < 7.5) {
        // ---------------- 7: LANZA (Shiranui / Higan), centrada ----------------
        float Ll = max(prm.x, 0.5);
        float R  = max(prm.y, 0.05);
        vec3 q = p - vec3(0.0, 0.0, -0.225 * Ll);       // la punta queda en +0.775 Ll
        float zt = Ll * 0.68;
        float v  = clamp((q.z - zt) / (Ll - zt), 0.0, 1.0);
        float vt = clamp(-q.z / (Ll * 0.55), 0.0, 1.0);
        float rr = R * (1.0 - v) * (1.0 + 0.6 * vt);
        d = (length(q.xy) - rr) * 0.85;
        d = max(d, q.z - Ll);
        d = max(d, -q.z - Ll * 0.55);
        L = R * 1.2;
        u = clamp((-q.z + Ll * 0.3) / (Ll * 0.9), 0.0, 1.2);
        nq = vec3(q.x / L, q.y / L - time * 0.7, q.z / (L * 3.0) + time * 5.0);
        nb = vec3(q.x / (L * 1.6), q.y / (L * 1.6) - time * 0.4, q.z / (L * 3.0) + time * 2.5);
        amp = L * (0.25 + 0.55 * vt);
        if (mf_toon > 0.5) {
            // (anime) un trazo largo y limpio que se abre poco a poco desde una punta afilada, con dos rayas sueltas
            // al lado. z = 0: la lanza, afilada por delante (2:11) · z = 1: la bala de Higan, afilada por detrás (0:57)
            float sg = (prm.z > 0.5) ? -1.0 : 1.0;
            float hl = 0.775 * Ll;
            float s  = clamp(0.5 - 0.5 * sg * p.z / hl, 0.0, 1.0);          // 0 la punta afilada · 1 el otro extremo
            float pr = pow(s, 0.85) * (1.0 - smoothstep(0.82, 1.0, s) * smoothstep(0.82, 1.0, s));
            // (las estelas del Hiken, paleta 14: el trazo va ondulado, como un chorro que se retuerce)
            float wav = (VolStyle.y > 13.5 && VolStyle.y < 14.5) ? 1.0 : 0.0;
            vec2 bend = wav * R * 1.3 * s * vec2(sin(p.z * 0.75 / R + time * 2.0), cos(p.z * 0.55 / R - time * 1.4 + 1.0));
            d = length(p.xy - bend) - R * 1.25 * pr;
            // las rayas sueltas: dos trazos finos junto a la punta, que van resbalando
            for (int i = 0; i < 2; i++) {
                float fi = float(i);
                float ph = fract(time * (0.55 + 0.25 * fi) + fi * 0.37);
                float zc = sg * hl * (0.75 - 0.9 * ph);
                float hw = hl * (0.26 + 0.08 * fi);
                float e  = clamp((p.z - zc) / hw, -1.0, 1.0);
                vec2 o   = R * vec2((fi < 0.5) ? 1.9 : -1.5, (fi < 0.5) ? 1.3 : -2.0) * (0.55 + 0.45 * s);
                float ds = length(vec3(p.xy - o, max(abs(p.z - zc) - hw, 0.0))) - R * 0.17 * (1.0 - e * e) * sin(3.1416 * ph);
                d = min(d, ds);
            }
            d = max(d * mix(0.85, 0.55, wav), abs(p.z) - hl);
            L = R * 1.2;
            u = 1.0;
            nq = vec3(p.x / L, p.y / L - time * 0.5, p.z / (L * 7.0) + sg * time * 4.0);
            nb = nq;
            amp = L * (0.15 + 1.5 * s * s);
        }
    }
    else if (shape > 8.5) {
        // ---------------- 9: AURA: el cuerpo en llamas ----------------
        // el fuego que envuelve a Ace: una funda alrededor del cuerpo (origen en los pies, +Y arriba) de la que salen
        // lenguas grandes que suben y se rizan, más largas en los hombros y la cabeza. En estilo anime solo se dibuja
        // la mitad de detrás (ver mfRenderToon): el cuerpo se ve entero y el fuego asoma por su contorno, como en la serie
        float R = max(prm.x, 0.2);
        float H = max(prm.y, 0.5);
        float kf = clamp(prm.z, 0.0, 1.0);
        float tt = time + prm.w * 17.0;
        vec3 q = p;
        q.z *= 1.30;                                   // el cuerpo es más ancho que hondo
        float v = clamp(q.y / H, 0.0, 1.4);
        float rb = R * (0.80 + 0.30 * smoothstep(0.50, 0.80, v) - 0.25 * smoothstep(0.85, 1.05, v));
        float d0 = mf_sdCapsule(q, vec3(0.0, R * 0.8, 0.0), vec3(0.0, max(H - R * 0.6, R), 0.0), rb);
        d = d0 - R * (0.08 + 0.20 * kf);
        L = R * 0.60;
        u = clamp(d0 / (R * (1.2 + 1.2 * kf)) + 0.25 * v, 0.0, 1.2);
        // las lenguas suben, se abren hacia fuera y se rizan como olas (más arriba, más): el ruido, retorcido por dos
        // remolinos suaves, deja ganchos en las puntas
        float sway = sin(tt * 1.7 + q.y * 2.1) * 0.30 + sin(tt * 2.9 - q.y * 3.7 + q.x * 2.0) * 0.14;
        vec3 qs = q + vec3(sway * R * (0.3 + v), 0.0, 0.5 * sway * R * v);
        float f1 = 4.2 / R; float a1 = 0.26 * R * (0.4 + 0.6 * smoothstep(0.0, 0.6, d0 / R + 0.5));
        qs += a1 * vec3(sin(q.y * f1 + tt * 1.3), 0.8 * sin(q.x * f1 * 1.2 + q.z * f1 * 0.7 - tt * 0.9), cos(q.y * f1 * 0.9 + tt * 1.1));
        qs += 0.45 * a1 * vec3(sin(qs.y * f1 * 2.1 - tt * 1.7), sin(qs.x * f1 * 2.3 + tt * 1.2), cos(qs.y * f1 * 1.9 + qs.x * f1));
        nq = vec3(qs.x / (L * 1.25), qs.y / (L * 2.3) - time * 1.9, qs.z / (L * 1.25));
        nb = vec3(q.x / (L * 1.2), q.y / (L * 2.0) - time * 1.4, q.z / (L * 1.2));
        amp = L * (1.25 + 2.6 * v) * (0.30 + 0.70 * kf);
        mf_lip = 2.6;
    }
    else {
        // ---------------- 8: LLAMA (pequeña, natural) ----------------
        // una lágrima más fina y alta que se abre arriba en dos o tres puntas (antes, de cerca, bolas redondas). La
        // que sale del suelo (z = 1) son varias lenguas de distinta altura que nacen juntas del suelo, cada una con su
        // vaivén (antes, una sola con la base redonda: de lejos, una cebolla naranja). w: el ritmo de cada llama
        float R = max(prm.x, 0.03) * 0.72;
        float H = max(prm.y, 0.1);
        float fl = clamp(prm.z, 0.0, 1.0);
        float ph = prm.w * 17.0;
        float tt = time + ph;
        float sink = mix(1.0, 0.35, fl);
        vec3 q = p;
        float v = clamp(q.y / H, 0.0, 1.0);
        q.x -= (sin(tt * 2.1) * 0.6 + sin(tt * 5.3) * 0.25) * v * v * R * 0.5;
        q.z -= cos(tt * 1.7) * 0.5 * v * v * R * 0.5;
        float rm = R * mix(1.0, 0.6, fl);
        d = mf_sdRoundCone(q - vec3(0.0, rm * sink, 0.0), rm, rm * 0.05, max(H * mix(0.92, 0.95, fl) - rm * sink, rm * 0.2));
        float uu = q.y / H;
        if (fl > 0.5) {
            // tres lenguas alrededor de la del centro, más bajas, que crecen y menguan a destiempo; donde se juntan
            // con la del centro, la altura (u) es la de cada una: sus puntas también se enfrían
            for (int i = 0; i < 3; i++) {
                float fi = float(i);
                float a = 2.094 * fi + ph * 1.3 + 0.4 * sin(tt * 0.7 + fi);
                vec2 o = vec2(cos(a), sin(a)) * R * 0.5;
                float ht = H * (0.5 + 0.35 * (0.5 + 0.5 * sin(tt * (2.3 + 0.9 * fi) + 1.7 * fi + ph)));
                float rt = R * 0.5;
                vec3 qt = q - vec3(o.x, 0.0, o.y);
                float vt = clamp(qt.y / ht, 0.0, 1.0);
                qt.x -= sin(tt * (3.1 + fi) + fi * 2.0) * vt * vt * rt * 0.8;
                qt.z -= cos(tt * (2.7 + 0.5 * fi) + fi) * vt * vt * rt * 0.8;
                float dt = mf_sdRoundCone(qt - vec3(0.0, rt * sink, 0.0), rt, rt * 0.04, max(ht - rt * sink, rt * 0.2));
                float kk = R * 0.3;
                uu = mix(uu, qt.y / ht, clamp(0.5 + 0.5 * (d - dt) / kk, 0.0, 1.0));
                d = mf_smin(d, dt, kk);
            }
        }
        // las puntas: arriba, alrededor del eje, se abren huecos que giran y suben (en el eje no: queda la del centro)
        float ang = atan(q.z, q.x);
        d += mix(0.9, 0.3, fl) * R * smoothstep(0.25, 0.9, v) * smoothstep(0.05 * R, 0.45 * R, length(q.xz))
           * (0.5 + 0.5 * sin(3.0 * ang + q.y / H * 2.0 - tt * 3.0));
        // (con eso la distancia ya no es exacta)
        mf_lip = 1.6;
        L = R * mix(0.9, 0.7, fl);
        u = uu;
        // el ruido estirado hacia arriba: vetas que suben, no grumos
        nq = vec3(q.x / (L * 1.2), q.y / (L * 3.6) - time * 3.8, q.z / (L * 1.2));
        nb = vec3(q.x / (L * 1.6), q.y / (L * 3.6) - time * 2.2, q.z / (L * 1.6));
        // (la del suelo, menos deshilachada: se ven sus lenguas, no jirones sueltos)
        amp = L * (mix(0.25, 0.15, fl) + mix(1.1, 0.7, fl) * v);
        if (mf_toon > 0.5 && VolStyle.y > 11.5 && VolStyle.y < 12.5) {
            // (anime) las llamas de Hidaruma (1:42): cuchillas largas, anchas cerca de la raíz y en punta arriba; la
            // raíz, deshilachada, y la punta, limpia
            float Rb = max(prm.x, 0.03);
            vec3 qb = p;
            qb.x -= sin(tt * 2.3 + 1.0) * 0.30 * Rb * v * v;
            float pr = (v < 0.28) ? pow(v / 0.28, 0.5) : pow((1.0 - v) / 0.72, 1.15);
            d = (length(qb.xz) - Rb * pr) * 0.80;
            d = max(d, max(-p.y, p.y - H));
            mf_lip = 1.3;
            L = Rb * 0.8;
            u = 1.0;
            nq = vec3(qb.x / (L * 1.1), p.y / (L * 4.5) - time * 4.5, qb.z / (L * 1.1));
            nb = nq;
            amp = L * (0.12 + 1.5 * (1.0 - v) * (1.0 - v) * (1.0 - v));
        }
    }
}

// Aspecto de cada forma: x = hondura del calor (en L; menos = el núcleo
// caliente más cerca de la superficie), y = temperatura mínima en la
// superficie, z = solidez (menos jirones), w = fuerza del halo.
vec4 mf_look(float shape, vec3 p, vec4 prm)
{
    if (shape < 0.5) return vec4(1.2, 0.32, 0.0, 1.7);
    if (shape < 1.5) { float S = max(prm.x, 0.2);
                       return vec4(mix(1.2, 0.55, smoothstep(-0.7 * S, -0.25 * S, p.z)), 0.26,
                                   0.6 * smoothstep(-0.30 * S, 0.0, p.z), 1.0); }
    if (shape > 1.5 && shape < 2.5) return vec4(1.5, 0.16, 0.45, 1.0);
    if (shape > 5.5 && shape < 6.5) return vec4(0.8, 0.28, 0.35, 1.0);
    if (shape > 6.5 && shape < 7.5) return vec4(0.6, 0.28, 0.30, 1.0);
    return vec4(1.1, 0.25, 0.0, 1.0);
}

// ---------------------------------------------------------------------
float mfBound(float shape, vec4 prm)
{
    if (shape < 0.5) return max(prm.x, 0.05) * (1.75 + 0.6 * clamp(prm.z, 0.0, 1.0) + 0.45 * clamp(prm.y, 0.0, 1.0)) + 0.3;
    if (shape < 1.5) return max(prm.x, 0.2) * 0.95 + max(prm.y, 0.0) * 1.1 + 0.3;
    if (shape < 2.5) { float R = max(prm.x, 0.1); float H = max(prm.y, 0.5);
                       return length(vec2(H * 1.25 + R, R * 2.2)) + 0.5; }
    if (shape < 3.5) { float th = (prm.z > 0.01) ? prm.z : 0.5;
                       return length(vec2(max(prm.x, 0.5) + th + 1.5, max(prm.y, 0.3) * 1.8 + 0.5)); }
    if (shape < 4.5) return length(vec3(max(prm.x, 0.5) + 1.5, max(prm.y, 0.5) * 1.5 + 0.5, max(prm.z, 0.1) + 1.5));
    if (shape < 5.5) return max(prm.x, 1.0) * 1.15 + max(prm.y, 0.3) * 1.6 + 0.3;
    if (shape < 6.5) { float th = (prm.y > 0.01) ? prm.y : 0.3; return max(prm.x, 0.3) * 1.6 + th * 3.0 + 0.3; }
    if (shape < 7.5) return max(prm.x, 0.5) * 1.15 + max(prm.y, 0.05) * 3.0 + 0.3;
    return length(vec2(max(prm.y, 0.1) * 1.5, max(prm.x, 0.03) * 2.5)) + 0.15;
}

// Tono de cuerpo negro: radiancia de Planck en R/G/B, normalizada al rojo.
// temp 0..1 -> ~1100K .. 3250K
vec3 mf_blackbody(float temp)
{
    float K  = 1100.0 + 2150.0 * temp;
    float c2 = 14388.0;
    vec3 lam = vec3(0.610, 0.550, 0.465);
    vec3 e   = exp(-c2 / (lam * K) + c2 / (0.610 * K));
    vec3 l5  = vec3(1.0, 1.7244, 3.8858);
    return l5 * e;
}

// Tono del fuego (como la rampa de color de fuego de Blender, más vivo):
// rojo oscuro -> rojo anaranjado -> naranja -> amarillo -> casi blanco.
vec3 mf_ramp(float t)
{
    vec3 c = mix(vec3(0.0), vec3(0.50, 0.05, 0.01), smoothstep(0.0, 0.18, t));
    c = mix(c, vec3(1.00, 0.24, 0.03), smoothstep(0.15, 0.38, t));
    c = mix(c, vec3(1.00, 0.50, 0.07), smoothstep(0.35, 0.58, t));
    c = mix(c, vec3(1.00, 0.76, 0.24), smoothstep(0.55, 0.78, t));
    c = mix(c, vec3(1.00, 0.96, 0.70), smoothstep(0.75, 0.95, t));
    return c;
}

// Las bandas del anime: rojo oscuro, rojo anaranjado, naranja, amarillo y
// el corazón casi blanco, con pasos suaves (no de golpe).
vec3 mf_bands(float t)
{
    vec3 c = vec3(0.50, 0.07, 0.02);
    c = mix(c, vec3(0.93, 0.26, 0.04), smoothstep(0.20, 0.26, t));
    c = mix(c, vec3(1.00, 0.55, 0.09), smoothstep(0.44, 0.50, t));
    c = mix(c, vec3(1.00, 0.82, 0.28), smoothstep(0.66, 0.72, t));
    c = mix(c, vec3(1.00, 0.97, 0.78), smoothstep(0.85, 0.91, t));
    return c;
}

// ---------------------------------------------------------------------
vec4 mfRender(vec3 ro, vec3 rd, float tNear, float tFar,
              float shape, vec4 prm, float time, float seed, float intensity, float nearFade, float thin, float pixAng)
{
    float flash = clamp(intensity - 1.0, 0.0, 1.0);   // destello del golpe
    intensity = clamp(intensity, 0.0, 1.0);
    if (intensity <= 0.002 || tFar <= tNear) return vec4(0.0);

    float B = mfBound(shape, prm);
    float bb = dot(ro, rd);
    float cc = dot(ro, ro) - B * B;
    float hh = bb * bb - cc;
    float tExit = (hh > 0.0) ? (-bb + sqrt(hh)) : tFar;
    float onGround = (tFar < tExit - 0.002 * B) ? 1.0 : 0.0;
    float perp = length(ro - rd * bb);
    float edgeFade = 1.0 - smoothstep(B * 0.78, B * 0.98, perp);

    // cada fuego con su propio ruido (desplazamiento pequeño: precisión)
    vec3 so = fract(vec3(seed) * vec3(0.1031, 0.1137, 0.1213) + vec3(0.13, 0.57, 0.91)) * 61.0;
    float tm = time;
    float isFist = (shape > 0.5 && shape < 1.5) ? 1.0 : 0.0;
    float isBall = (shape < 0.5) ? 1.0 : 0.0;
    float cool = (shape < 0.5) ? clamp(prm.y, 0.0, 1.0) : 0.0;
    // un golpe pequeño (las balas de Higan) acaba en una bocanada de humo gris claro y fino, no en una bola negra
    // w = 2: el sol de Entei (más caliente, el centro casi blanco, el borde que no se enfría tanto y más resplandor)
    float sunM = isBall * step(1.5, prm.w);
    float lsB = isBall * clamp(prm.w, 0.0, 1.0) * (1.0 - sunM);
    // la columna de Hibashira vista desde su hueco (primera persona), y la llama que sale del suelo
    float selfPil = (shape > 1.5 && shape < 2.5) ? clamp(prm.z, 0.0, 1.0) : 0.0;
    float flo = (shape > 7.5) ? clamp(prm.z, 0.0, 1.0) : 0.0;
    float isJet = (shape > 4.5 && shape < 5.5) ? 1.0 : 0.0;
    // la que está pegada a una pared no tiene suelo que le tape la base: abajo se deshace (si no, una bola colgada)
    float wallF = (shape > 7.5) ? step(1.5, prm.z) : 0.0;

    // entrada escalonada por píxel, sin cortes. Con un número sin patrón (antes, interleaved gradient noise: con
    // una tarjeta de verdad su rejilla de puntos se veía como ondas en las zonas finas)
    vec3 hq = fract(vec3(gl_FragCoord.xyx) * vec3(0.1031, 0.1030, 0.0973));
    hq += dot(hq, hq.yzx + 33.33);
    float jit = fract((hq.x + hq.y) * hq.z);
    float kEnt = 0.0;

    float T = 1.0;
    vec3  col = vec3(0.0);
    float minD = 1e5;
    // donde una bola se aclara por estar pegada a la cámara, tampoco deja halo: si no, con la cámara dentro de un
    // estallido que ya se ha abierto (o con el sol de Entei recién lanzado) quedaba un velo naranja liso por toda la
    // pantalla aunque ya no hubiera fuego delante
    float haloNear = isBall * step(0.001, nearFade);
    float Lref = 1.0;
    float wsum = 0.0;
    float ssum = 0.0;   // cuánto de lo que se ve es humo
    float tsum = 0.0;
    float t = tNear + jit * min(0.25, B * 0.03);   // también el halo, sin anillos
    bool first = true;
    // la última muestra con fuego (para lo que está metido dentro del fuego; ver después del bucle)
    float engT = -1e9; float engD = 0.0; float engL = 1.0; float engS = 0.0; float engTemp = 0.0; float engSm = 0.0;
    float engTp = -1e9; float engDp = 0.0;
    vec3 engC = vec3(0.0);

    for (int i = 0; i < 56; i++) {
        if (t >= tFar || T < 0.02) break;
        vec3 p = ro + rd * t;

        float d; float L; float u; vec3 nb; vec3 nq; float amp;
        mf_shape(p, shape, prm, tm, d, L, u, nb, nq, amp);
        d += (1.0 - intensity) * L * 0.9;          // se encoge al apagarse

        // las formas grandes son masa; las finas (puño, cruz, lanza, llama) guardan su forma
        float massy = (shape < 0.5 || (shape > 1.5 && shape < 5.5)) ? 1.0 : 0.0;
        float ampB = mix(amp * 1.1, max(amp, L * 0.55), massy);
        float envW = max(amp * 1.05, ampB * 1.05) + isFist * L * 0.9;
        float dEnv = d - envW;
        float stepIn = L * mix(0.12, 0.10, isFist);
        // el chorro de Kagerō visto a lo largo (en primera persona): su ruido es 3 o 4 veces más largo a lo largo que a
        // lo ancho, así que el paso también puede serlo (2,4 veces). Si no, los pasos se acababan al bloque de la mano y del chorro
        // solo se veía el principio, una bola
        stepIn *= 1.0 + 1.4 * isJet * rd.z * rd.z;
        if (dEnv > 0.0) {
            // espacio vacío: saltar hasta la envoltura (el halo, según lo cerca que pasa; no pegado a la cámara)
            if (t > 1.2) {
                float dh = d + haloNear * (1.0 - smoothstep(0.35 * nearFade, nearFade + 0.15, t)) * 4.0 * L;
                if (dh < minD) { minD = dh; Lref = L; }
            }
            float sk = max(dEnv / mf_lip, mf_dFar + (1.0 - intensity) * L * 0.9 - envW);
            t += max(sk * 0.85, stepIn * 0.5);
            first = true;
            continue;
        }
        if (first) {
            // al entrar (y al volver a entrar), un trozo de paso distinto en cada píxel
            first = false;
            t += stepIn * fract(jit + 0.618034 * kEnt);
            kEnt += 1.0;
            continue;
        }

        // ¿cuerpo (masa) o borde que se deshace (lenguas)?
        float w = smoothstep(0.55, 0.95, u) * (1.0 - cool);
        // la columna desde el hueco: toda ella son lenguas que suben (sin las bolsas de la masa: de cerca parecían la
        // costra de la lava); la llama del suelo, lenguas desde la mitad
        w = max(w, selfPil);
        w = mix(w, smoothstep(0.30, 0.75, u), flo);
        float tb = 0.35; float bhi = 0.0; float lo = 0.0; float hi = 0.0;
        if (w < 0.995) mf_billow(nb + so, tm * 0.6, tb, bhi);
        if (w > 0.005) mf_fbm2(nq * 1.15 + so.zxy, tm, lo, hi);
        // el detalle más fino solo donde cabe en la pantalla: de lejos, más pequeño que dos o tres píxeles, se
        // volvía un muaré (vetas como de madera en la columna de Hibashira)
        float aa = t * pixAng / L;
        // la columna de Hibashira vista de lejos (por fuera): las bolsas de la masa, tres veces más grandes, y sus
        // pliegues, más suaves. Con las pequeñas, a más de diez bloques la columna parecía una textura de lava
        // agrietada o la corteza de un árbol, no fuego que sube; de cerca siguen como estaban (son dos ruidos fijos en
        // el mundo y solo cambia la mezcla, así que al acercarse no se mueve nada)
        float farP = ((shape > 1.5 && shape < 2.5) ? 1.0 - selfPil : 0.0) * smoothstep(0.040, 0.085, aa);
        if (farP > 0.001 && w < 0.995) {
            float tb2 = 0.35; float bh2 = 0.0;
            mf_billow(nb * vec3(0.30, 0.38, 0.30) + so.yzx, tm * 0.6, tb2, bh2);
            tb = mix(tb, tb2, farP); bhi = mix(bhi, bh2, farP);
        }
        hi *= 1.0 - smoothstep(0.035, 0.12, aa);
        bhi *= 1.0 - smoothstep(0.08, 0.22, aa);
        float blo = tb - 0.35;

        // cuerpo: bolsas que empujan hacia fuera y pliegues que entran
        float nBody = 0.55 - 1.6 * tb - 0.15 * bhi;
        // borde: lenguas de llama
        float nTong = lo + ((shape < 0.5) ? 0.22 : 0.45) * hi;
        float dd = d + mix(ampB * nBody, amp * nTong, w);
        // dentro de la envoltura el halo mira el fuego de verdad, con sus huecos (y lo que ya es humo no brilla):
        // si no, por donde la forma está vacía se vería un disco de resplandor con el borde duro
        if (t > 1.2) {
            float de = max(dd, 0.0) + L * 1.5 * smoothstep(0.70, 1.15, u);
            de += haloNear * (1.0 - smoothstep(0.35 * nearFade, nearFade + 0.15, t)) * 4.0 * L;
            if (de < minD) { minD = de; Lref = L; }
        }

        // puño: piel suelta de llamas que se van hacia atrás
        float skin = 0.0;
        if (isFist > 0.5) {
            float S = max(prm.x, 0.2);
            float rear = smoothstep(-0.05 * S, -0.45 * S, p.z);
            float top  = smoothstep(0.02 * S, 0.22 * S, p.y) * smoothstep(0.55 * S, 0.1 * S, p.z);
            float m    = max(rear, 0.75 * top);
            float nn   = mix(blo * 1.5 + 0.4 * bhi, lo + 0.6 * hi, w);
            float lick = d - L * 0.15 * m + L * 0.85 * nn * m;
            skin = 0.5 * smoothstep(0.0, L * 0.4, -lick) * m;
        }

        // el borde: nítido en el cuerpo, muy suave donde el fuego ya se deshace en humo (sin bordes duros)
        // (la llama del suelo, con el borde más nítido hasta cerca de la punta: si no, de lejos era una nube)
        float dens = max(smoothstep(0.0, L * mix(mix(mix(0.30, 0.17, isFist), 0.14, step(1.5, shape) * step(shape, 2.5)), 0.14, flo)
                                         * (1.0 + mix(2.5, 1.5, flo) * smoothstep(mix(0.70, 0.85, flo), mix(1.15, 1.25, flo), u)), -dd), skin);
        if (dens > 0.001) {
            // toca el suelo suave, sin cortes; y pegado a la cámara se aclara (no tapa la vista)
            float gf = mix(1.0, smoothstep(0.0, max(0.12, L * 0.35), tFar - t), onGround);
            float nfk = smoothstep(0.35 * nearFade, nearFade + 0.15, t);
            dens *= mix(1.0, smoothstep(-0.06, 0.14, u), wallF);
            vec4 look = mf_look(shape, p, prm);
            // solo el borde se rompe en jirones; la masa es continua
            float uu = clamp(u, 0.0, 1.0);
            float brk = 0.45 + 0.55 * smoothstep(-0.55 + 0.45 * uu, 0.25 + 0.35 * uu, -hi);
            dens *= mix(1.0, mix(brk, 1.0, look.z), w);

            float depth = clamp(-dd / (L * look.x), 0.0, 1.0);
            // donde se aclara junto a la cámara, el corte se ve como la piel del fuego (más fría), no como el
            // corazón amarillo de dentro: si no, la pared aclarada era una bruma crema que lavaba la pantalla
            if (nearFade > 0.0) depth = min(depth, max(t - 0.35 * nearFade, 0.0) / (L * look.x));
            float hotLong = (isFist > 0.5 || (shape > 4.5 && shape < 5.5)) ? 1.0 : 0.0;
            // (la llama del suelo se enfría más arriba: si no, de su mitad para arriba casi no se veía)
            float coolU = 1.0 - 0.85 * smoothstep(mix(mix(0.45, 0.55, hotLong), 0.70, flo), mix(1.1, 1.3, flo), u);
            float temp = (look.y + (1.0 - look.y) * depth * (2.0 - depth)) * coolU;
            // y lo que se aclara junto a la cámara, más frío: queda un velo rojizo, no una bruma amarilla
            temp *= mix(0.5, 1.0, nfk);
            temp += sunM * (0.06 + 0.40 * depth * depth) * (1.0 - w);
            // bolsas más calientes; los pliegues entre ellas, más oscuros (hollín)
            float crease = (1.0 - smoothstep(0.03, 0.24, tb)) * (1.0 - w) * massy * mix(1.0, 0.35, farP);
            temp += mix(0.45, 0.28, farP) * (tb - 0.35) * (1.0 - w);
            // la columna: vetas calientes que suben y surcos más oscuros entre ellas (no una pared lisa)
            float isPil = (shape > 1.5 && shape < 2.5) ? 1.0 : 0.0;
            // (de lejos con menos contraste: allí las bolsas ya son grandes y se leen solas)
            temp += isPil * (mix(0.55, 0.18, farP) * (tb - 0.35) - 0.12 * bhi);
            // las cortinas (el muro y el cerco de Enjōmō): igual, y la base, donde nace el fuego, más caliente
            float isCur = (shape > 3.5 && shape < 4.5) ? 1.0 : ((shape > 2.5 && shape < 3.5) ? clamp(prm.w, 0.0, 1.0) : 0.0);
            temp += isCur * (0.45 * (tb - 0.35) - 0.10 * bhi + 0.22 * (1.0 - smoothstep(0.0, 0.40, u)));
            // la columna desde el hueco: el centro de cada lengua, amarillo; su borde, rojo; y vetas que suben
            temp += selfPil * (0.25 * mf_str + 0.32 * smoothstep(0.15, 1.1, mf_core) - 0.10 * (1.0 - smoothstep(0.0, 0.15, mf_core)));
            // la llama del suelo: la base, donde nace, casi amarilla
            temp += flo * 0.26 * (1.0 - smoothstep(0.0, 0.5, u));
            temp *= 1.0 - 0.18 * crease;
            float gran = (shape < 0.5) ? 1.5 : mix(1.0, 0.85, isFist);
            temp -= (0.10 * lo + 0.22 * hi) * coolU * gran * w;
            temp -= 0.10 * bhi * (1.0 - w);
            // la explosión vieja se vuelve humo de fuera hacia dentro y por los pliegues, mientras las bolsas que
            // abultan aún arden (manchas de fuego vivo entre humo negro, no todo marrón)
            float sx = cool * 1.55 - 0.62 - 0.75 * (tb - 0.35) - 0.40 * depth;
            float smokeX = (cool > 0.001) ? smoothstep(0.0, 0.30, sx) : 0.0;
            float tempFire = clamp(temp, 0.0, 1.0);
            temp *= (1.0 - 0.90 * smokeX) * (1.0 - 0.45 * cool);
            temp = clamp(mix(temp, 1.0, flash * 0.55), 0.0, 1.0);

            if (isFist > 0.5) {
                // los nudillos y los dedos se leen: el borde, más frío
                float S = max(prm.x, 0.2);
                vec3 q = p / S;
                vec2 e = vec2(0.02, -0.02);
                vec3 nrm = normalize(e.xyy * mf_sdFist(q + e.xyy) + e.yyx * mf_sdFist(q + e.yyx)
                                   + e.yxy * mf_sdFist(q + e.yxy) + e.xxx * mf_sdFist(q + e.xxx));
                float facing = clamp(dot(nrm, -rd), 0.0, 1.0);
                float body = smoothstep(-0.55 * S, -0.25 * S, p.z);
                temp *= mix(1.0, 0.25 + 0.85 * facing, body);
            }

            // las puntas se vuelven transparentes y dejan un hilo de humo
            float tip   = smoothstep(mix(0.70, 0.85, flo), mix(1.15, 1.25, flo), u);
            float smoke = smoothstep(0.85, 1.2, u) * (1.0 - temp) * mix(1.0, 0.3, isBall * (1.0 - cool));
            dens *= mix(1.0, mix(0.10, 0.22, smoke), tip);
            smoke = max(smoke, smokeX);

            dens *= 1.0 - 0.55 * lsB * smoke;
            float densE = dens;
            dens *= gf * nfk;
            float sigma = dens * intensity * 2.8 / L * thin;
            float aE = 1.0 - exp(-sigma * stepIn);
            // la masa caliente tapa lo de detrás; la llama fina y fría sobre todo brilla (transparente)
            float aA = aE * mix(mix(0.30, 0.70, massy * (1.0 - w)), 1.0, smoothstep(0.12, 0.50, temp));
            // color: el tono vivo del fuego con un poco de cuerpo negro; brillo según el calor
            float t2 = temp * temp;
            vec3 bbc = mf_blackbody(temp);
            vec3 hueC = mix(mf_ramp(temp), bbc / max(bbc.r, 1e-3), 0.25);
            vec3 emit = hueC * (0.6 + 2.0 * t2 + 1.4 * t2 * t2 * t2);
            emit *= 1.0 + 1.5 * flash + 0.5 * sunM;
            // el humo no brilla: se ve con la luz que haya (de día gris, de noche casi negro) y le entra más
            // luz por arriba que por abajo. Antes era casi negro siempre: de día salían manchones oscuros.
            float amb = 0.10 + 0.90 * clamp(VolSky, 0.0, 1.0);
            float Sv = max(max(prm.x, 0.05), 0.18 * (VolMax.y - VolMin.y));
            float upW = p.x * VolX.y + p.y * VolY.y + p.z * VolZ.y;   // la altura dentro de la forma, en el mundo
            float hgtG = smoothstep(-1.2 * Sv, 1.4 * Sv, upW);
            vec3 smokeCol = mix(vec3(0.085, 0.080, 0.078), vec3(0.46, 0.45, 0.44), hgtG * (0.55 + 0.45 * smokeX)) * amb;
            if (isBall > 0.5) {
                // el humo de la explosión: gris, con la luz del cielo arriba y oscuro abajo; junto al fuego, naranja
                float Rb = max(prm.x, 0.05);
                float hgt = smoothstep(-0.8 * Rb, 0.9 * Rb, p.y - cool * cool * Rb * 0.55);
                vec3 lit = mix(mix(vec3(0.085, 0.080, 0.078), vec3(0.34, 0.33, 0.32), lsB), mix(vec3(0.52, 0.50, 0.49), vec3(0.64, 0.63, 0.61), lsB),
                               hgt * (0.45 + 0.55 * tb / 0.6)) * amb;
                // pegado a lo que aún arde, el humo se ve naranja por debajo (eso sí es luz propia)
                smokeCol = mix(smokeCol, lit + mf_ramp(tempFire) * (0.7 * tempFire * (1.0 - smokeX)), smokeX);
            }
            emit = mix(emit, smokeCol, smoke);
            aA = mix(aA, aE * 0.92, smoke);
            engTp = engT; engDp = engD;
            engT = t; engD = dd; engL = L; engC = emit; engTemp = temp; engSm = smoke;
            engS = clamp(densE * intensity * 1.5, 0.0, 1.0) * aA / max(aE, 1e-5);
            col += T * emit * aE;
            float vis = T * aE * (1.0 - smoke);
            wsum += vis;
            ssum += T * aE * smoke;
            tsum += vis * temp;
            T *= 1.0 - aA;
        }
        t += stepIn;
    }

    // lo que está metido dentro de la masa de fuego (el poste contra el que estalla Hiken, el suelo bajo la bola, quien
    // está dentro de la columna): el rayo choca con ello a medio camino y solo cuenta el fuego de delante. Hondo en el
    // fuego, el fuego que lo rodea lo tapa y lo ilumina: se ve fuego (antes se veía el poste a través de la explosión,
    // con sus bordes rectos marcados en ella). Solo en las masas: en una llama fina, quien arde se sigue viendo
    float massyE = (shape < 0.5 || (shape > 1.5 && shape < 5.5)) ? 1.0 : 0.0;
    if (massyE > 0.5 && onGround > 0.5 && t >= tFar && engT > tFar - 0.4 * engL) {
        // (lo hondo que está, llevado hasta donde choca el rayo con las dos últimas muestras: con la última sola, el
        // borde salía granulado, porque cada píxel la toma a una distancia distinta)
        float dEnd = engD;
        if (engT - engTp < 0.3 * engL) dEnd += (engD - engDp) / max(engT - engTp, 1e-4) * (tFar - engT);
        float eng = smoothstep(0.0, engL * 0.8, -dEnd) * engS * (1.0 - smoothstep(0.0, 1.0, nearFade));
        col += T * engC * eng;
        float vis = T * eng * (1.0 - engSm);
        wsum += vis;
        tsum += vis * engTemp;
        ssum += T * eng * engSm;
        T *= 1.0 - eng;
    }

    // halo alrededor del fuego (como bloom), según lo cerca que pasó el rayo
    float look_w = mf_look(shape, vec3(0.0), prm).w;
    // (la nube de humo que queda después ya no brilla)
    float glow = exp(-max(minD, 0.0) / (Lref * 0.9)) * edgeFade * intensity * look_w * (1.0 - 0.7 * cool)
               * (1.0 - smoothstep(0.85, 1.0, cool));
    vec3 glowCol = mix(vec3(1.0, 0.42, 0.10), vec3(1.0, 0.58, 0.18), sunM);
    glow *= 1.0 + 0.7 * sunM;
    col += T * glowCol * glow * 0.13;

    // tono que guarda el color (naranja vivo); lo muy brillante quema hacia blanco
    float mx = max(col.r, max(col.g, col.b));
    float vv = 1.0 - exp(-mx * 1.8);
    vec3 rgb = col / max(mx, 1e-4) * vv;
    rgb = mix(rgb, vec3(vv), smoothstep(4.0, 10.0, mx) * 0.35);
    float a = 1.0 - T;

    // técnica de anime: el color de lo que se ve, por bandas, mezclado con
    // el de verdad -> formas claras y colores limpios sin perder el volumen
    float tv = tsum / max(wsum, 1e-4);
    float coverage = clamp(wsum, 0.0, 1.0);
    vec3 band = mf_bands(tv) * coverage * (0.82 + 0.25 * flash);
    // (lo que es humo no se pinta con las bandas del fuego: seguiría rojo)
    float smokeFrac = ssum / max(ssum + wsum, 1e-4);
    rgb = mix(rgb, max(rgb, band), 0.40 * smoothstep(0.05, 0.4, coverage) * (1.0 - smokeFrac));

    float alpha = clamp(a + min(glow, 1.0) * 0.12, 0.0, 1.0);
    return vec4(min(rgb, vec3(1.0)), alpha);
}

// =====================================================================
//  ESTILO ANIME: el fuego dibujado como en la serie
//
//  Colores planos con el borde limpio, sin humo ni degradados: una masa de un color, su borde de otro (grueso, del
//  mismo ancho por todo el contorno), venas del color del borde por los surcos entre los lóbulos, el corazón de las
//  masas gordas de un tercer color y un resplandor corto y suave por fuera. Animado «a doses» (12 dibujos por segundo).
//  Es la misma forma 3D de siempre: se recorre el rayo, pero en vez de sumar luz se mide cuánto se mete en la forma
//  (el borde), dónde la toca primero (las venas) y por dónde pasó cerca (el resplandor).
//
//  Paletas (VolStyle.y; 0 = la de cada forma):
//    1 Hiken        borde y venas amarillos, masa roja, manchas negras en lo más hondo
//    2 natural      borde rojo anaranjado, masa amarilla, corazón amarillo pálido
//    3 Marineford   masa dorada, y el borde y las venas amarillo pálido (el fuego de Marineford no tiene borde oscuro)
//    4 Kagerō       borde naranja, chorro amarillo vivo, corazón pálido
//    5 Hotarubi     bolas lisas: verde en la orilla que se aclara hacia el centro, con un halo verde
//    6 sol de Entei casi todo blanco amarillento, con un filo amarillo muy fino y un resplandor ancho
//    7 Jūjika       cruz amarillo limón con el centro pálido y el filo dorado
//    8 humo         gris claro plano con los surcos más oscuros (lo que queda de una explosión)
//    9 columna      el haz de Hibashira: casi todo amarillo pálido, una banda amarilla y el filo naranja
//   10 remolino     rojo plano sin borde, con llamas amarillas montadas encima (el remolino de Hibashira)
// =====================================================================
void mf_toonPal(float id, out vec3 cRim, out vec3 cVein, out vec3 cFill, out vec3 cCore, out vec3 cGlow, out vec4 k, out vec2 gk)
{
    // gk: x hasta dónde llega el resplandor de fuera (en L) · y su fuerza
    gk = vec2(0.30, 0.60);
    // k: x ancho del borde (en L) · y hondura del corazón (en L) · z ancho de las venas (0 = sin venas) · w manchas negras
    cRim  = vec3(0.992, 0.784, 0.165); cVein = cRim; cFill = vec3(0.961, 0.341, 0.286); cCore = vec3(0.07, 0.02, 0.03);
    cGlow = vec3(1.0, 0.52, 0.10); k = vec4(0.62, 2.3, 1.0, 1.0);
    if (id > 1.5 && id < 2.5) {
        cRim = vec3(0.945, 0.290, 0.130); cVein = vec3(0.969, 0.490, 0.110); cFill = vec3(0.992, 0.800, 0.180); cCore = vec3(1.0, 0.965, 0.690);
        cGlow = vec3(1.0, 0.36, 0.10); k = vec4(0.60, 2.2, 1.7, 0.0);
    } else if (id > 2.5 && id < 3.5) {
        cRim = vec3(0.992, 0.914, 0.627); cVein = vec3(0.996, 0.925, 0.660); cFill = vec3(0.984, 0.796, 0.352); cCore = vec3(0.990, 0.826, 0.410);
        cGlow = vec3(0.990, 0.820, 0.420); k = vec4(0.16, 2.6, 1.0, 0.0); gk = vec2(0.42, 0.72);
    } else if (id > 3.5 && id < 4.5) {
        cRim = vec3(0.925, 0.470, 0.130); cVein = vec3(0.945, 0.650, 0.140); cFill = vec3(0.960, 0.880, 0.140); cCore = vec3(1.0, 0.960, 0.560);
        cGlow = vec3(0.90, 0.30, 0.10); k = vec4(0.40, 1.5, 0.0, 0.0);
    } else if (id > 4.5 && id < 5.5) {
        cRim = vec3(0.450, 0.960, 0.580); cVein = cRim; cFill = vec3(0.660, 0.995, 0.710); cCore = vec3(0.800, 0.990, 0.810);
        cGlow = vec3(0.34, 0.82, 0.46); k = vec4(0.35, 1.0, 0.0, 0.0); gk = vec2(0.75, 0.80);
    } else if (id > 5.5 && id < 6.5) {
        cRim = vec3(0.976, 0.860, 0.400); cVein = cRim; cFill = vec3(0.984, 0.972, 0.740); cCore = vec3(0.996, 1.0, 0.860);
        cGlow = vec3(1.0, 0.66, 0.22); k = vec4(0.05, 0.16, 0.0, 0.0); gk = vec2(0.80, 0.85);
    } else if (id > 6.5 && id < 7.5) {
        cRim = vec3(0.992, 0.800, 0.390); cVein = cRim; cFill = vec3(0.996, 0.960, 0.500); cCore = vec3(1.0, 0.992, 0.650);
        cGlow = vec3(1.0, 0.72, 0.22); k = vec4(0.16, 0.50, 0.0, 0.0); gk = vec2(0.60, 0.85);
    } else if (id > 8.5 && id < 9.5) {
        cRim = vec3(0.965, 0.690, 0.190); cVein = cRim; cFill = vec3(0.996, 0.900, 0.240); cCore = vec3(0.988, 0.945, 0.600);
        cGlow = vec3(1.0, 0.55, 0.12); k = vec4(0.16, 0.48, 0.0, 0.0); gk = vec2(0.50, 0.75);
    } else if (id > 13.5) {
        // 14 las estelas que deja el Hiken (0:38-0:40): chorros rojos con un borde amarillo anaranjado ancho y sin manchas
        cRim = vec3(0.992, 0.700, 0.220); cVein = cRim; cFill = vec3(0.957, 0.365, 0.250); cCore = cFill;
        cGlow = vec3(1.0, 0.72, 0.20); k = vec4(0.70, 1.0e3, 0.0, 0.0); gk = vec2(0.50, 0.80);
    } else if (id > 12.5) {
        // 13 la cúpula roja del final de Entei (3:32): roja y plana, con el filo y unas grietas naranjas, y mucho resplandor
        cRim = vec3(1.0, 0.540, 0.140); cVein = cRim; cFill = vec3(0.945, 0.137, 0.086); cCore = cFill;
        cGlow = vec3(1.0, 0.30, 0.12); k = vec4(0.07, 1.0e3, 0.0, 0.0); gk = vec2(1.10, 0.95);
    } else if (id > 11.5) {
        // 12 Hidaruma (1:42): cuchillas con el filo naranja, amarillas y el centro pálido, sin línea de borde
        cRim = vec3(0.945, 0.506, 0.075); cVein = cRim; cFill = vec3(0.988, 0.812, 0.118); cCore = vec3(1.0, 0.945, 0.376);
        cGlow = vec3(1.0, 0.50, 0.08); k = vec4(0.20, 0.62, 0.0, 0.0); gk = vec2(0.55, 0.65);
    } else if (id > 10.5) {
        // 11 los trazos: las lanzas de Shiranui (2:11) y las balas de Higan (0:57), amarillo pálido con el filo dorado
        cRim = vec3(0.961, 0.784, 0.353); cVein = cRim; cFill = vec3(0.992, 0.953, 0.651); cCore = cFill;
        cGlow = vec3(1.0, 0.80, 0.36); k = vec4(0.30, 1.0e3, 0.0, 0.0); gk = vec2(0.32, 0.55);
    } else if (id > 9.5) {
        cRim = vec3(0.871, 0.208, 0.170); cVein = cRim; cFill = cRim; cCore = cRim;
        cGlow = vec3(0.95, 0.30, 0.10); k = vec4(0.0, 1.0e3, 0.0, 0.0); gk = vec2(0.22, 0.40);
    } else if (id > 7.5) {
        cRim = vec3(0.560, 0.540, 0.560); cVein = vec3(0.620, 0.600, 0.620); cFill = vec3(0.860, 0.850, 0.860); cCore = vec3(0.960, 0.955, 0.960);
        cGlow = vec3(0.0); k = vec4(0.45, 2.2, 1.0, 0.0);
    }
}

// la paleta de cada forma cuando no se pide otra
float mf_toonDefault(float shape, vec4 prm)
{
    if (shape < 0.5) return (prm.w > 1.5) ? 6.0 : 2.0;     // el sol de Entei · las bolas y explosiones
    if (shape < 1.5) return 1.0;                            // Hiken
    if (shape < 2.5) return (prm.z > 0.5) ? 2.0 : 9.0;     // Hibashira: desde dentro, llamas · desde fuera, el haz
    if (shape < 3.5) return (prm.w > 1.5) ? 10.0 : 2.0;    // el remolino de Hibashira · los anillos y el cerco
    if (shape < 4.5) return 3.0;                            // el muro (Kyōkaen)
    if (shape < 5.5) return 4.0;                            // Kagerō
    if (shape < 6.5) return 7.0;                            // Jūjika
    if (shape < 7.5) return 11.0;                           // las lanzas y las balas
    return 3.0;                                             // las llamas sueltas y las del cuerpo (y su aura)
}

// El campo del dibujo: la forma con sus lóbulos y lenguas grandes, sin detalle fino. Fuera de la envoltura no calcula
// el ruido y devuelve en skip la distancia segura hasta ella (> 0).
float mf_toonField(vec3 p, float shape, vec4 prm, float tm, vec3 so, float shrink,
                   out float L, out float u, out float skip)
{
    float d; vec3 nb; vec3 nq; float amp;
    mf_shape(p, shape, prm, tm, d, L, u, nb, nq, amp);
    d += shrink * L;
    float massy = (shape < 0.5 || (shape > 1.5 && shape < 5.5)) ? 1.0 : 0.0;
    // (el haz de Hibashira y su remolino son lisos: sin bolsas)
    if ((shape > 1.5 && shape < 2.5 && prm.z < 0.5) || (shape > 2.5 && shape < 3.5 && prm.w > 1.5)) massy = 0.0;
    float isFist = (shape > 0.5 && shape < 1.5) ? 1.0 : 0.0;
    float cool = (shape < 0.5) ? clamp(prm.y, 0.0, 1.0) : 0.0;
    float selfPil = (shape > 1.5 && shape < 2.5) ? clamp(prm.z, 0.0, 1.0) : 0.0;
    float flo = (shape > 7.5 && shape < 8.5) ? clamp(prm.z, 0.0, 1.0) : 0.0;
    float aura = (shape > 8.5) ? 1.0 : 0.0;
    // el sol de Entei es una bola lisa con unas pocas llamas; y el humo de una explosión, bolsas redondas, no jirones
    float sunM = (shape < 0.5) ? step(1.5, prm.w) : 0.0;
    // las luciérnagas de Hotarubi (paleta 5) también son bolas lisas
    float orb = (shape < 0.5 && VolStyle.y > 4.5 && VolStyle.y < 5.5) ? 1.0 : 0.0;
    sunM = max(sunM, orb);
    amp *= mix(1.0, 0.15, sunM) * (1.0 - 0.8 * orb);
    // la cruz de Jūjika son dos trazos casi rectos
    if (shape > 5.5 && shape < 6.5) amp *= 0.40;
    // las lanzas y las balas, trazos limpios
    if (shape > 6.5 && shape < 7.5) amp *= 0.45;
    if (cool > 0.001) amp = min(amp, max(prm.x, 0.05) * mix(0.30, 0.20, cool));
    float ampB = mix(mix(amp * 1.1, max(amp, L * 0.55), massy), L * 0.05, sunM);
    float envW = max(amp * 1.25, ampB * 0.85);
    skip = 0.0;
    if (d - envW > 0.0) {
        skip = max((d - envW) / mf_lip, mf_dFar + shrink * L - envW);
        return d;
    }
    float w = smoothstep(0.55, 0.95, u) * (1.0 - cool);
    w = max(w, selfPil);
    w = mix(w, smoothstep(0.30, 0.75, u), flo);
    // las bolas y explosiones: lenguas que salen ya desde el borde de la masa (llamas en punta, no solo bolsas)
    if (shape < 0.5) w = max(w, smoothstep(0.25, 0.80, u) * (1.0 - cool) * (1.0 - sunM));
    // el aura del cuerpo: casi todo lenguas
    w = mix(w, smoothstep(0.10, 0.50, u), aura);
    float nB = 0.0; float nT = 0.0;
    // lóbulos: una sola onda en valor absoluto (bolsas lisas y grandes con un surco limpio entre ellas)
    if (w < 0.995) nB = 0.42 - 1.75 * abs(mf_gnoise(nb * 0.62 + so));
    // lenguas: una onda estirada con el flujo
    // (en las explosiones, las lenguas más largas: acaban en punta)
    if (shape < 0.5 && sunM < 0.5) nq.y *= mix(0.62, 1.0, cool);
    if (w > 0.005) nT = 1.15 * mf_gnoise(nq * 0.62 + so.zxy);
    return d + mix(ampB * nB, amp * nT, w);
}

float mf_aastep(float th, float v)
{
    float fw = clamp(fwidth(v) * 0.75, 1e-4, 0.35);
    return smoothstep(th - fw, th + fw, v);
}

vec4 mfRenderToon(vec3 ro, vec3 rd, float tNear, float tFar,
                  float shape, vec4 prm, float time, float seed, float intensity, float nearFade, float pixAng)
{
    float flash = clamp(intensity - 1.0, 0.0, 1.0);
    intensity = clamp(intensity, 0.0, 1.0);
    if (intensity <= 0.002 || tFar <= tNear) return vec4(0.0);

    mf_toon = 1.0;
    vec3 so = fract(vec3(seed) * vec3(0.1031, 0.1137, 0.1213) + vec3(0.13, 0.57, 0.91)) * 61.0;
    // animado «a doses», como el dibujo: la forma cambia 12 veces por segundo
    float tm = floor(time * 12.0 + 0.5) / 12.0;
    float shrink = (1.0 - intensity) * 0.9;

    // pegado a la cámara el fuego no se dibuja (no tapa la vista): el rayo empieza un poco más allá
    float t = max(tNear, 0.35 * nearFade);
    if (shape > 8.5) {
        // el aura del cuerpo: el rayo empieza donde pasa más cerca del eje del cuerpo, así solo se dibuja el fuego
        // de detrás y el que asoma por el contorno (el cuerpo, que está delante, se ve entero)
        float den = rd.x * rd.x + rd.z * rd.z;
        if (den > 1.0e-5) t = max(t, -(ro.x * rd.x + ro.z * rd.z) / den);
    }
    float pen = -1.0e5; float tPen = t; float uPen = 0.0;
    float t0 = -1.0; float L0 = 1.0; float u0 = 0.0;
    float minOut = 1.0e5;
    float pA = -1.0e5; float pB = -1.0e5; float tB = t;   // las dos muestras anteriores (para afinar el máximo)
    float dPrev = 1.0; float tPrev = t; bool hasPrev = false;
    float Lr = 1.0;
    bool startIn = false;                     // el rayo empezó ya dentro del fuego (no hay superficie que mirar)
    float ribMin = 1.0e5; float tRib = t;     // las cintas: lo más cerca que pasa el rayo de una, y dónde
    float penF = -1.0e5;                      // lo que se mete el rayo en la capa de encima (el puño)

    for (int i = 0; i < 72; i++) {
        if (t >= tFar) break;
        vec3 p = ro + rd * t;
        float L; float u; float skip;
        float dd = mf_toonField(p, shape, prm, tm, so, shrink, L, u, skip);
        float rib = (mf_rib < 1.0e4) ? (mf_rib + shrink * L) / L : 1.0e5;
        if (rib < ribMin) { ribMin = rib; tRib = t; }
        float stepIn = L * 0.13;
        if (shape > 4.5 && shape < 5.5) stepIn *= 1.0 + 1.4 * rd.z * rd.z;
        if (skip > 0.0) {
            minOut = min(minOut, dd / L);
            Lr = L;
            // (cerca de una cinta, pasos cortos para no saltársela, pero nunca más cortos que dentro del fuego)
            t += max(min(skip, rib * L) * 0.85, stepIn);
            hasPrev = false; pA = -1.0e5; pB = -1.0e5;
            continue;
        }
        float pp = -dd / L;
        if (mf_part < 1.0e4) penF = max(penF, -(mf_part + shrink * L) / L);
        if (pp < 0.0) minOut = min(minOut, -pp);
        // la primera vez que entra: el punto justo, entre esta muestra y la anterior
        if (t0 < 0.0 && pp > 0.0) {
            t0 = (hasPrev && dPrev > 0.0) ? tPrev + (t - tPrev) * dPrev / (dPrev - dd) : t;
            startIn = (i == 0);
            L0 = L; u0 = u;
        }
        // lo más hondo que llega, afinado con las tres últimas muestras (sin escalones entre píxeles)
        if (pp > pen) { pen = pp; tPen = t; uPen = u; }
        if (pB > -1.0e4 && pA > -1.0e4 && pB >= pA && pB >= pp) {
            float den = 2.0 * pB - pA - pp;
            float pk = pB + ((den > 1.0e-5) ? (pp - pA) * (pp - pA) / (8.0 * den) : 0.0);
            if (pk > pen) { pen = pk; tPen = tB; }
        }
        pA = pB; pB = pp; tB = t;
        dPrev = dd; tPrev = t; hasPrev = true; Lr = L;
        t += stepIn;
    }

    // lo que está metido en el fuego (el suelo bajo un aro, un bloque dentro de una explosión): donde el rayo choca
    // con el mundo dentro de la forma, cuenta como hondo según lo dentro que quede ese punto. Así un aro plano en el
    // suelo es masa por dentro y borde solo en su orilla, y la base de una llama no es toda borde
    float B = mfBound(shape, prm);
    float bb = dot(ro, rd);
    float hh = bb * bb - (dot(ro, ro) - B * B);
    float tExit = (hh > 0.0) ? (-bb + sqrt(hh)) : tFar;
    if (tFar < tExit - 0.002 * B && t0 >= 0.0) {
        float Le; float ue; float se;
        float de = mf_toonField(ro + rd * tFar, shape, prm, tm, so, shrink, Le, ue, se);
        if (se <= 0.0 && de < 0.0) pen = max(pen, -de / Le * 3.0);
    }

    vec3 cRim; vec3 cVein; vec3 cFill; vec3 cCore; vec3 cGlow; vec4 k; vec2 gk;
    float pal = (VolStyle.y > 0.5) ? VolStyle.y : mf_toonDefault(shape, prm);
    mf_toonPal(pal, cRim, cVein, cFill, cCore, cGlow, k, gk);
    float cool = (shape < 0.5) ? clamp(prm.y, 0.0, 1.0) : 0.0;
    if (cool > 0.001) {
        // la explosión que se apaga: humo plano, gris claro (primero el borde, luego todo)
        vec3 sR; vec3 sV; vec3 sF; vec3 sC; vec3 sG; vec4 sk; vec2 sg;
        mf_toonPal(8.0, sR, sV, sF, sC, sG, sk, sg);
        float s1 = smoothstep(0.25, 0.60, cool); float s2 = smoothstep(0.45, 0.85, cool);
        cRim = mix(cRim, sR, s1); cVein = mix(cVein, sV, s1); cFill = mix(cFill, sF, s2); cCore = mix(cCore, sC, s2);
        cGlow *= 1.0 - s1; k = mix(k, sk, s2);
    }
    // el puño: el borde más fino (si no, los dedos enteros serían borde)
    if (shape > 0.5 && shape < 1.5) { k.x = 0.30; k.z = 1.20; k.w = 0.0; k.y = 1.0e3; }
    // las llamas sueltas y las lanzas son finas: con el borde de las masas serían casi todo borde
    if (shape > 6.5 && shape < 8.5) { k.x *= 0.40; k.y = min(k.y, 0.80); }
    // las cortinas (el cerco de Enjōmō, el muro): de frente son una lámina; el borde, solo en las puntas, y lo gordo
    // de abajo, con su corazón
    if ((shape > 2.5 && shape < 3.5 && prm.w > 0.5 && prm.w < 1.5) || (shape > 3.5 && shape < 4.5)) { k.x *= 0.45; k.y = min(k.y, 0.80); }
    if (VolStyle.z > 0.0) k.x = VolStyle.z;
    // el aura del cuerpo: su ruido va retorcido y el rayo pasa rozando muchas lenguas; el resplandor, pegado al borde
    if (shape > 8.5) gk = vec2(0.13, 0.60);

    // (los trazos y las cuchillas, de filo blando: dentro, el resplandor entero, sin aro)
    if (pal > 10.5 && pal < 12.5 && pen > 0.0) minOut = 0.0;
    // el resplandor corto de fuera
    float gl = exp(-max(min(minOut, ribMin), 0.0) / gk.x) * gk.y * intensity;
    // (el resplandor se apaga antes de llegar al borde de la esfera que envuelve la forma: sin corte a la vista)
    gl *= 1.0 - smoothstep(0.78 * B, 0.98 * B, sqrt(max(dot(ro, ro) - bb * bb, 0.0)));
    // las cintas: trazos finos del color del borde, por delante o por detrás del cuerpo según dónde pasan
    float ribA = (ribMin < 1.0e4) ? (1.0 - mf_aastep(0.0, ribMin)) * intensity : 0.0;
    if (pen <= 0.0 || t0 < 0.0) {
        float a0 = ribA;
        float g0 = gl * (1.0 - a0);
        if (a0 + g0 < 0.004) return vec4(0.0);
        return vec4(cRim * a0 + cGlow * g0, a0 + g0 * 0.75);
    }

    // dónde toca primero el rayo: la normal y los surcos (un punto un poco hacia fuera está cerca de otra pared)
    vec3 p0 = ro + rd * t0;
    float e = 0.07 * L0;
    float La; float ua; float sa;
    vec2 ev = vec2(1.0, -1.0);
    float f1 = mf_toonField(p0 + ev.xyy * e, shape, prm, tm, so, shrink, La, ua, sa);
    float f2 = mf_toonField(p0 + ev.yyx * e, shape, prm, tm, so, shrink, La, ua, sa);
    float f3 = mf_toonField(p0 + ev.yxy * e, shape, prm, tm, so, shrink, La, ua, sa);
    float f4 = mf_toonField(p0 + ev.xxx * e, shape, prm, tm, so, shrink, La, ua, sa);
    vec3 nrm = ev.xyy * f1 + ev.yyx * f2 + ev.yxy * f3 + ev.xxx * f4;
    float nl = length(nrm);
    nrm = (nl > 1.0e-6) ? nrm / nl : -rd;
    float gmag = nl / (4.0 * e);                    // cuánto cambia el campo por bloque (1 = distancia de verdad)
    float h = 0.55 * L0;
    float fo = mf_toonField(p0 + nrm * h, shape, prm, tm, so, shrink, La, ua, sa);
    float open = clamp(fo / (h * max(gmag, 0.35)), 0.0, 1.0);   // 1 = superficie abierta, menos = surco
    if (startIn) open = 1.0;

    // el borde de dentro no es paralelo al de fuera: sube y baja como las llamas
    float wob = mf_gnoise(p0 / (L0 * 2.2) + so.yzx + vec3(0.0, 0.0, tm * 0.9));
    float isFist = (shape > 0.5 && shape < 1.5) ? 1.0 : 0.0;
    float calm = max(isFist, (shape < 0.5) ? step(1.5, prm.w) : 0.0);   // el puño y el sol: el borde de dentro, casi paralelo
    if (shape > 1.5 && shape < 2.5 && prm.z < 0.5) calm = 1.0;          // y el haz de Hibashira
    float vp = pen + mix(0.32, 0.10, calm) * wob * min(pen, 1.5);

    // el puño va dibujado encima de su cuerpo de fuego: donde el rayo toca primero el puño, el borde es el del puño
    // solo (así su contorno se ve también contra el fuego de detrás, como en el 0:32)
    if (isFist > 0.5) {
        float Lq; float uq; float sq;
        mf_toonField(p0, shape, prm, tm, so, shrink, Lq, uq, sq);
        float onF = 1.0 - smoothstep(0.10, 0.30, (mf_part + shrink * Lq) / Lq);
        vp = mix(vp, min(vp, penF * 1.15), onF);
    }
    // los trazos y las cuchillas de Hidaruma no llevan línea: el filo se funde con el aire
    float soft = (pal > 10.5 && pal < 12.5) ? 1.0 : 0.0;
    // la línea del dibujo no adelgaza con la distancia: de lejos el borde ocupa lo mismo en pantalla (como en el
    // 0:34, donde el borde amarillo del cometa es tan gordo como en el primer plano del 0:32). Solo en las masas
    if (shape < 1.5 && soft < 0.5 && VolStyle.z <= 0.0 && k.x > 0.0) {
        float kMin = 0.034 * pixAng * ViewSize.y * t0 / L0;
        k.x = clamp(kMin, k.x, k.x * ((shape > 0.5) ? 3.5 : 2.2));
    }
    // y tampoco engorda al acercarse: pegado a la cámara (un chorro visto en primera persona) el borde no pasa de
    // un 6 % del alto de la pantalla
    if (soft < 0.5 && VolStyle.z <= 0.0) k.x = min(k.x, 0.065 * pixAng * ViewSize.y * t0 / L0);
    float sil = mf_aastep(0.0, pen);
    if (soft > 0.5) sil = smoothstep(0.0, 0.22, pen);
    float bFill = mf_aastep(k.x, vp);
    float bCore = mf_aastep(k.y, vp);
    vec3 c = mix(cRim, cFill, bFill);
    // las venas: el color del borde por los surcos (no en el propio borde, que ya lo es)
    if (k.z > 0.001) {
        float vn = (1.0 - mf_aastep(1.0 - 0.38 * k.z, open)) * bFill;
        c = mix(c, cVein, vn);
    }
    if (shape > 2.5 && shape < 3.5 && prm.w < 0.5) {
        // los anillos del suelo (no la cortina de Enjōmō): masa naranja con aros amarillos que corren hacia fuera,
        // como el aro del Dai Enkai del 2:59
        float th = (prm.z > 0.01) ? prm.z : 0.5;
        float R0 = max(prm.x, 0.5);
        float lr = length(p0.xz);
        float q = lr / (R0 + th) + 0.035 * wob;              // 0 en el centro, 1 en la orilla
        float eo = (lr - R0) / th;                           // -1 la orilla de dentro, +1 la de fuera
        float edge = (R0 > th * 1.05) ? abs(eo) : eo;        // (un disco lleno no tiene orilla de dentro)
        float wr = mf_gnoise(vec3(p0.xz * 1.4, tm * 1.3) + so);
        float rimK = mf_aastep(0.80 + 0.16 * wr, edge);
        // los aros: finos, casi redondos y a trozos
        float ph = q * 3.4 - tm * 0.8;
        float fr = abs(fract(ph) - 0.5);
        float ang = atan(p0.z, p0.x);
        float brk = mf_gnoise(vec3(cos(ang) * 2.2, sin(ang) * 2.2, floor(ph) * 3.7) + so.yzx);
        float ring = (1.0 - mf_aastep(0.085, fr)) * mf_aastep(-0.20, brk) * step(0.30, q);
        vec3 cBase = mix(cRim, cFill, 0.45);
        c = mix(cBase, cFill, ring);
        c = mix(c, mix(cRim, cBase, 0.25), rimK);
        bCore = 0.0;
    }
    if (pal > 12.5 && pal < 13.5) {
        // la cúpula: grietas finas y curvas del color del filo
        vec3 pc = p0 / (((shape < 0.5) ? max(prm.x, 0.05) : L0 * 4.0) * 0.42) + so.yzx;
        float cn = abs(mf_gnoise(pc + vec3(0.0, tm * 0.15, 0.0)) + 0.45 * mf_gnoise(pc * 2.3 + 3.1));
        float gate = mf_aastep(-0.10, mf_gnoise(pc * 0.7 + 7.7));
        c = mix(c, cVein, (1.0 - mf_aastep(0.040, cn)) * gate * bFill);
    }
    if (soft > 0.5) {
        // sin bandas: del filo al centro, poco a poco
        c = mix(cRim, cFill, smoothstep(0.04, k.x * 2.0, pen));
        c = mix(c, cCore, smoothstep(k.y * 0.65, k.y * 1.25, pen));
        bCore = 0.0;
    }
    if (pal > 4.5 && pal < 5.5) {
        // las luciérnagas: sin bandas, el verde de la orilla se aclara poco a poco hacia el centro
        c = mix(cRim, cFill, smoothstep(0.0, 1.3, pen));
        c = mix(c, cCore, smoothstep(0.9, 2.7, pen));
        bCore = 0.0;
    }
    if (shape > 2.5 && shape < 3.5 && prm.w > 2.5) {
        // los tajos de Shiranui (2:00): un arco con el canto de fuera rojo, el medio naranja y el de dentro amarillo
        float th = (prm.z > 0.01) ? prm.z : 0.5;
        float R0 = max(prm.x, 0.5);
        float lag = min(fract((tm * 7.0 - atan(p0.z, p0.x)) / 6.2832) / 0.40, 1.0);
        float rin = (length(p0.xz) - R0 * (0.85 + 0.40 * lag)) / th;
        vec2 pr = mf_rot(p0.xz, -tm * 7.0);
        float wv = mf_gnoise(vec3(pr * 1.1, tm * 0.7) + so) + 0.5 * mf_gnoise(vec3(pr * 2.6, 3.0) + so.zxy);
        float rv = rin + 0.45 * wv;
        c = vec3(0.992, 0.925, 0.500);
        c = mix(c, vec3(0.992, 0.800, 0.180), mf_aastep(-0.62, rv));
        c = mix(c, vec3(0.957, 0.520, 0.120), mf_aastep(-0.15, rv));
        c = mix(c, vec3(0.910, 0.250, 0.160), mf_aastep(0.30, rv));
        bCore = 0.0;
    } else if (shape > 2.5 && shape < 3.5 && prm.w > 1.5) {
        // el remolino de Hibashira: rojo plano, y montadas en él (por dentro y hacia la cabeza) llamas amarillas con
        // el centro pálido y el filo naranja; sueltas por el resto, algunas lenguas amarillas pequeñas
        float th = (prm.z > 0.01) ? prm.z : 0.5;
        float R0 = max(prm.x, 0.5);
        float lag = min(fract((tm * 4.5 - atan(p0.z, p0.x)) / 6.2832) / 0.86, 1.0);
        float rin = (length(p0.xz) - R0 * (0.85 + 0.40 * lag)) / th;
        float headK = 1.0 - smoothstep(0.05, 0.60, lag);
        float H0 = max(prm.y, 0.3);
        vec2 pr = mf_rot(p0.xz, -tm * 4.5);
        float yn = mf_gnoise(vec3(pr * 1.3, p0.y * 1.3 + tm * 0.8) + so);
        float up = clamp((p0.y - 0.9 * H0) / H0, 0.0, 1.6);
        float yv = headK * (0.62 - 0.45 * rin) + 0.50 * yn + 0.40 * min(up, 1.0);
        // (lenguas sueltas, alargadas a lo largo del remolino)
        float lick = mf_gnoise(vec3(lag * 9.0, length(p0.xz) / th * 1.7, tm * 1.1) + so.zxy);
        yv = max(yv, 0.10 + 1.15 * lick);
        c = mix(c, vec3(0.930, 0.400, 0.110), mf_aastep(0.50, yv));
        c = mix(c, vec3(0.992, 0.800, 0.180), mf_aastep(0.57, yv));
        c = mix(c, vec3(0.992, 0.965, 0.560), mf_aastep(1.08, yv));
        // las llamas que suben: el filo naranja y el centro pálido, como cualquier llama
        float fl = smoothstep(0.15, 0.55, up);
        c = mix(c, vec3(0.992, 0.965, 0.560), fl * mf_aastep(1.5, vp));
        c = mix(c, vec3(0.930, 0.400, 0.110), fl * (1.0 - mf_aastep(0.24, vp)));
        bCore = 0.0;
    }
    if (k.w > 0.5) {
        // las manchas negras del Hiken: en lo más hondo de la masa, con el filo rojo oscuro y el borde irregular
        vec3 pb = p0 / (L0 * 3.1) + so.zxy * 1.7 + vec3(tm * 0.5, 0.0, 0.0);
        float bn = mf_gnoise(pb) + 0.55 * mf_gnoise(pb * 2.7 + 5.1) + 0.30 * abs(mf_gnoise(pb * 5.3 + 1.7));
        float bv = vp - k.y - 0.15 + 1.0 * bn;
        c = mix(c, vec3(0.50, 0.08, 0.09), mf_aastep(-0.10, bv));
        c = mix(c, cCore, mf_aastep(0.0, bv));
    } else {
        c = mix(c, cCore, bCore);
    }
    // el destello del golpe: un dibujo casi blanco
    c = mix(c, vec3(1.0, 0.98, 0.90), flash * 0.75);
    // lo que cruza el hueco de la cámara se desvanece
    float nfk = (nearFade > 0.0) ? smoothstep(0.35 * nearFade, nearFade + 0.15, t0) : 1.0;
    float a = sil * nfk * smoothstep(0.0, 0.12, intensity);
    vec4 outc = vec4(c * a + cGlow * gl * (1.0 - a), a + gl * 0.75 * (1.0 - a));
    // la cinta que pasa por delante del cuerpo se pinta encima; la que pasa por detrás asoma por el borde
    float front = step(tRib, t0);
    float ra = ribA * mix(1.0 - a, 1.0, front);
    outc = outc * (1.0 - ra) + vec4(cRim * ra, ra);
    return outc;
}

vec3 mfUnproject(vec2 ndc, float z) {
    vec4 p = InvViewProj * vec4(ndc, z, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec2 uv = gl_FragCoord.xy / ViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 ro = mfUnproject(ndc, -1.0);
    vec3 rd = normalize(mfUnproject(ndc, 1.0) - ro);
    // lo que abarca un píxel (radianes): el detalle más fino que se puede dibujar sin muaré
    vec2 ndc1 = ndc + vec2(2.0 / ViewSize.x, 0.0);
    vec3 ro1 = mfUnproject(ndc1, -1.0);
    float pixAng = length(normalize(mfUnproject(ndc1, 1.0) - ro1) - rd);

    // hasta dónde llega la mirada antes de chocar con el mundo
    float depth = texture(Sampler0, uv).r;
    float tScene = 1.0e9;
    if (depth < 1.0) {
        vec3 ps = mfUnproject(ndc, depth * 2.0 - 1.0);
        tScene = dot(ps - ro, rd);
    }

    // el rayo en coordenadas de la forma
    vec3 o = ro - VolCenter;
    vec3 lro = vec3(dot(o, VolX), dot(o, VolY), dot(o, VolZ));
    vec3 lrd = vec3(dot(rd, VolX), dot(rd, VolY), dot(rd, VolZ));

    // la esfera que envuelve la forma (radio = VolMax.x)
    float B = VolMax.x;
    float b = dot(lro, lrd);
    float c = dot(lro, lro) - B * B;
    float h = b * b - c;
    if (VolInfo.w < -0.5) {
        // diagnóstico de las pruebas (VolInfo.w < 0): lo que lee y calcula el shader en este píxel
        float hs = sqrt(max(h, 0.0));
        fragColor = vec4(depth, min(tScene, 1.0e6), max(-b - hs, 0.2), (h <= 0.0) ? -1.0 : min(-b + hs, tScene));
        return;
    }
    if (h <= 0.0) discard;
    h = sqrt(h);
    float tn = max(-b - h, 0.2);
    float tf = min(-b + h, tScene);
    if (tf <= tn) discard;

    // con la cámara dentro del fuego (no solo cerca), lo que está pegado a ella se aclara: se ve el fuego
    // alrededor, no una pared naranja (y lo que está lejos, como el puño al final de su estela, se ve entero)
    float dc; float Lc; float uc; vec3 nbc; vec3 nqc; float ampc;
    mf_shape(lro, VolInfo.x, VolPrm, VolTime, dc, Lc, uc, nbc, nqc, ampc);
    float inside = 1.0 - smoothstep(-0.25 * Lc, Lc * 0.8 + ampc, dc);
    // dentro de una bola grande (la explosión de Entei) el hueco crece con ella: el fuego se ve alrededor, a unos
    // bloques, y no como una pared amarilla pegada a la cara
    float ballR = (VolInfo.x < 0.5) ? max(VolPrm.x, 0.05) : 0.0;
    float nearF = max(VolInfo.w, inside * max(2.6, 0.7 * ballR));
    // y desde dentro se ve a través (bruma de fuego): se sigue viendo el mundo, no una pantalla amarilla
    float thin = mix(1.0, 0.35, inside * smoothstep(3.0, 7.0, ballR));
    // el remolino de Hibashira con la cámara en su pared o justo fuera (la tercera persona, a 4 bloques de Ace): la
    // pared que queda entre la cámara y el hueco se aclara y se ve a Ace dentro, con el fondo del remolino detrás.
    // Desde el hueco (primera persona) la pared se ve entera.
    if (VolInfo.x > 1.5 && VolInfo.x < 2.5) {
        float Rc = max(VolPrm.x, 0.1);
        float rc = length(lro.xz);
        float wc = (1.0 - smoothstep(1.2 * Rc + 0.5, 1.2 * Rc + 2.5, rc))
                 * step(-1.0, lro.y) * step(lro.y, max(VolPrm.y, 0.5) + 1.0);
        nearF = max(nearF, wc * max(rc - 0.21 * Rc, 0.0) * 1.6);
    }

    vec4 col = (VolStyle.x > 0.5)
        ? mfRenderToon(lro, lrd, tn, tf, VolInfo.x, VolPrm, VolTime, VolInfo.y, VolInfo.z, nearF, pixAng)
        : mfRender(lro, lrd, tn, tf, VolInfo.x, VolPrm, VolTime, VolInfo.y, VolInfo.z, nearF, thin, pixAng);
    if (col.a < 0.002 && max(col.r, max(col.g, col.b)) < 0.002) discard;
    fragColor = col;
}
