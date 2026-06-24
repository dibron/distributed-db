package com.distributeddb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE MOST IMPORTANT TEST — simulates a crash and recovery.
 *
 * Now tests recovery with BOTH WAL and SSTables:
 * - Data in SSTables is already on disk (no recovery needed)
 * - Data in the MemTable is recovered from the WAL
 */
class CrashRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void dataRecoveredAfterCrash() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        // === BEFORE CRASH ===
        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 1000)) {
            storage.put("user:1", "Alice");
            storage.put("user:2", "Bob");
            storage.put("user:3", "Charlie");
            storage.delete("user:2");
        }
        // RAM is GONE!

        // === AFTER CRASH — RESTART ===
        try (MemTableStorage recovered = new MemTableStorage(walPath, dataDir, 1000)) {
            assertEquals("Alice", recovered.get("user:1").orElse(null));
            assertEquals("Charlie", recovered.get("user:3").orElse(null));
            assertTrue(recovered.get("user:2").isEmpty());
        }
    }

    @Test
    void recoveryWithSSTables() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        // Write enough data to trigger a flush, then write more
        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 3)) {
            // These 3 go to SSTable (flushed)
            storage.put("a", "1");
            storage.put("b", "2");
            storage.put("c", "3");

            // These 2 are still in MemTable (in WAL on disk)
            storage.put("d", "4");
            storage.put("e", "5");
        }
        // RAM is GONE! But SSTable has a,b,c and WAL has d,e

        // === RESTART ===
        try (MemTableStorage recovered = new MemTableStorage(walPath, dataDir, 3)) {
            // a,b,c come from SSTable (already on disk)
            assertEquals("1", recovered.get("a").orElse(null));
            assertEquals("2", recovered.get("b").orElse(null));
            assertEquals("3", recovered.get("c").orElse(null));

            // d,e come from WAL replay
            assertEquals("4", recovered.get("d").orElse(null));
            assertEquals("5", recovered.get("e").orElse(null));
        }
    }

    @Test
    void emptyRecovery() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 1000)) {
            assertEquals(0, storage.size());
            assertTrue(storage.get("anything").isEmpty());
        }
    }

    @Test
    void multipleUpdatesToSameKey() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 1000)) {
            storage.put("score", "10");
            storage.put("score", "20");
            storage.put("score", "30");
        }

        try (MemTableStorage recovered = new MemTableStorage(walPath, dataDir, 1000)) {
            assertEquals("30", recovered.get("score").orElse(null));
        }
    }
}
