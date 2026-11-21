// Look and timing of the intercom banner. The plugin REWRITES this file from config.yml (banner.*, timing.*) every
// time it builds the pack; the values here are the defaults (used by the Gradle `packZip` task as they are).
const float INTERCOM_WIDTH = 280.0;                  // banner width, GUI pixels (clamped to the screen width - 8)
const float INTERCOM_TOP = 0.0;                      // gap between the top of the screen and the banner, GUI pixels
const float INTERCOM_BG_ALPHA = 0.88;                // opacity of the banner background
const vec3  INTERCOM_TEXT_COLOR = vec3(1.0, 1.0, 1.0);
const vec3  INTERCOM_SENDER_COLOR = vec3(0.60, 0.65, 0.70);
const float INTERCOM_ENTER_TICKS = 10.0;             // slide-in time (client-clock driver), game ticks
const float INTERCOM_EXIT_TICKS = 10.0;              // slide-out time (client-clock driver), game ticks
