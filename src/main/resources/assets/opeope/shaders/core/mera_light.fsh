#version 150

// La luz del fuego de Ace sobre el mundo: el suelo, las paredes y la gente que están cerca del fuego se ven
// iluminados de naranja (mucho de noche y en cuevas, poco a pleno sol), y la luz tiembla como la de una hoguera.
// No toca el mundo: se calcula en pantalla con la profundidad, y la mezcla (DST_COLOR, SRC_COLOR) multiplica lo que
// ya hay: color = mundo × 2·salida. Así cada cosa conserva su color y se enciende con el tono del fuego.
// Todas las luces se cuentan juntas en un solo dibujo: varios fuegos cerca suman luz como luces de verdad (sin
// multiplicarse entre ellos, que pintaba el suelo de amarillo). Un dibujo aclara como mucho ×2; con mucha luz
// (de noche, en una cueva) se repite con LightPass 1 y 2, y cada vez pone el ×2 siguiente (hasta ×8). Al final
// (LightPass 3, mezcla ONE, ONE) se suma un resplandor naranja que de noche tiñe lo oscuro junto al fuego.
// Lo normal es LightPass 4: con una copia de la imagen (Sampler1) todo se calcula en un dibujo y cada color sube hacia
// el blanco sin pasarse nunca; multiplicando, lo que ya era claro se recortaba (la gente junto al fuego salía blanca y
// quedaba un círculo quemado con el borde marcado). Los pases 0 a 3 quedan por si no se puede copiar la imagen.
// Cada luz es un segmento (de A a B) o un aro (centro A, eje, radio, alto).

uniform sampler2D Sampler0;   // la profundidad del mundo
uniform sampler2D Sampler1;   // (LightPass 4) la imagen ya dibujada, copiada
uniform mat4 InvViewProj;     // pantalla → mundo (relativo a la cámara)
uniform vec2 ViewSize;
uniform float VolTime;
uniform vec3 LightColor;
uniform float LightPass;      // 0, 1, 2: qué tramo de ×2 pone este dibujo; 3: el resplandor que se suma; 4: todo

// las luces: MeraVolumes las pasa directamente con OpenGL (los arrays no van en el .json)
uniform int LightCount;
uniform vec4 LightsA[24];     // punto A (relativo a la cámara) y alcance en bloques
uniform vec4 LightsB[24];     // punto B (en un aro, su alto en x) y fuerza
uniform vec4 LightsX[24];     // eje del aro y su radio (0 = segmento)
uniform float LightsSeed[24];

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

    // la luz de todos los fuegos: suman en cuadratura (dos fuegos iguales dan ~1,4 veces uno, no el doble)
    float E2 = 0.0;
    for (int i = 0; i < 24; i++) {
        if (i >= LightCount) break;
        vec4 A = LightsA[i];
        vec4 B = LightsB[i];
        vec4 X = LightsX[i];
        // el punto de la luz más cercano a esta superficie
        vec3 Q;
        if (X.w > 0.0) {
            vec3 rel = P - A.xyz;
            float h = dot(rel, X.xyz);
            vec3 pl = rel - X.xyz * h;
            float l = length(pl);
            vec3 radial = (l > 1e-3) ? pl / l : normalize(cross(X.xyz, vec3(0.31, 0.0, 0.95)));
            Q = A.xyz + radial * X.w + X.xyz * clamp(h, 0.0, B.x);
        } else {
            vec3 ab = B.xyz - A.xyz;
            float hh = clamp(dot(P - A.xyz, ab) / max(dot(ab, ab), 1e-4), 0.0, 1.0);
            Q = A.xyz + ab * hh;
        }
        vec3 Ld = Q - P;
        float dist = length(Ld);
        float R = A.w;
        if (dist >= R) continue;
        vec3 l = Ld / max(dist, 1e-4);
        float ndl = clamp(dot(N, l), 0.0, 1.0);
        // como la luz de los bloques de Minecraft: también llega (menos) a lo que la mira de lado o de espaldas
        float lam = 0.35 + 0.65 * ndl;
        float x = dist / R;
        float fall = (1.0 - x * x) * (1.0 - x * x) / (1.0 + 4.0 * x * x);
        float s = LightsSeed[i];
        float flick = 0.84 + 0.09 * sin(VolTime * 17.0 + s) + 0.05 * sin(VolTime * 29.0 + s * 1.7) + 0.02 * sin(VolTime * 53.0 + s * 3.1);
        float k = B.w * fall * lam * flick;
        E2 += k * k;
    }
    // la luz no crece sin fin: mucha luz aclara cada vez menos y tira hacia el blanco, como una foto muy iluminada
    // (así un montón de fuegos juntos no pintan el suelo de amarillo chillón)
    float E = sqrt(E2);
    E = E / (1.0 + 0.12 * E);
    if (LightPass > 3.5) {
        // todo en un dibujo, con lo que ya hay debajo: en lo oscuro es como multiplicar por 1 + luz, y lo claro se
        // acerca al blanco poco a poco (1 − (1 − c)^(1 + luz)): sin recortes ni manchas quemadas con borde
        vec4 base = texture(Sampler1, uv);
        vec3 c = clamp(base.rgb, 0.0, 1.0);
        // el tono se queda naranja aunque haya mucha luz (si se acerca al blanco, la gente junto al fuego sale blanca)
        vec3 tint4 = mix(LightColor, vec3(1.0), clamp(0.07 * E, 0.0, 0.30));
        // lo que ya está claro (al sol, o alumbrado por el shaderpack) gana menos: ahí la luz del fuego pesa poco
        float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float adapt = 1.0 - 0.75 * smoothstep(0.05, 0.70, lum);
        vec3 lit = 1.0 - pow(max(1.0 - c, vec3(1.0e-6)), 1.0 + tint4 * (1.3 * E * adapt));
        // y de noche el resplandor naranja que tiñe lo oscuro junto al fuego, sumado sin pasarse
        float g4 = 0.40 * (1.0 - exp(-0.12 * E));
        lit += (1.0 - lit) * clamp(LightColor * g4, 0.0, 1.0);
        if (max(lit.r - c.r, max(lit.g - c.g, lit.b - c.b)) < 0.002) discard;
        fragColor = vec4(lit, base.a);
        return;
    }
    if (LightPass > 2.5) {
        // el último dibujo suma (ONE, ONE) un resplandor naranja: de noche lo oscuro junto al fuego se ve naranja,
        // no solo más claro (de día casi no se nota)
        float g = 0.40 * (1.0 - exp(-0.12 * E));
        if (g < 0.002) discard;
        fragColor = vec4(LightColor * g, 0.0);
        return;
    }
    // cuánto se aclara cada color: el mundo × (1 + luz del fuego / luz que ya había)
    vec3 tint = mix(LightColor, vec3(1.0), clamp(0.10 * E, 0.0, 0.45));
    vec3 factor = 1.0 + tint * E;
    // lo que pone este dibujo: el tramo de ×2 que le toca
    vec3 f = clamp(factor / exp2(LightPass), vec3(1.0), vec3(2.0));
    if (max(f.r, max(f.g, f.b)) < 1.003) discard;
    fragColor = vec4(0.5 * f, 0.0);
}
