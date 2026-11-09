#version 330

// A full-screen triangle from gl_VertexID alone: no vertex buffer, no transform uniforms. The V1 layer is drawn
// straight into Minecraft's main target, so it has to cover it exactly, whatever the GUI scale.
out vec2 texCoord0;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * vec2(2.0, 2.0) + vec2(-1.0, -1.0), 0.0, 1.0);
    // clip y = +1 is the top of the target, and v = 1 is the top row of the frame as it was uploaded
    texCoord0 = uv;
}
