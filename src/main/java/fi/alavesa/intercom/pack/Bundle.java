package fi.alavesa.intercom.pack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The pack sources that ship inside the plugin: resource path -> bytes for everything under pack/,
 * pack-optional/ and shaders/. Read from the plugin jar on a server and from src/main/resources by the Gradle
 * {@code packZip} task, so both build the pack from the same files.
 */
public final class Bundle {

    private static final String[] ROOTS = {"pack/", "pack-optional/", "shaders/"};

    private Bundle() { }

    private static boolean wanted(String path) {
        for (String root : ROOTS) if (path.startsWith(root)) return true;
        return false;
    }

    public static Map<String, byte[]> fromJar(File jar) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (JarFile zip = new JarFile(jar)) {
            for (JarEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory() || !wanted(entry.getName())) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    files.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return files;
    }

    public static Map<String, byte[]> fromDirectory(Path resources) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(resources)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String path = resources.relativize(file).toString().replace(File.separatorChar, '/');
                if (wanted(path) && !path.endsWith(".DS_Store")) files.put(path, Files.readAllBytes(file));
            }
        }
        return files;
    }
}
