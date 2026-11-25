#version 150

// El fuego 3D de la Mera Mera no Mi: la caja que envuelve cada volumen (coordenadas relativas a la cámara).
in vec3 Position;

uniform mat4 ViewProj;

void main() {
    gl_Position = ViewProj * vec4(Position, 1.0);
}
