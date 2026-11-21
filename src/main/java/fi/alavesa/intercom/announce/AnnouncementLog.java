package fi.alavesa.intercom.announce;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * plugins/Intercom/announcements.log: one line per announcement, appended on a single background thread so the
 * server thread never waits for the disk and lines keep their order.
 */
public final class AnnouncementLog {

    private final Path file;
    private final Logger log;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "intercom-log");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean failed;

    public AnnouncementLog(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void append(Announcement announcement) {
        String line = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS))
            + "\t" + announcement.senderName()
            + "\t" + (announcement.senderId() == null ? "console" : announcement.senderId())
            + "\t" + announcement.text() + System.lineSeparator();
        try {
            writer.execute(() -> write(line));
        } catch (RejectedExecutionException ignored) {
            // the plugin is shutting down; the console line was already written
        }
    }

    private void write(String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!failed) log.warning("announcements.log could not be written (" + e.getMessage() + ") - announcements are only in the console log");
            failed = true;
        }
    }

    /** Writes what is still waiting, then stops the thread. */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
