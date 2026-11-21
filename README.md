# Intercom

Announcement / intercom / PA system for Paper **26.3**. `/announce` opens a dialog; what is sent there slides
down from the top of every player's screen as a black banner with the text and the sender's name, with a chime
and a subtle screen dim, then slides back up.

- Plugin: `build/libs/Intercom-1.0.0.jar` (Java 25, Paper API `26.3.build.257-stable`, nothing shaded)
- Resource pack: built by the plugin at start-up and sent to players automatically. A stand-alone copy comes
  from `./gradlew packZip`.

## What was verified, and how

| | |
|---|---|
| Compiles against Paper API 26.3 (build 257, stable), 19 unit tests | yes |
| Shaders compile with glslang for Vulkan SPIR-V, all five variants of `core/text` + the post shader | yes |
| Boots on Paper 26.3 with no errors | yes |
| On a real 26.3 client: banner, wrapping, sender line, accented letters, slide in / out (both drivers), screen dim fading, textured dialog | yes, from screenshots |
| On a real client together with SCP079 1.1.1 + IdCards 1.0.4 + Radio 0.2.4 (merged text shader, Intercom pack on top) | yes |
| On a real client without the pack: chat + action bar fallback, no banner text, no dim | yes |
| **Not verified**: clicking Send / Cancel and typing in the dialog, the sounds, more than one player, LuckPerms itself, the Vulkan backend on a real GPU, how smooth the slide looks | the test client cannot take input or play sound, and its screenshots are too slow to judge motion |

## Install

1. Put `Intercom-1.0.0.jar` in `plugins/` (delete any older Intercom jar) and start the server.
2. Give the permission: `lp group staff permission set intercom.announce true`
3. Join. The pack arrives about 6 seconds after joining. Run `/announce test Hello` (needs `intercom.admin`).

`/announce status` shows the pack hash, how it is delivered, and how many players can see the banner.

### With SCP079, IdCards or Radio on the same server

These plugins also replace the text shader, and Minecraft runs only the top pack's copy. Intercom builds its
shader on top of theirs and must end up as the top pack. They need the version that knows this, or every join
turns into a long series of pack reloads:

- SCP079 **1.1.1**, IdCards **1.0.4**, Radio **0.2.4** (one line each: do not re-send your pack because Intercom's
  loaded after it).

With those, a join was 5 pack reloads on the test client (one per plugin, plus one extra round when SCP079
re-sends its pack after IdCards' loads) and Intercom ends on top.

## Commands and permissions

| Command | Permission | |
|---|---|---|
| `/announce` (`/pa`, `/intercom`) | `intercom.announce` | open the dialog |
| `/announce test <text>` | `intercom.admin` | announce without dialog and cooldown; works from the console |
| `/announce clear` | `intercom.admin` | stop the banner on screen and empty the queue |
| `/announce reload` | `intercom.admin` | re-read config and messages, rebuild the pack |
| `/announce status` | `intercom.admin` | pack / shader / player read-out |
| `/announce open <player>` | `intercom.admin` | put the dialog on a player's screen |

`intercom.bypass.cooldown` skips the cooldown. `intercom.admin` includes the other two. All default to op.
Permissions are plain Bukkit checks (`hasPermission`), so LuckPerms works with no hook. The permission is checked
when the dialog opens **and** when it is submitted; the denial text is `no-permission` in `messages.yml`.

## Configuration

`config.yml` is commented line by line. The parts that matter most:

- `announcement.*`: max characters (120), max lines (3, up to 4), cooldown (30 s), queue size (5), sender line.
- `timing.*`: slide and hold times, and the animation `driver` (below).
- `banner.*`, `post-effect.*`: look. These are **baked into the pack**; `/announce reload` rebuilds it and players
  download it again.
- `fallback.mode`: what players without the pack get (`chat`, `actionbar`, `both`, `none`).
- `resourcepack.*`: delivery.

`messages.yml` holds every player-facing string in MiniMessage. The announcement text and names are always
inserted as plain text.

## Resource pack delivery

`resourcepack.delivery`:

| Mode | |
|---|---|
| `auto` (default) | `same-port`, falling back to `http` |
| `same-port` | served on the Minecraft port itself. Nothing to open in a firewall. Not behind Velocity/Bungee. |
| `http` | built-in web server on `resourcepack.http.port` (8078); players must be able to reach it |
| `url` | you upload `plugins/Intercom/Intercom-pack.zip` and set `resourcepack.url` and `resourcepack.sha1` |
| `none` | you merged `plugins/Intercom/resourcepack/` into your own server pack |

The pack is sent as an **additional** pack (`Player.sendResourcePacks`, not replacing the server's), 120 ticks
after joining. The zip is deterministic: the same files give the same SHA-1, so clients download again only when
something changed. The hash is in `/announce status` and in `plugins/Intercom/Intercom-pack.zip.sha1`.

**Hosting it yourself.** `./gradlew packZip` writes `build/pack/Intercom-pack.zip` and prints its SHA-1 (also in
`Intercom-pack.zip.sha1`). That zip is built from the default config with the vanilla text shader. On a server
with changed `banner.*` settings or other text-shader plugins, upload the zip the plugin wrote to
`plugins/Intercom/` instead. For `server.properties`:

```
resource-pack=https://example.com/Intercom-pack.zip
resource-pack-sha1=<the 40 hex characters>
```

and set `resourcepack.delivery: none`. In that mode the plugin cannot ask about its own pack, so a player
counts as having it once their client reports any server pack as loaded.

**Who gets the banner.** The plugin listens to `PlayerResourcePackStatusEvent`. Only players whose client
reported the pack as loaded are sent the banner. Everyone else gets the fallback and a vanilla chime, never the
raw marker text.

## How it works

### Delivery channel: a boss bar

The banner is the **name of a boss bar**. Boss bars are the one HUD text channel several plugins can use at once
(each bar is its own object), they stay exactly as long as the server says, and the name can be replaced in
place. The action bar and titles are single slots that any other plugin overwrites, and they fade on their own
timers. The pack blanks the blue bar sprites, so only the name is drawn. Side effects: a real blue boss bar from
another plugin would be invisible (no vanilla mob uses blue), and other boss bars shown later sit one row lower
while a banner is up.

### Text shader

`assets/minecraft/shaders/core/text.vsh/.fsh` are the vanilla 26.3 files from the client jar with four lines
added (an include and a call per stage). The logic is in `assets/intercom/shaders/include/`:

- `intercom_marker.glsl`: the encoding, shared by both stages
- `intercom_text_vertex.glsl`: position, box, slide
- `intercom_text_fragment.glsl`: colours
- `intercom_config.glsl`: constants written from `config.yml`

**Marker.** Banner glyphs are GUI text with colour red = 250. Green and blue carry 16 bits:

```
green  bits 7-6  phase: 0 HOLD, 1 ENTER, 2 EXIT, 3 STEP
       bits 5-0  ENTER/EXIT: game tick (mod 64) the slide started; STEP: shown amount 0-63
blue   bits 2-0  0-4 = text row from the top, 7 = background box
       bit  3    this row is the sender line
       bits 6-4  rows in the banner - 1
       bit  7    the banner has a sender line
```

Only the `IS_GUI` variant of the shader contains the code, so signs, name tags, text displays and maps are
compiled without it. Chat, titles and scoreboards go through the code but do not match the marker.
Text with the exact colour `#FAxxxx` from another plugin could match; combinations the plugin never sends are
rejected, which removes most of that space.

**Layout.** The font `intercom:banner` is Minecraft's default bitmap font plus cursor-moving spaces and one
background glyph. `BannerText.java` writes every line starting at minus half its width and ends with the cursor
back at 0, so the client's own centring puts the middle of every line at the screen centre. The shader keeps x,
replaces y with the row, and stretches the background glyph over the banner. Widths come from
`font/default_widths.tsv`, generated from the 26.3 client jar (`tools/gen_widths.py`).

**Text always fits.** The server wraps by measured pixel width (`banner.width - 2 x padding-x`), caps characters
per line, and refuses a message that needs more than `max-lines`. Banner width is limited to 312 px; the
smallest Minecraft GUI is 320 wide.

**Animation.** Two drivers, `timing.driver`:

- `client-clock` (default). The marker carries the game tick at which the slide started; the shader compares it
  with `GameTime`, the client's copy of the world clock including the partial tick. Motion is computed every
  frame and the server sends the name once per phase. `GameTime` loops every 24000 ticks; time is compared
  modulo 64, which divides 24000, so the loop is seamless. The server ends each phase itself, so a client whose
  clock is off (server lag) sees the banner jump instead of slide; it cannot get stuck.
- `server-ticks`. The server sends the shown amount every tick. 20 positions a second, no clock involved.

I chose `client-clock` as default because its failure is cosmetic and its normal case is smoother. Switch if
the slide jumps on your server.

### Post effect

26.3 has server-driven per-player post effects (`/posteffect`, Paper `Player.postEffects()`). The pack has
`intercom:dim_1..6`, one shader with rising strength. A post effect takes no parameters, so the server walks
through the levels while the banner slides. Each switch is one list update, and only Intercom's ids are touched.
Post effects are applied before the HUD, so the banner stays at full brightness. Players without the pack are
not sent the effect.

### Dialog

A Paper Dialog API dialog (`DialogType.confirmation`): multi-line text input with `maxLength`, Send, Cancel.
Send is a custom click action; the server re-checks permission and re-validates the text.

**Textures use font glyphs, not GUI sprite overrides.** Every vanilla button is drawn from the same three
sprites, so overriding them re-skins every menu in the game. Instead:

- Button: the label starts with a texture glyph pulled left over the button face, then the label text.
- Background: one tall glyph at the start of the hint line; it hangs behind the text box.

Limits: the hovered state is vanilla's frame around the texture (one pixel is left free for it). Dialog buttons
have no pressed or disabled state in vanilla. The text box keeps its vanilla look. The dialog clips its content
at the bottom of the text box, so the panel cannot extend below it.

If you want every button in the game re-skinned anyway, set `resourcepack.global-button-skin: true`; the pack
then includes the three sprite overrides from `pack-optional/global-buttons`.

### Input safety

`Sanitizer.java`: legacy `§` codes are removed; control characters and line breaks become spaces; private-use
characters, unassigned code points and zero-width characters are dropped; anything not in the banner font
becomes `?`. MiniMessage tags and `&` codes stay as literal characters, because the text is never parsed. The
marker is a colour, and typed text cannot set a colour. Length and blank checks run on the server; each
announcement is logged to the console and `plugins/Intercom/announcements.log` with name and UUID.

## Replacing textures and sounds

Put replacements in `plugins/Intercom/pack-overrides/` under the same path as in the pack, then
`/announce reload`. The current pack is in `plugins/Intercom/resourcepack/` for reference.

| File (under `assets/intercom/`) | Size | Notes |
|---|---|---|
| `textures/font/banner_bg.png` | any, 8x8 shipped | stretched over the whole banner; flat colours or gradients only. Opacity comes from `banner.background-opacity` |
| `textures/font/button_send.png` | 108 x 18 | drawn inside a 110 x 20 button; no nine-slice, fixed size |
| `textures/font/button_cancel.png` | 108 x 18 | same |
| `textures/font/dialog_panel.png` | 240 x 75 | starts 4 px above the hint text; 75 = 4 + 9 + 4 + 10 + `dialog.input-height` (48). Change the height in `font/ui.json` too if you change that |
| `sounds/chime_open.ogg` | OGG Vorbis | banner starts descending. Event `intercom:chime_open` |
| `sounds/chime_close.ogg` | OGG Vorbis | banner starts retracting. Event `intercom:chime_close` |
| `sounds/send_confirm.ogg` | OGG Vorbis | sender only. Event `intercom:send_confirm` |

Optional global buttons (`assets/minecraft/textures/gui/sprites/widget/`): `button.png`,
`button_highlighted.png`, `button_disabled.png`, each 200 x 20 with a `.png.mcmeta` of
`nine_slice`, width 200, height 20, border 3.

Sounds: 44.1 kHz OGG Vorbis. Mono is the usual advice because only mono sounds are positioned in the world;
these are played on the listener, so stereo works equally. The shipped placeholders are stereo (the local ffmpeg
has no mono Vorbis encoder). The sound ids are `intercom:chime_open` and so on, not `intercom.chime_open`: they
live in the pack's own namespace so they cannot collide with another pack's `sounds.json`.

Regenerate the placeholders with `python3 tools/gen_placeholders.py`.

## Troubleshooting

- **Text does not appear, only chat.** `/announce status`: is the player counted as seeing the banner? If not,
  their client did not load the pack (declined, download failed; the console names the reason). If the text
  shader line says `none`, another plugin's shader could not be merged.
- **Coloured or garbled text at the top instead of a banner.** Another pack with its own `core/text` is above
  Intercom's. Update the plugins listed above, or raise `resourcepack.send-delay-ticks`.
- **All server packs vanish, or the screen goes black after joining.** A core shader in some pack failed to
  compile; the client then drops every server pack (`Caught error loading resourcepacks` in the client log).
  Check edited shader files with `tools/validate_shaders.py` and glslang before shipping them.
- **Banner jumps instead of sliding.** `timing.driver: server-ticks`.
- **`?` in the banner.** The character is not in Minecraft's default bitmap font (emoji, CJK).
- **Lines off-centre.** A pack replaced Minecraft's default font textures with different glyph widths.
- **"Update did nothing".** An older Intercom jar is still in `plugins/`.

## Known limitations

- Sodium, Iris, OptiFine and other mods that replace the renderer may ignore or break custom core shaders.
  Those players see marker-coloured text at the top of the screen. The plugin does not try to detect them; a
  client does not report its mods reliably.
- Not Folia-compatible: everything runs on the Bukkit scheduler and the main thread.
- One announcement at a time for the whole server; players who join mid-announcement do not see it.
- The banner alphabet is the default bitmap font (ASCII, Latin-1, Greek, Cyrillic, some symbols).
- `same-port` delivery uses reflection into the server's netty listener (`pack/SamePortHttp.java`), the only
  non-API code. `http` and `url` avoid it.

## Assumptions and risks

- **Game time source.** `client-clock` assumes the server's `World.getGameTime()` is what the client's
  `GameTime` follows. The 26.3 client bytecode shows the time packet setting it, and the slide ran correctly on
  the test client, but only in one world and without lag.
- **Vulkan.** Shaders pass glslang's Vulkan target; the test client ran the OpenGL backend.
- **Glyph quad order.** The box uses `gl_VertexIndex % 4` to tell a glyph's corners apart, which holds for
  26.3's text batching (same technique as the SCP079 captions).
- **Boss bar layout numbers** (name at y 3, bars 19 px apart) are read from the 26.3 client and could change in a
  later version; the pack format range is 97-97 for that reason.
- **Dialog details** (label not clipped over the button, panel behind the text box) are observed behaviour, not
  documented API.
- **Pack order with other plugins** depends on timing; a player for whom another pack stays on top after three
  re-sends gets the fallback.

## Build

```
./gradlew build      # jar + tests
./gradlew packZip    # stand-alone pack + SHA-1
```

Needs JDK 25 or newer. Project layout:

```
src/main/java/fi/alavesa/intercom/
  IntercomPlugin.java            lifecycle, pack build
  command/AnnounceCommand.java   Brigadier tree
  dialog/ComposeDialog.java      dialog + click handler      dialog/DialogSkin.java   glyph textures
  announce/AnnouncementManager   validation, cooldown, queue, log
  banner/BannerScheduler.java    phases and timing           banner/BannerText.java   marker encoding
  banner/PostDim.java            screen dim levels
  text/Sanitizer, LineWrapper, FontWidths, Spaces
  config/Settings, Messages
  pack/PackAssets, TextShaderMerge, PackBuilder, PackDelivery (pack status), SamePortHttp, PackHttpServer, PackCli
src/main/resources/
  plugin.yml  config.yml  messages.yml  font/default_widths.tsv  shaders/vanilla_text.*
  pack/            the resource pack sources
  pack-optional/   global button skin
tools/             gen_placeholders.py  gen_widths.py  validate_shaders.py
```
