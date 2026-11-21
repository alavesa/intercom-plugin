#ifndef INTERCOM_TEXT_FRAGMENT_GLSL
#define INTERCOM_TEXT_FRAGMENT_GLSL

// =====================================================================================================================
// Intercom: fragment part of the core/text override. Colours the announcement banner.
//
// Acts only on GUI text carrying the marker (intercom_marker.glsl); everything else gets back exactly the colour
// computed before this call. The marker colour itself is never shown:
//   box   the background texture (textures/font/banner_bg.png, stretched) at INTERCOM_BG_ALPHA
//   text  INTERCOM_TEXT_COLOR, the sender line INTERCOM_SENDER_COLOR; the glyph texture supplies the shape
// =====================================================================================================================

#ifdef IS_GUI
#include <intercom:intercom_marker.glsl>
#include <intercom:intercom_config.glsl>
#endif

vec4 intercom_text_fragment(vec4 color, vec4 texColor, vec4 vertColor, vec4 colorModulator) {
#ifdef IS_GUI
    // the four vertices of a glyph have the same colour, so the interpolated value is still the exact marker
    IntercomMarker m = intercom_decode(vertColor.rgb);
    if (!m.valid) return color;
    if (m.kind == INTERCOM_KIND_BOX) {
        return vec4(texColor.rgb, texColor.a * INTERCOM_BG_ALPHA * vertColor.a * colorModulator.a);
    }
    vec3 rgb = m.senderRow ? INTERCOM_SENDER_COLOR : INTERCOM_TEXT_COLOR;
    return vec4(rgb, texColor.a * vertColor.a) * colorModulator;
#else
    return color;
#endif
}

#endif
