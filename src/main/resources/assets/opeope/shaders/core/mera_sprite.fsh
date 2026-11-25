#version 150

// Como position_tex_color de Minecraft, pero sin su recorte: Minecraft tira todo lo que tenga menos de 0,1 de alfa,
// y en lo suave y tenue (resplandores, brasas, bocanadas de humo) ese corte dejaba un disco con el borde duro: el
// círculo claro alrededor de las balas de Higan y en la hierba, y las "gotas" claras dentro del humo de Entei.
// Aquí solo se tira lo que de verdad no se ve, y el borde se desvanece hasta cero.
// Además, cerca del suelo o de una pared se desvanece (con la profundidad del mundo): una bocanada de humo que toca
// la hierba no se corta en una raya recta.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;   // la profundidad del mundo (copia de este fotograma)
uniform vec4 ColorModulator;
uniform mat4 InvProjMat;
uniform vec2 ViewSize;
uniform float SoftRange;      // a cuántos bloques del mundo empieza a desvanecerse (0 = sin eso)

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// distancia a la cámara del punto de esta pantalla a una profundidad (0..1)
float spDist(vec2 ndc, float z) {
    vec4 p = InvProjMat * vec4(ndc, z * 2.0 - 1.0, 1.0);
    return length(p.xyz / p.w);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (SoftRange > 0.0) {
        vec2 uv = gl_FragCoord.xy / ViewSize;
        float zs = texture(Sampler1, uv).r;
        if (zs < 1.0) {
            vec2 ndc = uv * 2.0 - 1.0;
            color.a *= clamp((spDist(ndc, zs) - spDist(ndc, gl_FragCoord.z)) / SoftRange, 0.0, 1.0);
        }
    }
    if (color.a < 0.002) discard;
    fragColor = color * ColorModulator;
}
