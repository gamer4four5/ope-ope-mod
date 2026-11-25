#version 150

// La luz del fuego de Ace sobre el mundo: el suelo, las paredes y la gente que están cerca del fuego se ven
// iluminados de naranja (mucho de noche y en cuevas, poco a pleno sol), y la luz tiembla como la de una hoguera.
// No toca el mundo: se calcula en pantalla con la profundidad, y la mezcla (DST_COLOR, SRC_COLOR) multiplica lo que
// ya hay: color = mundo × 2·salida. Así cada cosa conserva su color y se enciende, y además la luz puede calentarlo
// (quitarle azul): lo muy claro (nieve, arena) se tiñe de naranja en vez de quemarse a blanco.
// La luz es un segmento (de A a B) o un aro (centro A, eje LightAxis, radio LightInfo.w, alto LightB.x).

uniform sampler2D Sampler0;   // la profundidad del mundo
uniform mat4 InvViewProj;     // pantalla → mundo (relativo a la cámara)
uniform vec2 ViewSize;
uniform float VolTime;
uniform vec3 LightA;
uniform vec3 LightB;
uniform vec3 LightAxis;
uniform vec4 LightInfo;       // alcance (bloques), fuerza, semilla, radio del aro (0 = segmento)
uniform vec3 LightColor;

out vec4 fragColor;

vec3 mlPos(vec2 uv) {
    float z = texture(Sampler0, uv).r;
    vec4 p = InvViewProj * vec4(uv * 2.0 - 1.0, z * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec2 px = 1.0 / ViewSize;
    vec2 uv = gl_FragCoord.xy * px;
    float z = texture(Sampler0, uv).r;
    if (z >= 1.0) discard;                       // el cielo no se ilumina
    vec3 P = mlPos(uv);

    // el punto de la luz más cercano a esta superficie
    vec3 Q;
    if (LightInfo.w > 0.0) {
        vec3 rel = P - LightA;
        float h = dot(rel, LightAxis);
        vec3 pl = rel - LightAxis * h;
        float l = length(pl);
        vec3 radial = (l > 1e-3) ? pl / l : normalize(cross(LightAxis, vec3(0.31, 0.0, 0.95)));
        Q = LightA + radial * LightInfo.w + LightAxis * clamp(h, 0.0, LightB.x);
    } else {
        vec3 ab = LightB - LightA;
        float hh = clamp(dot(P - LightA, ab) / max(dot(ab, ab), 1e-4), 0.0, 1.0);
        Q = LightA + ab * hh;
    }
    vec3 Ld = Q - P;
    float dist = length(Ld);
    float R = LightInfo.x;
    if (dist >= R) discard;

    // hacia dónde mira la superficie: con los vecinos de cada lado, el más parecido (sin bordes falsos)
    vec3 Pr = mlPos(uv + vec2(px.x, 0.0));
    vec3 Pl = mlPos(uv - vec2(px.x, 0.0));
    vec3 Pu = mlPos(uv + vec2(0.0, px.y));
    vec3 Pd = mlPos(uv - vec2(0.0, px.y));
    vec3 dx = (dot(Pr - P, Pr - P) < dot(P - Pl, P - Pl)) ? Pr - P : P - Pl;
    vec3 dy = (dot(Pu - P, Pu - P) < dot(P - Pd, P - Pd)) ? Pu - P : P - Pd;
    vec3 N = cross(dx, dy);
    float nl = length(N);
    N = (nl > 1e-8) ? N / nl : -normalize(P);
    if (dot(N, P) > 0.0) N = -N;                 // hacia la cámara (la cámara está en el origen)

    vec3 l = Ld / max(dist, 1e-4);
    float ndl = clamp(dot(N, l), 0.0, 1.0);
    // pegado al fuego todo brilla un poco, aunque mire hacia otro lado
    float lam = max(ndl, 0.3 * (1.0 - smoothstep(0.2, 1.6, dist)));
    float x = dist / R;
    float fall = (1.0 - x * x) * (1.0 - x * x) / (1.0 + 4.0 * x * x);
    float s = LightInfo.z;
    float flick = 0.84 + 0.09 * sin(VolTime * 17.0 + s) + 0.05 * sin(VolTime * 29.0 + s * 1.7) + 0.02 * sin(VolTime * 53.0 + s * 3.1);
    float k = LightInfo.y * fall * lam * flick;
    float warm = k / (1.0 + k);
    vec3 factor = (1.0 + LightColor * k) * (1.0 - 0.6 * warm * vec3(0.0, 0.4, 1.0));
    fragColor = vec4(min(0.5 * factor, vec3(1.0)), 0.0);
}
