#version 330

// The same composition as v1_composite, without the GUI's transform uniforms: drawn into Minecraft's world target
// before the hand is, so V1's arm and gun stay behind Minecraft's own.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
#ifdef MASK_RGBA
    vec4 m = texture(Sampler1, texCoord0);
    float glow = max(m.r, max(m.g, m.b));
    if (m.a == 0.0 && glow < 0.004) {
        discard;
    }
    vec3 c = texture(Sampler0, texCoord0).rgb;
    if (max(c.r, max(c.g, c.b)) < 0.01 && glow > 0.03) {
        c = m.rgb;
    }
    fragColor = vec4(c, m.a);
#else
    float a = texture(Sampler1, texCoord0).r;
    if (a == 0.0) {
        discard;
    }
    fragColor = vec4(texture(Sampler0, texCoord0).rgb, a);
#endif
}
