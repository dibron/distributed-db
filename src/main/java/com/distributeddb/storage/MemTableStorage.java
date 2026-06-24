package com.distributeddb.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Storage engine that combines MemTable + WAL + SSTables.
 *
 * THE FULL PICTURE (Phase 1a + 1b + 1c):
 * ========================================
 *
 *   WRITE PATH:
 *   Client → WAL (disk, safe) → MemTable (RAM, fast)
 *                                    ↓ when full
 *                               SSTable (disk, sorted, immutable)
 *
 *   READ PATH:
 *   Client → MemTable (RAM) → SSTable[newest] → SSTable[older] → ... → not found
 *
 *   RECOVERY:
 *   Startup → Replay WAL → Rebuild MemTable
 *   (SSTables are already on disk, no recovery needed for them)
 */
public class MemTableStorage implements StorageEngine, Closeable {

    private static final Logger log = LoggerFactory.getLogger(MemTableStorage.class);

    private final ConcurrentSkipListMap<String, String> memTable = new ConcurrentSkipListMap<>();
    private final WriteAheadLog wal;
    private final Path dataDir;

    // SSTables on disk — newest FIRST (index 0 = newest)
    // We search newest first because it has the most recent data
    private final List<SSTable> sstables = new ArrayList<>();

    // When the MemTable reaches this many keys, flush it to an SSTable
    private final int flushThreshold;

    // When there are this many SSTables, compact them into one
    private final int compactionThreshold;

    // Counter for naming SSTable files: sstable_0001.dat, sstable_0002.dat, etc.
    private int sstableCounter = 0;

    // Special marker value: when we DELETE a key, we write this to the MemTable
    // instead of removing it. This way, when we flush to SSTable, the SSTable
    // knows that the key was deleted (a "tombstone").
    // Without this, an old SSTable might still have the key and return it!
    static final String TOMBSTONE = "__TOMBSTONE__";

    /**
     * Create storage with WAL and SSTable support.
     *
     * @param walPath        where the WAL file lives
     * @param dataDir        folder for SSTable files
     * @param flushThreshold flush MemTable after this many keys
     */
    public MemTableStorage(Path walPath, Path dataDir, int flushThreshold, int compactionThreshold) throws IOException {
        this.dataDir = dataDir;
        this.flushThreshold = flushThreshold;
        this.compactionThreshold = compactionThreshold;

        // Load any existing SSTables from disk (from previous runs)
        loadExistingSSTables();

        // Set up WAL and replay for recovery
        this.wal = new WriteAheadLog(walPath);
        List<WALEntry> entries = wal.replay();
        for (WALEntry entry : entries) {
            applyToMemTable(entry);
        }

        if (!entries.isEmpty()) {
            log.info("Recovered {} entries from WAL into MemTable", entries.size());
        }
    }

    /**
     * Create storage with just a WAL (no SSTables) — for backward compatibility.
     */
    public MemTableStorage(Path walPath, Path dataDir, int flushThreshold) throws IOException {
        this(walPath, dataDir, flushThreshold, 4);
    }

    public MemTableStorage(Path walPath) throws IOException {
        this(walPath, walPath.getParent().resolve("sstables"), 1000, 4);
    }

    /**
     * Create storage without WAL or SSTables (for simple testing only).
     */
    public MemTableStorage() {
        this.wal = null;
        this.dataDir = null;
        this.flushThreshold = Integer.MAX_VALUE;
        this.compactionThreshold = Integer.MAX_VALUE;
    }

    @Override
    public void put(String key, String value) {
        // Step 1: Write to WAL FIRST (disk — safe)
        if (wal != null) {
            try {
                wal.append(WALEntry.put(key, value));
            } catch (IOException e) {
                throw new RuntimeException("Failed to write to WAL", e);
            }
        }

        // Step 2: Update MemTable (RAM — fast)
        memTable.put(key, value);

        // Step 3: If MemTable is too big, flush to SSTable
        if (memTable.size() >= flushThreshold) {
            try {
                flushMemTable();
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush MemTable", e);
            }
        }
    }

    @Override
    public Optional<String> get(String key) {
        // Step 1: Check MemTable first (newest data)
        String value = memTable.get(key);
        if (value != null) {
            // If it's a tombstone, the key was deleted
            if (TOMBSTONE.equals(value)) {
                return Optional.empty();
            }
            return Optional.of(value);
        }

        // Step 2: Check SSTables from newest to oldest
        for (SSTable sstable : sstables) {
            Optional<String> result = sstable.get(key);
            if (result.isPresent()) {
                // If it's a tombstone, the key was deleted
                if (TOMBSTONE.equals(result.get())) {
                    return Optional.empty();
                }
                return result;
            }
        }

        // Not found anywhere
        return Optional.empty();
    }

    @Override
    public void delete(String key) {
        // Step 1: Write DELETE to WAL FIRST
        if (wal != null) {
            try {
                wal.append(WALEntry.delete(key));
            } catch (IOException e) {
                throw new RuntimeException("Failed to write to WAL", e);
            }
        }

        // Step 2: Write a TOMBSTONE to MemTable (don't just remove!)
        // Why? If we just removed it from MemTable, a GET would check
        // the SSTables and find the old value there. The tombstone
        // blocks that — it says "this key is dead, stop looking."
        memTable.put(key, TOMBSTONE);

        // Step 3: Check if flush needed
        if (memTable.size() >= flushThreshold) {
            try {
                flushMemTable();
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush MemTable", e);
            }
        }
    }

    /**
     * Flush the current MemTable to disk as a new SSTable.
     *
     * WHAT HAPPENS:
     * 1. Write all MemTable entries to a sorted file (SSTable)
     * 2. Add the new SSTable to the FRONT of our list (it's the newest)
     * 3. Clear the MemTable (RAM freed!)
     * 4. Reset the WAL (those entries are now safely in the SSTable)
     */
    void flushMemTable() throws IOException {
        if (memTable.isEmpty()) {
            return;
        }

        sstableCounter++;
        String fileName = String.format("sstable_%04d.dat", sstableCounter);
        Path sstablePath = dataDir.resolve(fileName);

        // Write sorted data to file
        SSTable sstable = SSTable.flush(memTable, sstablePath);

        // Add to FRONT of list (newest first)
        sstables.add(0, sstable);

        // Clear MemTable — data is safely on disk now
        memTable.clear();

        // Reset WAL — those entries are now in the SSTable
        if (wal != null) {
            wal.reset();
        }

        log.info("Flushed MemTable to {} ({} entries)", fileName, sstable.size());

        // If there are too many SSTables, compact them into fewer
        if (sstables.size() >= compactionThreshold) {
            compactSSTables();
        }
    }

    /**
     * Merge ALL current SSTables into a single SSTable.
     *
     * WHAT HAPPENS:
     * 1. Take all existing SSTables
     * 2. Merge them into one (newer values win, tombstones removed)
     * 3. Delete the old files
     * 4. Replace our list with just the one merged SSTable
     */
    void compactSSTables() throws IOException {
        if (sstables.size() < 2) {
            return;
        }

        sstableCounter++;
        String fileName = String.format("sstable_%04d.dat", sstableCounter);
        Path outputPath = dataDir.resolve(fileName);

        // Compact all SSTables into one
        SSTable merged = Compaction.compact(sstables, outputPath);

        // Replace our list with just the merged SSTable
        sstables.clear();
        sstables.add(merged);
    }

    /**
     * On startup, load any existing SSTable files from the data directory.
     * Files are sorted by name (sstable_0001, 0002, ...) so newest is last.
     * We reverse them so newest is FIRST in our list.
     */
    private void loadExistingSSTables() throws IOException {
        if (dataDir == null || !java.nio.file.Files.exists(dataDir)) {
            return;
        }

        java.nio.file.Files.list(dataDir)
                .filter(p -> p.getFileName().toString().startsWith("sstable_"))
                .sorted()
                .forEach(path -> {
                    try {
                        sstables.add(new SSTable(path));
                        // Track the highest counter so new files don't overwrite old ones
                        String name = path.getFileName().toString();
                        int num = Integer.parseInt(
                                name.replace("sstable_", "").replace(".dat", "")
                        );
                        sstableCounter = Math.max(sstableCounter, num);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        // Reverse so newest SSTable is first
        Collections.reverse(sstables);

        if (!sstables.isEmpty()) {
            log.info("Loaded {} existing SSTables from {}", sstables.size(), dataDir);
        }
    }

    private void applyToMemTable(WALEntry entry) {
        switch (entry.getOperation()) {
            case PUT -> memTable.put(entry.getKey(), entry.getValue());
            case DELETE -> memTable.put(entry.getKey(), TOMBSTONE);
        }
    }

    public int memTableSize() {
        return memTable.size();
    }

    public int size() {
        return memTable.size();
    }

    public int sstableCount() {
        return sstables.size();
    }

    List<SSTable> getSstables() {
        return Collections.unmodifiableList(sstables);
    }

    public WriteAheadLog getWal() {
        return wal;
    }

    @Override
    public void close() throws IOException {
        if (wal != null) {
            wal.close();
        }
    }
}
