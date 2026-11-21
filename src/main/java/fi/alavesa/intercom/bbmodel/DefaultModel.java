package fi.alavesa.intercom.bbmodel;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import fi.alavesa.intercom.util.Png;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * The starter microphone: a desk paging microphone - weighted base with a talk button, a status LED and a
 * label plate, a stem, a gooseneck bent towards the speaker and a capsule with a mesh grille. Written as an
 * ordinary Blockbench project (generic "free" format, one embedded 32x32 texture) into the models folder when
 * that folder has no microphone.bbmodel. From then on the file on disk is the model: open it in Blockbench,
 * change it or replace it with any other .bbmodel of the same name, and run /announce reload.
 * <p>
 * Blockbench axes: 16 px = one block, the model's FRONT faces north (-z), it stands on y = 0 around x = z = 0.
 */
public final class DefaultModel {

    public static final String FILE_NAME = "microphone.bbmodel";

    /** Texture atlas regions, {@code [u1, v1, u2, v2]} in a 32 x 32 UV space. */
    private static final double[] METAL = {0, 0, 16, 16}, GRILLE = {16, 0, 32, 16}, RED = {0, 16, 4, 20},
        GREEN = {4, 16, 8, 20}, CHROME = {8, 16, 16, 20}, PLATE = {0, 20, 16, 28}, RUBBER = {16, 16, 32, 32};

    private final JsonArray elements = new JsonArray();

    private DefaultModel() { }

    public static String json() {
        DefaultModel m = new DefaultModel();

        JsonArray base = new JsonArray();
        base.add(m.cube("foot", -4.5, 0, -4.5, 4.5, 0.4, 4.5, RUBBER, RUBBER, 0));
        base.add(m.cube("base", -4.2, 0.4, -4.2, 4.2, 1.8, 4.2, METAL, METAL, 0));
        base.add(m.cube("base_top", -3.6, 1.8, -3.6, 3.6, 2.2, 3.6, METAL, METAL, 0));
        base.add(m.cube("button_rim", -1.9, 2.2, -3.3, 1.9, 2.4, -0.9, CHROME, CHROME, 0));
        base.add(m.cube("talk_button", -1.5, 2.4, -2.9, 1.5, 2.9, -1.3, RED, RED, 12));
        base.add(m.cube("status_led", 2.4, 2.2, -3.1, 3.1, 2.5, -2.4, GREEN, GREEN, 15));
        base.add(m.cube("label_plate", -3.1, 2.2, 1.2, 3.1, 2.3, 3.1, METAL, PLATE, 0));
        base.add(m.cube("stem_collar", -1.1, 2.2, -0.4, 1.1, 2.8, 1.0, CHROME, CHROME, 0));
        base.add(m.cube("stem", -0.6, 2.8, -0.1, 0.6, 7.0, 0.7, METAL, METAL, 0));
        base.add(m.cube("cable", -0.4, 0.4, 4.2, 0.4, 1.0, 6.5, RUBBER, RUBBER, 0));

        // The capsule hangs on the end of the neck, bent further forward: a group nested in a rotated group.
        JsonArray head = new JsonArray();
        head.add(m.cube("head_collar", -0.9, 10.6, -0.6, 0.9, 11.3, 1.2, CHROME, CHROME, 0));
        head.add(m.cube("capsule", -1.3, 11.3, -1.0, 1.3, 14.0, 1.6, GRILLE, GRILLE, 0));
        head.add(m.cube("capsule_ring", -1.45, 12.3, -1.15, 1.45, 12.8, 1.75, CHROME, CHROME, 0));
        head.add(m.cube("capsule_cap", -1.0, 14.0, -0.7, 1.0, 14.6, 1.3, GRILLE, GRILLE, 0));

        JsonArray neck = new JsonArray();
        neck.add(m.cube("neck_joint", -0.8, 6.7, -0.3, 0.8, 7.5, 0.9, CHROME, CHROME, 0));
        neck.add(m.cube("neck", -0.4, 7.5, -0.1, 0.4, 10.6, 0.7, RUBBER, RUBBER, 0));
        // a negative x rotation tips the top towards the front (north)
        neck.add(group("head", new double[] {0, 10.9, 0.3}, new double[] {-30, 0, 0}, head));

        JsonArray outliner = new JsonArray();
        outliner.add(group("base", new double[] {0, 0, 0}, new double[] {0, 0, 0}, base));
        outliner.add(group("neck", new double[] {0, 7.1, 0.3}, new double[] {-25, 0, 0}, neck));

        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "4.10");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);

        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", 32);
        resolution.addProperty("height", 32);

        JsonObject texture = new JsonObject();
        texture.addProperty("name", "microphone.png");
        texture.addProperty("id", "0");
        texture.addProperty("uuid", uuid("texture"));
        texture.addProperty("width", 32);
        texture.addProperty("height", 32);
        texture.addProperty("uv_width", 32);
        texture.addProperty("uv_height", 32);
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(texture()));
        JsonArray textures = new JsonArray();
        textures.add(texture);

        JsonObject root = new JsonObject();
        root.add("meta", meta);
        root.addProperty("name", "microphone");
        root.add("resolution", resolution);
        root.add("elements", m.elements);
        root.add("outliner", outliner);
        root.add("textures", textures);
        return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root);
    }

    /**
     * @param sides atlas region of the four side faces and the bottom
     * @param top   atlas region of the top face
     */
    private JsonElement cube(String name, double x1, double y1, double z1, double x2, double y2, double z2,
                             double[] sides, double[] top, int lightEmission) {
        JsonObject cube = new JsonObject();
        cube.addProperty("name", name);
        cube.addProperty("type", "cube");
        cube.addProperty("uuid", uuid(name));
        cube.add("from", array(x1, y1, z1));
        cube.add("to", array(x2, y2, z2));
        cube.add("origin", array((x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2));
        cube.add("rotation", array(0, 0, 0));
        cube.addProperty("box_uv", false);
        if (lightEmission > 0) cube.addProperty("light_emission", lightEmission);
        JsonObject faces = new JsonObject();
        for (String side : new String[] {"north", "east", "south", "west", "down"}) faces.add(side, face(sides));
        faces.add("up", face(top));
        cube.add("faces", faces);
        elements.add(cube);
        return new JsonPrimitive(uuid(name));
    }

    private static JsonObject face(double[] region) {
        JsonObject face = new JsonObject();
        face.add("uv", array(region));
        face.addProperty("texture", 0);
        return face;
    }

    private static JsonObject group(String name, double[] origin, double[] rotation, JsonArray children) {
        JsonObject group = new JsonObject();
        group.addProperty("name", name);
        group.addProperty("uuid", uuid("group:" + name));
        group.add("origin", array(origin));
        group.add("rotation", array(rotation));
        group.addProperty("isOpen", true);
        group.add("children", children);
        return group;
    }

    /** Stable uuids, so the generated file is the same on every server. */
    private static String uuid(String element) {
        return UUID.nameUUIDFromBytes(("intercom-microphone/" + element).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JsonArray array(double... values) {
        JsonArray a = new JsonArray();
        for (double v : values) a.add(v);
        return a;
    }

    /** 32 x 32 atlas: gunmetal, mesh grille, red button, green LED, chrome, the label plate, black rubber. */
    static byte[] texture() {
        int size = 32;
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int c = 0;
                if (y < 16 && x < 16) {
                    c = (x * 5 + y * 3) % 11 == 0 ? 0xFF3A3F47 : y == 0 ? 0xFF5A616C : 0xFF444A54;   // gunmetal, lit top edge
                } else if (y < 16) {
                    c = (x + y) % 2 == 0 ? 0xFF9AA1AB : 0xFF2A2D33;                                  // mesh
                } else if (y < 20 && x < 4) {
                    c = (x == 0 || y == 16) ? 0xFFFF6A5E : 0xFFD9261C;                               // talk button
                } else if (y < 20 && x < 8) {
                    c = 0xFF4CFF6E;                                                                  // LED
                } else if (y < 20 && x < 16) {
                    c = y == 16 ? 0xFFE6EAF0 : y == 19 ? 0xFF8A9099 : 0xFFB9BFC8;                    // chrome band
                } else if (y >= 20 && y < 28 && x < 16) {
                    c = (y == 20 || y == 27 || x == 0 || x == 15) ? 0xFF8A9099 : 0xFF1C1E22;         // plate with a rim
                } else if (y >= 16 && x >= 16) {
                    c = (x + y * 2) % 7 == 0 ? 0xFF1D1F23 : 0xFF16181B;                              // rubber
                }
                px[y * size + x] = c;
            }
        }
        // "PA" on the label plate, 3 x 5 pixel letters in amber
        int amber = 0xFFE8A028;
        int[][] letters = {
            {4, 22}, {5, 22}, {6, 22}, {4, 23}, {6, 23}, {4, 24}, {5, 24}, {6, 24}, {4, 25}, {4, 26},
            {9, 22}, {10, 22}, {11, 22}, {9, 23}, {11, 23}, {9, 24}, {10, 24}, {11, 24}, {9, 25}, {11, 25}, {9, 26}, {11, 26}};
        for (int[] p : letters) px[p[1] * size + p[0]] = amber;
        return Png.encode(size, size, px);
    }
}
