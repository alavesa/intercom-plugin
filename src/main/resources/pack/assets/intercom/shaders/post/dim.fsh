#version 330
#extension GL_ARB_separate_shader_objects : require

// =====================================================================================================================
// Intercom post effect (intercom:dim_1 ... dim_N): dims the world a little, darkens the corners and takes some
// colour out while an announcement banner is on screen. One shader, N post_effect JSONs that differ only in
// Amount (the plugin writes them from config.yml post-effect.*): the server walks through the levels while the
// banner slides in and out, because a post effect has no per-player parameter it could animate.
// The HUD (and the banner) is drawn AFTER post effects, so the banner itself stays at full brightness.
// =====================================================================================================================

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform IntercomDim {
    float Amount;        // 0..1: how far this level is faded in
    float Dim;           // darkening of the whole picture at Amount 1
    float Vignette;      // extra darkening in the corners at Amount 1
    float Desaturate;    // 0..1 colour removed at Amount 1
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(luma), Desaturate * Amount);
    // 0 in the middle, 1 in the corners
    float edge = smoothstep(0.25, 0.75, length(texCoord - 0.5));
    col *= 1.0 - Amount * (Dim + Vignette * edge);
    fragColor = vec4(col, 1.0);
}
