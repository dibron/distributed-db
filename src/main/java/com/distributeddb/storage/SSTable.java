package com.distributeddb.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * SSTable (Sorted String Table) — an immutable sorted file on disk.
 *
 * WHAT IS IT?
 * ===========
 * When the MemTable gets full, we "flush" it to disk as an SSTable.
 * The file contains key=value pairs, SORTED by key, one per line.
 *
 * WHAT THE FILE LOOKS LIKE:
 * =========================
 *   apple|red
 *   banana|yellow
 *   cherry|red
 *   date|brown
 *
 * WHY SORTED?
 * ===========
 * Because we can use binary search to find any key in O(log n) time
 * instead of scanning every line O(n).
 *
 * Example: Finding "cherry" in 1000 entries:
 *   - Unsorted: check all 1000 lines (slow!)
 *   - Sorted: check ~10 lines using binary search (fast!)
 *
 * IMMUTABLE = NEVER CHANGED
 * =========================
 * Once written, an SSTable is NEVER modified. New data goes to
 * a new MemTable → new SSTable. Old SSTables are only removed
 * during compaction (Phase 1d).
 */
public class SSTable {

    private static final Logger log = LoggerFactory.getLogger(SSTable.class);

    private final Path filePath;

    // We load the sorted keys into memory for binary search.
    // For a real DB, you'd use an on-disk index, but this is simpler to learn.
    private final List<String> keys;
    private final List<String> values;

    /**
     * Load an existing SSTable from disk.
     * Reads the whole file into memory (keys + values lists).
     */
    public SSTable(Path filePath) throws IOException {
        this.filePath = filePath;
        this.keys = new ArrayList<>();
        this.values = new ArrayList<>();

        if (Files.exists(filePath)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        String[] parts = line.split("\\|", 2);
                        keys.add(parts[0]);
                        values.add(parts[1]);
                    }
                }
            }
            log.info("Loaded SSTable {} with {} entries", filePath.getFileName(), keys.size());
        }
    }

    /**
     * Look up a key using binary search.
     *
     * HOW BINARY SEARCH WORKS:
     * ========================
     * Imagine a phone book (sorted A→Z). To find "Miller":
     *   1. Open to the middle → "Johnson" → Miller comes AFTER → search right half
     *   2. Open middle of right half → "Peterson" → Miller comes BEFORE → search left half
     *   3. Open middle of that → "Miller" → FOUND!
     *
     * Each step cuts the search space in HALF. That's why it's O(log n).
     * 1000 entries → ~10 steps. 1,000,000 entries → ~20 steps!
     */
    public Optional<String> get(String key) {
        int index = Collections.binarySearch(keys, key);

        if (index >= 0) {
            return Optional.of(values.get(index));
        }
        return Optional.empty();
    }

    /**
     * Write a MemTable (sorted map) to disk as a new SSTable file.
     *
     * This is called "flushing." The MemTable is already sorted
     * (because ConcurrentSkipListMap keeps keys sorted), so we
     * just iterate and write each key|value on its own line.
     *
     * Returns the new SSTable object so we can read from it later.
     */
    public static SSTable flush(SortedMap<String, String> data, Path filePath) throws IOException {
        if (filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()))) {
            for (Map.Entry<String, String> entry : data.entrySet()) {
                writer.write(entry.getKey() + "|" + entry.getValue());
                writer.newLine();
            }
            writer.flush();
        }

        log.info("Flushed {} entries to SSTable {}", data.size(), filePath.getFileName());
        return new SSTable(filePath);
    }

    /**
     * How many entries are in this SSTable.
     */
    public int size() {
        return keys.size();
    }

    public Path getFilePath() {
        return filePath;
    }

    /**
     * Delete this SSTable file from disk.
     * Used during compaction (Phase 1d) when merging old SSTables.
     */
    public void delete() throws IOException {
        Files.deleteIfExists(filePath);
        log.info("Deleted SSTable {}", filePath.getFileName());
    }

    /**
     * Return all entries as a sorted map.
     * Used during compaction to merge multiple SSTables.
     */
    public SortedMap<String, String> allEntries() {
        SortedMap<String, String> map = new TreeMap<>();
        for (int i = 0; i < keys.size(); i++) {
            map.put(keys.get(i), values.get(i));
        }
        return map;
    }
}
