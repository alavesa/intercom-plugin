#ifndef INTERCOM_TEXT_FRAGMENT_GLSL
#define INTERCOM_TEXT_FRAGMENT_GLSL

// =====================================================================================================================
// Intercom: fragment part of the core/text override. Colours the announcement banner.
//
// Acts only on GUI text carrying the marker (intercom_marker.glsl); everything else gets back exactly the colour
// computed before this call. The marker colour itself is never shown:
//   box   the background texture (textures/font/banner_bg.png, stretched) at INTERCOM_BG_ALPHA
//   text  one of the 16 Minecraft chat colours, picked by the marker; the glyph texture supplies the shape
// =====================================================================================================================

#ifdef IS_GUI
#include <intercom:intercom_marker.glsl>
#include <intercom:intercom_config.glsl>

// &0 ... &f
const vec3 INTERCOM_PALETTE[16] = vec3[16](
    vec3(0.000, 0.000, 0.000), vec3(0.000, 0.000, 0.667), vec3(0.000, 0.667, 0.000), vec3(0.000, 0.667, 0.667),
    vec3(0.667, 0.000, 0.000), vec3(0.667, 0.000, 0.667), vec3(1.000, 0.667, 0.000), vec3(0.667, 0.667, 0.667),
    vec3(0.333, 0.333, 0.333), vec3(0.333, 0.333, 1.000), vec3(0.333, 1.000, 0.333), vec3(0.333, 1.000, 1.000),
    vec3(1.000, 0.333, 0.333), vec3(1.000, 0.333, 1.000), vec3(1.000, 1.000, 0.333), vec3(1.000, 1.000, 1.000));
#endif

vec4 intercom_text_fragment(vec4 color, vec4 texColor, vec4 vertColor, vec4 colorModulator) {
#ifdef IS_GUI
    // the four vertices of a glyph have the same colour, so the interpolated value is still the exact marker
    IntercomMarker m = intercom_decode(vertColor);
    if (!m.valid) return color;
    // vertColor.a is part of the marker, not an opacity
    if (m.box) return vec4(texColor.rgb, texColor.a * INTERCOM_BG_ALPHA * colorModulator.a);
    return vec4(INTERCOM_PALETTE[m.color] * colorModulator.rgb, texColor.a * colorModulator.a);
#else
    return color;
#endif
}

#endif
