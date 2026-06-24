package com.distributeddb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the FULL flow: writing to MemTable, automatic flush to SSTable,
 * and reading data that spans both MemTable and SSTables.
 *
 * This is the integration test for Phase 1c.
 */
class FlushAndReadTest {

    @TempDir
    Path tempDir;

    @Test
    void autoFlushWhenThresholdReached() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        // Flush after just 3 keys (small for testing)
        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 3)) {
            storage.put("a", "1");
            storage.put("b", "2");
            assertEquals(0, storage.sstableCount());  // not flushed yet

            storage.put("c", "3");  // this is the 3rd key → triggers flush!
            assertEquals(1, storage.sstableCount());   // one SSTable now
            assertEquals(0, storage.memTableSize());   // MemTable was cleared
        }
    }

    @Test
    void readFromSSTableAfterFlush() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 3)) {
            // These 3 will be flushed to SSTable
            storage.put("apple", "red");
            storage.put("banana", "yellow");
            storage.put("cherry", "red");

            // apple is now in the SSTable, not the MemTable
            assertEquals("red", storage.get("apple").orElse(null));
            assertEquals("yellow", storage.get("banana").orElse(null));
        }
    }

    @Test
    void readSpansBothMemTableAndSSTable() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 3)) {
            // First batch → goes to SSTable after 3 keys
            storage.put("apple", "red");
            storage.put("banana", "yellow");
            storage.put("cherry", "red");

            // Second batch → still in MemTable
            storage.put("date", "brown");
            storage.put("elderberry", "purple");

            // apple is in SSTable, date is in MemTable — both should be found
            assertEquals("red", storage.get("apple").orElse(null));
            assertEquals("brown", storage.get("date").orElse(null));
        }
    }

    @Test
    void deleteBlocksSSTableRead() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 3)) {
            // Flush apple to SSTable
            storage.put("apple", "red");
            storage.put("banana", "yellow");
            storage.put("cherry", "red");
            // ^^^ flushed, apple is in SSTable now

            // Delete apple — tombstone goes into MemTable
            storage.delete("apple");

            // Even though apple is in the SSTable, the tombstone blocks it
            assertTrue(storage.get("apple").isEmpty());

            // Other keys still work
            assertEquals("yellow", storage.get("banana").orElse(null));
        }
    }

    @Test
    void newerSSTableWins() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 3)) {
            // First flush: apple=red
            storage.put("apple", "red");
            storage.put("b", "1");
            storage.put("c", "1");

            // Second flush: apple=GREEN (updated!)
            storage.put("apple", "GREEN");
            storage.put("d", "1");
            storage.put("e", "1");

            // Should return the newer value
            assertEquals("GREEN", storage.get("apple").orElse(null));
        }
    }

    @Test
    void multipleFlushes() throws IOException {
        Path walPath = tempDir.resolve("wal.log");
        Path dataDir = tempDir.resolve("sstables");

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, 2)) {
            storage.put("a", "1");
            storage.put("b", "2");  // flush 1
            storage.put("c", "3");
            storage.put("d", "4");  // flush 2
            storage.put("e", "5");
            storage.put("f", "6");  // flush 3

            assertEquals(3, storage.sstableCount());

            // All data should be readable across 3 SSTables
            assertEquals("1", storage.get("a").orElse(null));
            assertEquals("4", storage.get("d").orElse(null));
            assertEquals("6", storage.get("f").orElse(null));
        }
    }
}
