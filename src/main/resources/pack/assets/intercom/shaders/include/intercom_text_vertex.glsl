#ifndef INTERCOM_TEXT_VERTEX_GLSL
#define INTERCOM_TEXT_VERTEX_GLSL

// =====================================================================================================================
// Intercom: vertex part of the core/text override. Moves the announcement banner into place and slides it.
//
// WHAT ARRIVES HERE
// The banner is the NAME of a boss bar, written in the font intercom:banner. Vanilla draws a boss bar name centred
// at the top of the screen, 9 px above its bar; bars are stacked 19 px apart. The plugin builds the name so that its
// total advance is 0 (banner/BannerText.java): the pen therefore starts exactly at the screen centre, and a glyph's
// distance from the centre is its x inside the banner. Every line is one run of glyphs with its own marker colour.
//
// WHAT THIS DOES
//   x    kept as it is, relative to the screen centre (the plugin already centred every line)
//   y    replaced: banner top + padding + row * line height. The offset of the vertex inside its text line is
//        kept, so glyphs of any height work (the accented sheet is 12 px tall, the ASCII sheet 8)
//   box  the background glyph is stretched over the whole banner (its own position is ignored)
//   slide  everything is shifted up by (1 - shown) * (banner height + top gap); shown comes from the marker
//
// ANIMATION PROGRESS (see intercom_marker.glsl for the bits)
//   ENTER / EXIT  the marker holds the game tick at which the slide started, and GameTime (the client's own copy of
//                 the world clock, with the partial tick) says how far it is: smooth at any frame rate, and the
//                 server sends the name once per phase instead of 20 times a second. GameTime loops every 24000
//                 ticks and the marker has 6 bits, so time is compared modulo 64 ticks (24000 is a multiple of 64,
//                 the loop is seamless). The server ends every phase itself (HOLD / removing the bar), so a client
//                 whose clock is off sees the banner jump instead of slide, never a stuck or missing banner.
//   STEP          the marker holds the shown amount itself; the server sends it every tick (timing.driver:
//                 server-ticks). 20 steps a second, but independent of the client clock.
//
// No varyings are added, so nothing collides with the varying locations other plugins' hooks use.
// =====================================================================================================================

#ifdef IS_GUI
#include <minecraft:globals.glsl>
#include <intercom:intercom_marker.glsl>
#include <intercom:intercom_config.glsl>

const float INTERCOM_LINE_H = 10.0;          // distance between two rows
const float INTERCOM_PAD_Y = 6.0;            // above the first and below the last row
const float INTERCOM_SENDER_GAP = 3.0;       // extra gap above the sender line
const float INTERCOM_NAME_Y = 3.0;           // y of the first boss bar's name (vanilla: bar at 12, name 9 above)
const float INTERCOM_BAR_STEP = 19.0;        // distance between two boss bars (vanilla)
const float INTERCOM_CLOCK_WINDOW = 64.0;    // the marker's time is a game tick modulo this
const float INTERCOM_CLOCK_LATE = 48.0;      // "elapsed" beyond this means the client clock is BEHIND the start tick

// Height of the whole banner in GUI pixels.
float intercom_height(int rows, bool hasSender) {
    return 2.0 * INTERCOM_PAD_Y + float(rows) * INTERCOM_LINE_H - 2.0 + (hasSender ? INTERCOM_SENDER_GAP : 0.0);
}

// How much of the banner is on screen: 0 = fully above the screen, 1 = in place.
float intercom_shown(int phase, int time) {
    if (phase == 0) return 1.0;
    if (phase == 3) return float(time) / 63.0;                       // already eased by the server
    // GameTime = (world game time mod 24000 + partial tick) / 24000
    float elapsed = mod(GameTime * 24000.0 - float(time), INTERCOM_CLOCK_WINDOW);
    // a start tick slightly in the future (network jitter) shows up as "almost a whole window ago": that is 0
    if (elapsed > INTERCOM_CLOCK_LATE) elapsed = 0.0;
    if (phase == 1) {
        float left = 1.0 - clamp(elapsed / INTERCOM_ENTER_TICKS, 0.0, 1.0);
        return 1.0 - left * left * left;                             // ease-out cubic: fast, then settles
    }
    float t = clamp(elapsed / INTERCOM_EXIT_TICKS, 0.0, 1.0);
    return 1.0 - t * t * t;                                          // ease-in cubic: starts slowly, then leaves
}
#endif

// Call at the END of main(): it replaces gl_Position for banner glyphs and must not be overwritten afterwards.
// color: the vertex colour attribute (it is declared after this file is included, so it is passed in).
void intercom_text_vertex(vec4 color) {
#ifdef IS_GUI
    IntercomMarker m = intercom_decode(color.rgb);
    if (!m.valid) return;

    vec2 gui = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);         // GUI size in GUI pixels
    vec2 ndc = gl_Position.xy / gl_Position.w;
    float height = intercom_height(m.rows, m.hasSender);

    vec2 p;                                                             // relative to (screen centre, banner top)
    if (m.kind == INTERCOM_KIND_BOX) {
        // glyph quads are emitted top-left, bottom-left, bottom-right, top-right (26.3 BakedSheetGlyph.render)
        int corner = gl_VertexIndex % 4;
        vec2 k = vec2(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0);
        float width = min(INTERCOM_WIDTH, gui.x - 8.0);
        p = vec2((k.x - 0.5) * width, k.y * height);
    } else {
        // x inside the banner = distance from the screen centre (rounded: the centre is a half pixel on odd widths)
        float x = floor(ndc.x * 0.5 * gui.x + 0.75);
        // y inside the text line: the name's line starts at NAME_Y + n * BAR_STEP for the n-th boss bar on screen;
        // glyph quads reach from 4 px above to 14 px below that, which the modulo keeps apart for any n
        float y = floor((1.0 - ndc.y) * 0.5 * gui.y + 0.5);
        float inLine = mod(y - INTERCOM_NAME_Y + 4.0, INTERCOM_BAR_STEP) - 4.0;
        float rowTop = INTERCOM_PAD_Y + float(m.kind) * INTERCOM_LINE_H + (m.senderRow ? INTERCOM_SENDER_GAP : 0.0);
        p = vec2(x, rowTop + inLine);
    }

    // the slide, snapped to whole SCREEN pixels so glyphs stay crisp while they move
    float slide = -(1.0 - intercom_shown(m.phase, m.time)) * (INTERCOM_TOP + height + 1.0);
    float pixelsPerGui = ScreenSize.y / gui.y;
    if (pixelsPerGui >= 1.0) slide = floor(slide * pixelsPerGui + 0.5) / pixelsPerGui;

    vec4 clip = ProjMat * vec4(floor(gui.x * 0.5) + p.x, INTERCOM_TOP + slide + p.y, 0.0, 1.0);
    gl_Position = vec4(clip.xy * gl_Position.w, gl_Position.z, gl_Position.w);
#endif
}

#endif
