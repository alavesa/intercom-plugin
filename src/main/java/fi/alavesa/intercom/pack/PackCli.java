package fi.alavesa.intercom.pack;

import fi.alavesa.intercom.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Builds the stand-alone resource pack outside a server - what the Gradle task {@code packZip} runs:
 * the bundled files with the default config.yml, the vanilla text shader plus the Intercom lines, no other
 * plugin's shader merged in.
 *
 * <pre>usage: PackCli &lt;src/main/resources&gt; &lt;output directory&gt;</pre>
 */
public final class PackCli {

    private PackCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: PackCli <resources directory> <output directory>");
            System.exit(2);
        }
        Path resources = Path.of(args[0]), out = Path.of(args[1]);
        Logger log = Logger.getLogger("Intercom");
        Settings settings = Settings.read(YamlConfiguration.loadConfiguration(resources.resolve("config.yml").toFile()), log);
        PackAssets.Pack pack = PackAssets.collect(Bundle.fromDirectory(resources), null, null, settings);
        pack.warnings().forEach(log::warning);
        if (!pack.bannerShader()) throw new IllegalStateException("the text shader could not be built: " + pack.shaderDescription());
        PackBuilder.Written written = PackBuilder.write(out, pack.files());
        System.out.println(written.fileCount() + " files, text shader: " + pack.shaderDescription());
    }
}
