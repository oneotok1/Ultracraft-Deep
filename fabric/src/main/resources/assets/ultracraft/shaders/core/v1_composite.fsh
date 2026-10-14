#version 330

// ULTRAKILL's final image (Sampler0) with the alpha of V1's 3D render target (Sampler1):
// guns, arm, projectiles and effects over Minecraft's world, everything else transparent.
// MASK_RGBA (the default): the mask is ULTRAKILL's whole pre-post-processing target, composited premultiplied.
// Otherwise: a single-channel alpha mask, composited straight.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
#ifdef MASK_RGBA
    // Sampler1 is ULTRAKILL's render target before post-processing, drawn over transparent black: its colour is
    // premultiplied light (additive tracers, muzzle flashes and glows add colour but leave alpha at 0) and its alpha
    // is how much of Minecraft is covered. Composited premultiplied: out = ultrakill + minecraft * (1 - alpha).
    vec4 m = texture(Sampler1, texCoord0);
    float glow = max(m.r, max(m.g, m.b));
    if (m.a == 0.0 && glow < 0.004) {
        discard;
    }
    vec3 c = texture(Sampler0, texCoord0).rgb;
    // where post-processing (vignette, colour compression) crushed something to black that the raw frame still has,
    // show the raw colour: a covered pixel drawn black would be a black blot over Minecraft
    if (max(c.r, max(c.g, c.b)) < 0.01 && glow > 0.03) {
        c = m.rgb;
    }
    fragColor = vec4(c, m.a) * vertexColor * ColorModulator;
#else
    float a = texture(Sampler1, texCoord0).r;
    if (a == 0.0) {
        discard;
    }
    fragColor = vec4(texture(Sampler0, texCoord0).rgb, a) * vertexColor * ColorModulator;
#endif
}
