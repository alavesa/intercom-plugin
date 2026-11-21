#ifndef INTERCOM_MARKER_GLSL
#define INTERCOM_MARKER_GLSL

// =====================================================================================================================
// Intercom: the marker that tells banner glyphs apart from every other piece of text, and what it carries.
// Shared by the vertex and the fragment hook. Java side: banner/BannerText.java (keep the two in sync).
//
// Every character of banner text reaches the shader twice, as Minecraft draws text: the glyph and its shadow.
// A text colour is three bytes; a SHADOW colour is four (it has its own alpha). So the data rides in the shadow:
//
//   the glyph itself   colour exactly (249, 28, 10)  ->  thrown away (moved off screen)
//   its shadow         red 250, alpha 0xE0-0xEF      ->  this is the copy that is drawn, where the data says
//
//   ALPHA  bits 7-4  0xE (part of the marker)
//          bits 3-0  colour: index into the 16 Minecraft chat colours (&0 - &f)
//   GREEN  bits 7-6  phase   0 = HOLD   banner fully shown
//                            1 = ENTER  sliding in,  bits 5-0 = game tick (mod 64) at which the slide started
//                            2 = EXIT   sliding out, bits 5-0 = game tick (mod 64) at which the slide started
//                            3 = STEP   server-driven: bits 5-0 = how much of the banner is shown, 0 (none) - 63 (all)
//          bits 5-0  time    see phase
//   BLUE   bit  7    1 = the background box:  bits 6-0 = banner height / 2
//                    0 = text:                bits 6-1 = y of the text row inside the banner / 2
//                                             bit  0   = large (the row is drawn at double size)
//
// Bold, italic, underline and strike-through are not in here: the client draws them itself (bold = the glyph
// twice, italic = a slanted quad, lines = extra quads), and they come through the same path.
//
// Players cannot produce this: colours are chosen by the server, never by typed text, and the plugin sends banner
// text in its own font with every private-use character removed. Ordinary shadows are dark (a quarter of the text
// colour) and ordinary text is opaque, so "red 250 with alpha 0xE?" does not occur by itself.
// Other plugins' markers on this server: SuperbVehicles red 253, IdCards red 252, Radio #FEFBxx/#FEFCxx,
// SCP079 #FB79xx, CCTV #0CC7xx.
// =====================================================================================================================

struct IntercomMarker {
    bool valid;      // a banner shadow glyph: draw it as the data says
    bool hidden;     // a banner glyph proper: do not draw
    int phase;
    int time;
    bool box;
    float y;         // text: top of the row inside the banner; box: unused
    float height;    // box: banner height; text: unused
    bool large;
    int color;
};

IntercomMarker intercom_decode(vec4 rgba) {
    ivec4 c = ivec4(round(rgba * 255.0));
    IntercomMarker m;
    m.hidden = c.r == 249 && c.g == 28 && c.b == 10;
    m.valid = c.r == 250 && (c.a & 240) == 224;
    m.phase = c.g >> 6;
    m.time = c.g & 63;
    m.box = (c.b & 128) != 0;
    m.height = float((c.b & 127) * 2);
    m.y = float(((c.b >> 1) & 63) * 2);
    m.large = !m.box && (c.b & 1) != 0;
    m.color = c.a & 15;
    return m;
}

#endif
