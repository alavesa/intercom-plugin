#ifndef INTERCOM_MARKER_GLSL
#define INTERCOM_MARKER_GLSL

// =====================================================================================================================
// Intercom: the marker that tells banner glyphs apart from every other piece of text, and what it carries.
// Shared by the vertex and the fragment hook. Java side: banner/BannerText.java (keep the two in sync).
//
// A banner glyph is GUI text whose colour has RED = 250. Green and blue are 16 bits of data:
//
//   GREEN  bits 7-6  phase   0 = HOLD   banner fully shown
//                            1 = ENTER  sliding in,  bits 5-0 = game tick (mod 64) at which the slide started
//                            2 = EXIT   sliding out, bits 5-0 = game tick (mod 64) at which the slide started
//                            3 = STEP   server-driven: bits 5-0 = how much of the banner is shown, 0 (none) - 63 (all)
//          bits 5-0  time    see phase
//
//   BLUE   bits 2-0  kind    0-4 = text row, counted from the top; 7 = the background box; 5 and 6 are never sent
//          bit  3    sender  this row is the sender line (other colour, a little gap above it)
//          bits 6-4  rows    number of rows in the banner - 1 (message lines + sender line; at most 5 rows)
//          bit  7    signed  the banner ends with a sender line (every glyph carries it: it changes the box height)
//
// Players cannot produce this: a chat/sign/book colour is chosen by the server, never by typed text, and the
// plugin sends banner text as plain text in its own font with every private-use character removed.
// Other plugins' markers on this server: SuperbVehicles red 253, IdCards red 252, Radio #FEFBxx/#FEFCxx,
// SCP079 #FB79xx, CCTV #0CC7xx - none has red 250.
// =====================================================================================================================

const int INTERCOM_RED = 250;
const int INTERCOM_KIND_BOX = 7;
const int INTERCOM_MAX_ROWS = 5;

struct IntercomMarker {
    bool valid;      // false: not banner text - leave the glyph alone
    int phase;
    int time;
    int kind;
    bool senderRow;
    int rows;
    bool hasSender;
};

IntercomMarker intercom_decode(vec3 rgb) {
    ivec3 c = ivec3(round(rgb * 255.0));
    IntercomMarker m;
    m.phase = c.g >> 6;
    m.time = c.g & 63;
    m.kind = c.b & 7;
    m.senderRow = (c.b & 8) != 0;
    m.rows = ((c.b >> 4) & 7) + 1;
    m.hasSender = (c.b & 128) != 0;
    // red alone would also catch somebody else's #FAxxxx text; the combinations the plugin never sends are rejected
    m.valid = c.r == INTERCOM_RED && m.rows <= INTERCOM_MAX_ROWS && (m.kind == INTERCOM_KIND_BOX || m.kind < m.rows);
    return m;
}

#endif
