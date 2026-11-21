package fi.alavesa.intercom.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.alavesa.intercom.config.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Puts the resource pack together in memory: the bundled files, the files written from config.yml (shader
 * constants, post effect levels, pack.mcmeta), the merged core/text shader, and the server owner's own
 * replacement files. Blocking file work with no Bukkit access.
 *
 * <pre>
 *   pack.mcmeta
 *   assets/minecraft/shaders/core/text.vsh, text.fsh      vanilla (or another plugin's) + the Intercom lines
 *   assets/minecraft/textures/gui/sprites/boss_bar/       blue bar blanked: the banner's boss bar shows no bar
 *   assets/intercom/shaders/include/intercom_*.glsl       the banner logic; intercom_config.glsl from config.yml
 *   assets/intercom/shaders/post/dim.fsh                  the screen dim
 *   assets/intercom/post_effect/dim_1..N.json             its fade levels, from config.yml
 *   assets/intercom/font/banner.json, ui.json             banner font; dialog textures as glyphs
 *   assets/intercom/textures/font/*.png                   placeholder textures
 *   assets/intercom/sounds.json, sounds/*.ogg             placeholder sounds
 * </pre>
 */
public final class PackAssets {

    private static final String PREFIX = "pack/", OPTIONAL_BUTTONS = "pack-optional/global-buttons/";
    private static final String CONFIG_GLSL = "assets/intercom/shaders/include/intercom_config.glsl";
    private static final String BOSS_BAR = "assets/minecraft/textures/gui/sprites/boss_bar/";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /**
     * @param files             pack path -> bytes, sorted
     * @param bannerShader      true when the pack carries the text shader the banner needs
     * @param shaderDescription how the text shader came about
     */
    public record Pack(SortedMap<String, byte[]> files, boolean bannerShader, String shaderDescription, List<String> warnings) { }

    private PackAssets() { }

    /**
     * @param bundle        the pack sources shipped with the plugin (see {@link Bundle})
     * @param pluginsFolder where the other plugins' pack zips are looked up; null = merge with nobody
     * @param overrides     folder whose files replace or add pack files (same paths as in the pack); null = none
     */
    public static Pack collect(Map<String, byte[]> bundle, File pluginsFolder, Path overrides, Settings settings) {
        SortedMap<String, byte[]> files = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : bundle.entrySet()) {
            if (file.getKey().startsWith(PREFIX)) files.put(file.getKey().substring(PREFIX.length()), file.getValue());
            else if (settings.pack().globalButtonSkin() && file.getKey().startsWith(OPTIONAL_BUTTONS)) {
                files.put(file.getKey().substring(OPTIONAL_BUTTONS.length()), file.getValue());
            }
        }
        files.put("pack.mcmeta", json(mcmeta(settings.pack())));
        files.put(CONFIG_GLSL, configGlsl(settings).getBytes(StandardCharsets.UTF_8));
        for (int level = 1; level <= settings.postEffect().levels(); level++) {
            files.put("assets/intercom/post_effect/dim_" + level + ".json", json(postEffect(settings.postEffect(), level)));
        }

        boolean shader = false;
        String description;
        byte[] vertex = bundle.get("shaders/vanilla_text.vsh"), fragment = bundle.get("shaders/vanilla_text.fsh");
        if (vertex == null || fragment == null) {
            warnings.add("The vanilla text shader is missing from the plugin jar - announcements use the fallback for everyone");
            description = "none (missing from the jar)";
        } else {
            List<Map.Entry<String, File>> candidates = new ArrayList<>();
            if (pluginsFolder != null) {
                for (Map.Entry<String, String> zip : settings.pack().shaderZips().entrySet()) candidates.add(Map.entry(zip.getKey(), new File(pluginsFolder, zip.getValue())));
            }
            TextShaderMerge.Result merged = TextShaderMerge.build(new String(vertex, StandardCharsets.UTF_8), new String(fragment, StandardCharsets.UTF_8), candidates, warnings::add);
            shader = merged.shipped();
            description = merged.description();
            files.putAll(merged.files());
        }
        if (!shader) {
            // no banner is ever shown without the shader, so its boss bar is never sent: leave blue bars alone
            files.keySet().removeIf(path -> path.startsWith(BOSS_BAR));
        }

        if (overrides != null && Files.isDirectory(overrides)) {
            try (Stream<Path> walk = Files.walk(overrides)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String path = overrides.relativize(file).toString().replace(File.separatorChar, '/');
                    if (path.endsWith(".DS_Store") || path.endsWith(".txt") && !path.contains("/")) continue;
                    if (path.equals(CONFIG_GLSL)) {
                        warnings.add("pack-overrides/" + path + " is ignored: that file is written from config.yml (banner.*, timing.*)");
                        continue;
                    }
                    files.put(path, Files.readAllBytes(file));
                }
            } catch (IOException e) {
                warnings.add("pack-overrides could not be read (" + e.getMessage() + ") - the pack has the default files");
            }
        }
        return new Pack(files, shader, description, warnings);
    }

    /**
     * 26.3 is resource pack format 97. Since format 65 a pack declares the range it supports with
     * min_format / max_format; pack_format is still written for tools that only know the old field.
     */
    static JsonObject mcmeta(Settings.Pack settings) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", settings.description());
        pack.addProperty("pack_format", settings.formatMin());
        pack.addProperty("min_format", settings.formatMin());
        pack.addProperty("max_format", settings.formatMax());
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    /** The constants intercom_text_vertex.glsl / intercom_text_fragment.glsl read. */
    static String configGlsl(Settings settings) {
        Settings.Banner b = settings.banner();
        return String.format(Locale.ROOT,
            "// Written by the Intercom plugin from config.yml (banner.*, timing.*). Edit config.yml, not this file.%n"
                + "const float INTERCOM_WIDTH = %d.0;%n"
                + "const float INTERCOM_TOP = %d.0;%n"
                + "const float INTERCOM_BG_ALPHA = %.3f;%n"
                + "const vec3  INTERCOM_TEXT_COLOR = %s;%n"
                + "const vec3  INTERCOM_SENDER_COLOR = %s;%n"
                + "const float INTERCOM_ENTER_TICKS = %d.0;%n"
                + "const float INTERCOM_EXIT_TICKS = %d.0;%n",
            b.width(), b.topOffset(), b.backgroundOpacity(), vec3(b.textRgb()), vec3(b.senderRgb()),
            settings.timing().enterTicks(), settings.timing().exitTicks());
    }

    private static String vec3(int rgb) {
        return String.format(Locale.ROOT, "vec3(%.4f, %.4f, %.4f)", ((rgb >> 16) & 0xFF) / 255.0, ((rgb >> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0);
    }

    /**
     * One fade level of the screen dim, in the 26.3 post_effect format (same shape as vanilla's invert.json):
     * main -> swap through intercom:post/dim, then blitted back to main.
     */
    static JsonObject postEffect(Settings.PostEffect settings, int level) {
        JsonObject dim = pass("intercom:post/dim", "minecraft:main", "swap");
        JsonArray dimUniforms = new JsonArray();
        dimUniforms.add(uniform("Amount", level / (double) settings.levels()));
        dimUniforms.add(uniform("Dim", settings.dim()));
        dimUniforms.add(uniform("Vignette", settings.vignette()));
        dimUniforms.add(uniform("Desaturate", settings.desaturate()));
        JsonObject dimBlocks = new JsonObject();
        dimBlocks.add("IntercomDim", dimUniforms);
        dim.add("uniforms", dimBlocks);

        JsonObject blit = pass("minecraft:post/blit", "swap", "minecraft:main");
        JsonObject modulate = new JsonObject();
        modulate.addProperty("name", "ColorModulate");
        modulate.addProperty("type", "vec4");
        JsonArray white = new JsonArray();
        for (int i = 0; i < 4; i++) white.add(1.0);
        modulate.add("value", white);
        JsonArray blitUniforms = new JsonArray();
        blitUniforms.add(modulate);
        JsonObject blitBlocks = new JsonObject();
        blitBlocks.add("BlitConfig", blitUniforms);
        blit.add("uniforms", blitBlocks);

        JsonObject targets = new JsonObject();
        targets.add("swap", new JsonObject());
        JsonArray passes = new JsonArray();
        passes.add(dim);
        passes.add(blit);
        JsonObject root = new JsonObject();
        root.add("targets", targets);
        root.add("passes", passes);
        return root;
    }

    private static JsonObject pass(String fragmentShader, String from, String to) {
        JsonObject input = new JsonObject();
        input.addProperty("sampler_name", "In");
        input.addProperty("target", from);
        JsonArray inputs = new JsonArray();
        inputs.add(input);
        JsonObject pass = new JsonObject();
        pass.addProperty("vertex_shader", "minecraft:core/screenquad");
        pass.addProperty("fragment_shader", fragmentShader);
        pass.add("inputs", inputs);
        pass.addProperty("output", to);
        return pass;
    }

    private static JsonObject uniform(String name, double value) {
        JsonObject uniform = new JsonObject();
        uniform.addProperty("name", name);
        uniform.addProperty("type", "float");
        uniform.addProperty("value", Math.round(value * 10000) / 10000.0);
        return uniform;
    }

    /** Size and time of the other plugins' pack zips: changes when one of them rebuilds its pack. */
    public static String sourceSignature(File pluginsFolder, Settings.Pack pack) {
        StringBuilder sb = new StringBuilder();
        for (String path : pack.shaderZips().values()) {
            File zip = new File(pluginsFolder, path);
            sb.append(zip.length()).append(':').append(zip.lastModified()).append(';');
        }
        return sb.toString();
    }

    private static byte[] json(JsonObject o) { return (GSON.toJson(o) + "\n").getBytes(StandardCharsets.UTF_8); }
}
