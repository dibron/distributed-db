package com.distributeddb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for compaction — merging SSTables.
 */
class CompactionTest {

    @TempDir
    Path tempDir;

    @Test
    void mergesTwoSSTables() throws IOException {
        // SSTable 1 (older): apple=red, banana=yellow
        TreeMap<String, String> data1 = new TreeMap<>();
        data1.put("apple", "red");
        data1.put("banana", "yellow");
        SSTable sst1 = SSTable.flush(data1, tempDir.resolve("sst1.dat"));

        // SSTable 2 (newer): apple=GREEN, cherry=red
        TreeMap<String, String> data2 = new TreeMap<>();
        data2.put("apple", "GREEN");
        data2.put("cherry", "red");
        SSTable sst2 = SSTable.flush(data2, tempDir.resolve("sst2.dat"));

        // Compact (newest first in the list)
        SSTable merged = Compaction.compact(
                List.of(sst2, sst1),
                tempDir.resolve("merged.dat")
        );

        // apple: newer value (GREEN) should win
        assertEquals("GREEN", merged.get("apple").orElse(null));
        // banana: only in sst1, kept
        assertEquals("yellow", merged.get("banana").orElse(null));
        // cherry: only in sst2, kept
        assertEquals("red", merged.get("cherry").orElse(null));

        assertEquals(3, merged.size());
    }

    @Test
    void tombstonesRemovedDuringCompaction() throws IOException {
        // SSTable 1: apple=red, banana=yellow
        TreeMap<String, String> data1 = new TreeMap<>();
        data1.put("apple", "red");
        data1.put("banana", "yellow");
        SSTable sst1 = SSTable.flush(data1, tempDir.resolve("sst1.dat"));

        // SSTable 2: apple=TOMBSTONE (deleted!)
        TreeMap<String, String> data2 = new TreeMap<>();
        data2.put("apple", MemTableStorage.TOMBSTONE);
        SSTable sst2 = SSTable.flush(data2, tempDir.resolve("sst2.dat"));

        SSTable merged = Compaction.compact(
                List.of(sst2, sst1),
                tempDir.resolve("merged.dat")
        );

        // apple was deleted — should be completely gone
        assertTrue(merged.get("apple").isEmpty());
        // banana survives
        assertEquals("yellow", merged.get("banana").orElse(null));

        // Only 1 entry (banana), not 2
        assertEquals(1, merged.size());
    }

    @Test
    void oldFilesDeletedAfterCompaction() throws IOException {
        TreeMap<String, String> data = new TreeMap<>();
        data.put("a", "1");
        SSTable sst1 = SSTable.flush(data, tempDir.resolve("sst1.dat"));
        SSTable sst2 = SSTable.flush(data, tempDir.resolve("sst2.dat"));

        Path sst1Path = sst1.getFilePath();
        Path sst2Path = sst2.getFilePath();

        Compaction.compact(List.of(sst2, sst1), tempDir.resolve("merged.dat"));

        // Old files should be deleted
        assertFalse(java.nio.file.Files.exists(sst1Path));
        assertFalse(java.nio.file.Files.exists(sst2Path));
    }

    @Test
    void autoCompactionInStorage() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        // flushThreshold=2 (flush every 2 keys), compactionThreshold=3 (compact after 3 SSTables)
        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 2, 3)) {
            // Flush 1: keys a, b
            storage.put("a", "1");
            storage.put("b", "2");
            assertEquals(1, storage.sstableCount());

            // Flush 2: keys c, d
            storage.put("c", "3");
            storage.put("d", "4");
            assertEquals(2, storage.sstableCount());

            // Flush 3: keys e, f → triggers compaction (3 >= 3)
            storage.put("e", "5");
            storage.put("f", "6");
            assertEquals(1, storage.sstableCount());  // compacted down to 1!

            // All data should still be readable
            assertEquals("1", storage.get("a").orElse(null));
            assertEquals("3", storage.get("c").orElse(null));
            assertEquals("5", storage.get("e").orElse(null));
        }
    }

    @Test
    void compactionPreservesNewerValues() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 2, 3)) {
            // Flush 1: apple=red
            storage.put("apple", "red");
            storage.put("x", "1");

            // Flush 2: apple=GREEN (update!)
            storage.put("apple", "GREEN");
            storage.put("y", "1");

            // Flush 3: triggers compaction
            storage.put("z", "1");
            storage.put("w", "1");

            // After compaction, apple should be GREEN (the newer value)
            assertEquals("GREEN", storage.get("apple").orElse(null));
        }
    }
}
