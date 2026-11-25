#version 150

// Como position_tex_color de Minecraft, pero sin su recorte: Minecraft tira todo lo que tenga menos de 0,1 de alfa,
// y en lo suave y tenue (resplandores, brasas, bocanadas de humo) ese corte dejaba un disco con el borde duro: el
// círculo claro alrededor de las balas de Higan y en la hierba, y las "gotas" claras dentro del humo de Entei.
// Aquí solo se tira lo que de verdad no se ve, y el borde se desvanece hasta cero.

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.002) discard;
    fragColor = color * ColorModulator;
}
