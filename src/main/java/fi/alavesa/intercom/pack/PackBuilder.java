package fi.alavesa.intercom.pack;

import fi.alavesa.intercom.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.SortedMap;

/**
 * Writes a collected pack to disk: the zip players download, its SHA-1 next to it, and the same files as a
 * folder for server owners who merge them into their own pack. The zip is deterministic (see
 * {@link DeterministicZip}): the same files always give the same SHA-1, so clients download the pack again
 * only when it really changed. Blocking disk work, no Bukkit access.
 */
public final class PackBuilder {

    public static final String PACK_DIR = "resourcepack", ZIP_NAME = "Intercom-pack.zip";

    /** @param sha1 SHA-1 of the zip, lower-case hex - what the client verifies the download with */
    public record Written(Path zip, Path folder, String sha1, int fileCount) { }

    private PackBuilder() { }

    public static Written write(Path directory, SortedMap<String, byte[]> files) throws IOException {
        Path zip = directory.resolve(ZIP_NAME), folder = directory.resolve(PACK_DIR);
        Files.createDirectories(directory);
        byte[] bytes = DeterministicZip.write(files, false);
        String sha1 = Hashing.sha1Hex(bytes);
        writeFolder(folder, files);
        // written under another name first: the HTTP hosts never see a half-written zip
        Path tmp = zip.resolveSibling(ZIP_NAME + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(directory.resolve(ZIP_NAME + ".sha1"), sha1 + "\n", StandardCharsets.UTF_8);
        return new Written(zip, folder, sha1, files.size());
    }

    /** Replaces the folder form of the pack: everything in resourcepack/ is generated, nothing else lives there. */
    private static void writeFolder(Path folder, SortedMap<String, byte[]> files) throws IOException {
        if (Files.exists(folder)) {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (!dir.equals(folder)) Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = folder.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
    }
}
