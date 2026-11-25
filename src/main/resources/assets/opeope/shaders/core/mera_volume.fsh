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
//                w humo claro y fino 0..1 (los golpes pequeños: una bocanada gris)
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
float mf_lip;
float mf_dFar;
// la columna de Hibashira vista desde el hueco (primera persona): el ruido de sus rendijas (mf_str) y cuánto se mete
// cada punto en su banda de fuego, en grosores de pared (mf_core): el centro de cada lengua, más caliente
float mf_str;
float mf_core;

void mf_shape(vec3 p, float shape, vec4 prm, float time,
              out float d, out float L, out float u, out vec3 nb, out vec3 nq, out float amp)
{
    mf_lip = 1.0;
    mf_dFar = -1e9;
    mf_str = 0.0;
    mf_core = 0.0;
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
    }
    else if (shape < 1.5) {
        // ---------------- 1: PUÑO (Hiken) ----------------
        float S = max(prm.x, 0.2);
        float tail = max(prm.y, 0.0);
        float df = mf_sdFist(p / S) * S;
        float z0 = -0.45 * S;
        float v = clamp((z0 - p.z) / max(tail, 0.01), 0.0, 1.0);
        float rr = mix(0.34 * S, 0.06 * S, pow(v, 0.7));
        float dt = max(length(p.xy - vec2(0.0, -0.02 * S)) - rr, p.z - z0 - 0.1 * S);
        dt = max(dt, z0 - tail - p.z);
        d = mf_smin(df, dt, 0.25 * S);
        L = 0.20 * S;
        u = clamp((-0.30 * S - p.z) / max(tail + 0.2 * S, 0.01), 0.0, 1.0) * 0.95;
        nq = vec3(p.x / (L * 1.3), p.y / (L * 1.3) - time * 0.6, p.z / (L * 2.6) + time * 3.2);
        nb = vec3(p.x / (L * 2.1), p.y / (L * 2.1) - time * 0.3, p.z / (L * 2.4) + time * 1.6);
        float body = smoothstep(-0.1 * S, -0.6 * S, p.z);
        amp = L * mix(0.16, 0.50 + 0.60 * v, body);
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
        d = d0 + th * (1.35 * band + 0.55 * band2 + 0.45 + 0.7 * zS) * sy;
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
        u = p.y / H;
        // en la cortina, el ruido estirado hacia arriba: vetas y lenguas que suben, no bolas redondas
        nq = vec3(p.x / (L * mix(0.8, 0.7, cur)), p.y / (L * mix(2.0, 3.0, cur)) - time * mix(2.6, 3.2, cur), p.z / (L * mix(0.8, 0.7, cur)));
        nb = vec3(p.x / (L * mix(1.5, 1.1, cur)), p.y / (L * mix(1.5, 3.6, cur)) - time * mix(1.2, 1.9, cur), p.z / (L * mix(1.5, 1.1, cur)));
        // y abajo casi sin huecos: la masa se abre en lenguas sobre todo en la mitad de arriba
        amp = mix(L * mix(0.55, 0.45, cur), H * mix(0.45, 0.34, cur), mix(v, v * v, cur));
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
        nq = vec3(q.x / ns, q.y / ns - time * 1.2, q.z / (ns * 2.4) - time * 4.0);
        nb = vec3(q.x / (ns * 1.7), q.y / (ns * 1.7) - time * 0.5, q.z / (ns * 2.2) - time * 2.4);
        amp = L * (0.40 + 0.65 * v);
    }
    else if (shape < 6.5) {
        // ---------------- 6: CRUZ (Jujika) ----------------
        float A  = max(prm.x, 0.3);
        float th = (prm.y > 0.01) ? prm.y : 0.3;
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
    float lsB = isBall * clamp(prm.w, 0.0, 1.0);
    // la columna de Hibashira vista desde su hueco (primera persona), y la llama que sale del suelo
    float selfPil = (shape > 1.5 && shape < 2.5) ? clamp(prm.z, 0.0, 1.0) : 0.0;
    float flo = (shape > 7.5) ? clamp(prm.z, 0.0, 1.0) : 0.0;
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
    float Lref = 1.0;
    float wsum = 0.0;
    float ssum = 0.0;   // cuánto de lo que se ve es humo
    float tsum = 0.0;
    float t = tNear + jit * min(0.25, B * 0.03);   // también el halo, sin anillos
    bool first = true;

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
        if (dEnv > 0.0) {
            // espacio vacío: saltar hasta la envoltura (el halo, según lo cerca que pasa; no pegado a la cámara)
            if (t > 1.2 && d < minD) { minD = d; Lref = L; }
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
            dens *= gf * nfk;
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
            // bolsas más calientes; los pliegues entre ellas, más oscuros (hollín)
            float crease = (1.0 - smoothstep(0.03, 0.24, tb)) * (1.0 - w) * massy;
            temp += 0.45 * (tb - 0.35) * (1.0 - w);
            // la columna: vetas calientes que suben y surcos más oscuros entre ellas (no una pared lisa)
            float isPil = (shape > 1.5 && shape < 2.5) ? 1.0 : 0.0;
            temp += isPil * (0.55 * (tb - 0.35) - 0.12 * bhi);
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
            float sigma = dens * intensity * 2.8 / L * thin;
            float aE = 1.0 - exp(-sigma * stepIn);
            // la masa caliente tapa lo de detrás; la llama fina y fría sobre todo brilla (transparente)
            float aA = aE * mix(mix(0.30, 0.70, massy * (1.0 - w)), 1.0, smoothstep(0.12, 0.50, temp));
            // color: el tono vivo del fuego con un poco de cuerpo negro; brillo según el calor
            float t2 = temp * temp;
            vec3 bbc = mf_blackbody(temp);
            vec3 hueC = mix(mf_ramp(temp), bbc / max(bbc.r, 1e-3), 0.25);
            vec3 emit = hueC * (0.6 + 2.0 * t2 + 1.4 * t2 * t2 * t2);
            emit *= 1.0 + 1.5 * flash;
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
            col += T * emit * aE;
            float vis = T * aE * (1.0 - smoke);
            wsum += vis;
            ssum += T * aE * smoke;
            tsum += vis * temp;
            T *= 1.0 - aA;
        }
        t += stepIn;
    }

    // halo alrededor del fuego (como bloom), según lo cerca que pasó el rayo
    float look_w = mf_look(shape, vec3(0.0), prm).w;
    // (la nube de humo que queda después ya no brilla)
    float glow = exp(-max(minD, 0.0) / (Lref * 0.9)) * edgeFade * intensity * look_w * (1.0 - 0.7 * cool)
               * (1.0 - smoothstep(0.85, 1.0, cool));
    vec3 glowCol = vec3(1.0, 0.42, 0.10);
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

    vec4 col = mfRender(lro, lrd, tn, tf, VolInfo.x, VolPrm, VolTime, VolInfo.y, VolInfo.z, nearF, thin, pixAng);
    if (col.a < 0.002 && max(col.r, max(col.g, col.b)) < 0.002) discard;
    fragColor = col;
}
