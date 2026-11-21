package fi.alavesa.intercom.microphone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.alavesa.intercom.bbmodel.BbModel;
import fi.alavesa.intercom.bbmodel.BbModelParser;
import fi.alavesa.intercom.bbmodel.DefaultModel;
import fi.alavesa.intercom.bbmodel.JavaModelWriter;
import fi.alavesa.intercom.bbmodel.ModelException;
import fi.alavesa.intercom.bbmodel.ModelGeometry;
import fi.alavesa.intercom.bbmodel.ModelLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * models/microphone.bbmodel -> the item model in the resource pack and the numbers the placed display needs.
 * This is the one place the model file is read. Blocking disk work with no Bukkit access.
 *
 * <pre>
 *   assets/intercom/items/microphone.json                 what item_model=intercom:microphone resolves to
 *   assets/intercom/models/item/microphone.json           the converted model
 *   assets/intercom/textures/item/microphone/&lt;tex&gt;.png    its textures (+ .png.mcmeta for animated ones)
 * </pre>
 *
 * @param geometry  flattened model: bounds, origin and the fit the display has to undo
 * @param packFiles pack path -> bytes
 * @param warnings  everything worth telling the admin about the file
 */
public record MicrophoneModel(ModelGeometry geometry, Map<String, byte[]> packFiles, List<String> warnings) {

    public static final String ID = "microphone", MODELS_DIR = "models";
    private static final String NAMESPACE = "intercom";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * @param modelsDir the folder holding microphone.bbmodel; the starter model is written there when the file
     *                  is missing. null = use the starter model without touching the disk
     * @throws ModelException when the file cannot be used; the message says why
     */
    public static MicrophoneModel load(Path modelsDir) throws ModelException, IOException {
        byte[] file;
        Path dir = modelsDir;
        if (modelsDir == null) {
            file = DefaultModel.json().getBytes(StandardCharsets.UTF_8);
        } else {
            Path source = modelsDir.resolve(DefaultModel.FILE_NAME);
            if (!Files.exists(source)) {
                Files.createDirectories(modelsDir);
                Files.writeString(source, DefaultModel.json());
            }
            file = Files.readAllBytes(source);
        }
        ModelLog log = new ModelLog(DefaultModel.FILE_NAME);
        BbModel model = BbModelParser.parse(ID, file, relative -> sibling(dir, relative), log);
        ModelGeometry geometry = ModelGeometry.of(model);

        Map<String, byte[]> files = new TreeMap<>();
        String assets = "assets/" + NAMESPACE + "/";
        files.put(assets + "models/item/" + ID + ".json", json(new JavaModelWriter(NAMESPACE).write(model, geometry, log)));
        JsonObject reference = new JsonObject();
        reference.addProperty("type", "minecraft:model");
        reference.addProperty("model", NAMESPACE + ":item/" + ID);
        JsonObject definition = new JsonObject();
        definition.add("model", reference);
        files.put(assets + "items/" + ID + ".json", json(definition));
        for (BbModel.Texture texture : model.textures) {
            if (texture.png() == null) continue;
            String path = assets + "textures/item/" + ID + "/" + texture.key() + ".png";
            files.put(path, texture.png());
            if (texture.mcmeta() != null) {
                files.put(path + ".mcmeta", texture.mcmeta());
            } else if (texture.frames() > 1) {
                JsonObject animation = new JsonObject();
                animation.addProperty("frametime", texture.frameTime());
                if (texture.interpolate()) animation.addProperty("interpolate", true);
                JsonObject root = new JsonObject();
                root.add("animation", animation);
                files.put(path + ".mcmeta", json(root));
            }
        }
        return new MicrophoneModel(geometry, files, List.copyOf(log.warnings()));
    }

    /** A file next to the model (a texture that is not embedded). Never leaves the models folder. */
    private static byte[] sibling(Path modelsDir, String relative) {
        if (modelsDir == null) return null;
        try {
            Path path = modelsDir.resolve(relative).normalize();
            if (!path.startsWith(modelsDir) || !Files.isRegularFile(path)) return null;
            return Files.readAllBytes(path);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] json(JsonObject o) { return GSON.toJson(o).getBytes(StandardCharsets.UTF_8); }
}
