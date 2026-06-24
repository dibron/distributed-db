package com.distributeddb.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-Ahead Log (WAL) — the safety net for our database.
 *
 * HOW IT WORKS:
 * =============
 * Every time someone does a PUT or DELETE, we:
 *   1. Write it to this log file FIRST (disk is safe — survives crashes)
 *   2. THEN update the MemTable (RAM is fast but volatile)
 *
 * If the computer crashes and restarts, the MemTable (RAM) is empty.
 * But the WAL file is still on disk! We read it line by line and
 * replay every operation to rebuild the MemTable.
 *
 * WHAT THE FILE LOOKS LIKE:
 * =========================
 * Each line is one operation:
 *   1719532800000|PUT|apple|red
 *   1719532800001|PUT|banana|yellow
 *   1719532800002|DELETE|apple|
 *
 * WHY "WRITE-AHEAD"?
 * ==================
 * Because we write to the log AHEAD of (before) updating the MemTable.
 * If we crash between step 1 and step 2, the log has the data and we can recover.
 * If we did it the other way (MemTable first, then log), a crash after step 1
 * would mean the data is in RAM (gone!) but not on disk (can't recover!).
 */
public class WriteAheadLog implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WriteAheadLog.class);

    private final Path filePath;        // where the WAL file lives on disk
    private BufferedWriter writer;      // writes text to the file

    public WriteAheadLog(Path filePath) throws IOException {
        this.filePath = filePath;

        // Create parent directories if they don't exist
        // e.g., if filePath is "data/wal/wal.log", create "data/wal/"
        if (filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }

        // Open the file in APPEND mode — new entries go at the END
        // If the file doesn't exist yet, it gets created
        this.writer = new BufferedWriter(new FileWriter(filePath.toFile(), true));

        log.info("WAL opened at {}", filePath);
    }

    /**
     * Append one entry to the log file.
     *
     * This is the most important method. It:
     * 1. Converts the entry to a string  (e.g., "1719...|PUT|apple|red")
     * 2. Writes that string as a new line in the file
     * 3. Calls flush() to force the data to disk IMMEDIATELY
     *
     * flush() is critical — without it, the data might sit in a memory buffer
     * and not actually reach the disk before a crash.
     */
    public synchronized void append(WALEntry entry) throws IOException {
        writer.write(entry.serialize());
        writer.newLine();
        writer.flush();  // FORCE to disk — don't let the OS buffer this!
    }

    /**
     * Read ALL entries from the log file (used on startup to recover).
     *
     * Opens the file, reads it line by line, and converts each line
     * back into a WALEntry. Returns them in order (oldest first).
     */
    public List<WALEntry> replay() throws IOException {
        List<WALEntry> entries = new ArrayList<>();

        if (!Files.exists(filePath)) {
            log.info("No WAL file found at {} — starting fresh", filePath);
            return entries;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    entries.add(WALEntry.deserialize(line));
                }
            }
        }

        log.info("Replayed {} entries from WAL", entries.size());
        return entries;
    }

    /**
     * Delete the WAL file and start a fresh one.
     *
     * We call this AFTER the MemTable has been flushed to an SSTable (Phase 1c).
     * At that point, the data is safely on disk in the SSTable, so we don't
     * need the WAL entries anymore.
     */
    public synchronized void reset() throws IOException {
        writer.close();
        Files.deleteIfExists(filePath);
        this.writer = new BufferedWriter(new FileWriter(filePath.toFile(), true));
        log.info("WAL reset — old entries cleared");
    }

    /**
     * Close the file when we're done.
     */
    @Override
    public synchronized void close() throws IOException {
        writer.close();
        log.info("WAL closed");
    }
}
