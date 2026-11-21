package fi.alavesa.intercom;

import fi.alavesa.intercom.bbmodel.ModelException;
import fi.alavesa.intercom.config.Settings;
import fi.alavesa.intercom.microphone.MicrophoneModel;
import fi.alavesa.intercom.pack.Bundle;
import fi.alavesa.intercom.pack.DeterministicZip;
import fi.alavesa.intercom.pack.PackAssets;
import fi.alavesa.intercom.util.Hashing;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static PackAssets.Pack build(String... overrides) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(RESOURCES.resolve("config.yml").toFile());
        for (int i = 0; i < overrides.length; i += 2) yaml.set(overrides[i], overrides[i + 1].equals("true") ? Boolean.TRUE : overrides[i + 1]);
        Map<String, byte[]> bundle = Bundle.fromDirectory(RESOURCES);
        try {
            return PackAssets.collect(bundle, null, null, Settings.read(yaml, Logger.getLogger("test")), MicrophoneModel.load(null).packFiles());
        } catch (ModelException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(PackAssets.Pack pack, String path) {
        return new String(pack.files().get(path), StandardCharsets.UTF_8);
    }

    @Test
    void vanillaShaderGetsTheIntercomLines() throws IOException {
        PackAssets.Pack pack = build();
        assertTrue(pack.bannerShader());
        assertTrue(pack.warnings().isEmpty(), pack.warnings().toString());
        String vertex = text(pack, "assets/minecraft/shaders/core/text.vsh"), fragment = text(pack, "assets/minecraft/shaders/core/text.fsh");
        assertTrue(vertex.contains("#include <intercom:intercom_text_vertex.glsl>"));
        // the call is the last statement of main()
        assertTrue(vertex.replaceAll("\\s+", " ").contains("texCoord0 = UV0; // ===== INTERCOM BEGIN ===== intercom_text_vertex(Color); // ===== INTERCOM END ===== }"));
        assertTrue(fragment.contains("color = intercom_text_fragment(color, texColor, vertexColor, ColorModulator);"));
        assertTrue(fragment.indexOf("intercom_text_fragment(color") < fragment.indexOf("if (color.a < 0.1)"));
    }

    @Test
    void packHasWhatThePluginUses() throws IOException {
        PackAssets.Pack pack = build();
        for (String path : new String[] {
            "pack.mcmeta", "assets/intercom/font/banner.json", "assets/intercom/font/ui.json", "assets/intercom/sounds.json",
            "assets/intercom/sounds/chime_open.ogg", "assets/intercom/sounds/chime_corrupt.ogg", "assets/intercom/items/microphone.json",
            "assets/intercom/models/item/microphone.json", "assets/intercom/textures/item/microphone/microphone.png", "assets/intercom/sounds/chime_close.ogg", "assets/intercom/sounds/send_confirm.ogg",
            "assets/intercom/shaders/post/dim.fsh", "assets/intercom/post_effect/dim_1.json", "assets/intercom/post_effect/dim_6.json",
            "assets/intercom/shaders/include/intercom_config.glsl", "assets/intercom/shaders/include/intercom_marker.glsl",
            "assets/minecraft/textures/gui/sprites/boss_bar/blue_background.png"}) {
            assertTrue(pack.files().containsKey(path), path);
        }
        assertFalse(pack.files().containsKey("assets/intercom/post_effect/dim_7.json"));
        assertFalse(pack.files().keySet().stream().anyMatch(p -> p.contains("sprites/widget/")), "global button skin is opt-in");
        assertTrue(text(pack, "pack.mcmeta").contains("\"pack_format\": 97"));
        assertTrue(text(pack, "assets/intercom/shaders/include/intercom_config.glsl").contains("const float INTERCOM_WIDTH = 280.0;"));
    }

    @Test
    void globalButtonSkinIsOptIn() throws IOException {
        PackAssets.Pack pack = build("resourcepack.global-button-skin", "true");
        assertTrue(pack.files().containsKey("assets/minecraft/textures/gui/sprites/widget/button.png"));
        assertTrue(pack.files().containsKey("assets/minecraft/textures/gui/sprites/widget/button_highlighted.png.mcmeta"));
    }

    @Test
    void sameFilesSameHash() throws IOException {
        assertEquals(Hashing.sha1Hex(DeterministicZip.write(build().files(), false)), Hashing.sha1Hex(DeterministicZip.write(build().files(), false)));
    }
}
