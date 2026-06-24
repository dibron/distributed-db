package com.distributeddb.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WriteAheadLog — making sure entries survive restarts.
 *
 * Key thing we're testing: if we write entries and then create a NEW WAL
 * instance pointing to the same file, can we replay and get everything back?
 * This simulates what happens after a crash + restart.
 *
 * We use @TempDir so each test gets a fresh temporary folder that's
 * automatically cleaned up after the test finishes.
 */
class WriteAheadLogTest {

    @TempDir
    Path tempDir;    // JUnit creates a temp folder for us

    private Path walPath;

    @BeforeEach
    void setUp() {
        walPath = tempDir.resolve("test-wal.log");
    }

    @Test
    void appendAndReplay() throws IOException {
        // Write some entries
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.append(WALEntry.put("apple", "red"));
            wal.append(WALEntry.put("banana", "yellow"));
            wal.append(WALEntry.delete("apple"));
        }

        // Simulate restart — create a NEW WAL instance and replay
        try (WriteAheadLog wal2 = new WriteAheadLog(walPath)) {
            List<WALEntry> entries = wal2.replay();

            assertEquals(3, entries.size());

            // First entry: PUT apple=red
            assertEquals(WALEntry.Operation.PUT, entries.get(0).getOperation());
            assertEquals("apple", entries.get(0).getKey());
            assertEquals("red", entries.get(0).getValue());

            // Second entry: PUT banana=yellow
            assertEquals(WALEntry.Operation.PUT, entries.get(1).getOperation());
            assertEquals("banana", entries.get(1).getKey());

            // Third entry: DELETE apple
            assertEquals(WALEntry.Operation.DELETE, entries.get(2).getOperation());
            assertEquals("apple", entries.get(2).getKey());
        }
    }

    @Test
    void replayEmptyFile() throws IOException {
        // Brand new WAL — no file exists yet
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            List<WALEntry> entries = wal.replay();
            assertTrue(entries.isEmpty());
        }
    }

    @Test
    void resetClearsLog() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.append(WALEntry.put("key1", "val1"));
            wal.append(WALEntry.put("key2", "val2"));

            // Reset should clear everything
            wal.reset();

            List<WALEntry> entries = wal.replay();
            assertTrue(entries.isEmpty());
        }
    }

    @Test
    void walFileActuallyExistsOnDisk() throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(walPath)) {
            wal.append(WALEntry.put("test", "data"));

            // The file should physically exist
            assertTrue(Files.exists(walPath));

            // And contain the serialized entry
            String content = Files.readString(walPath);
            assertTrue(content.contains("PUT"));
            assertTrue(content.contains("test"));
            assertTrue(content.contains("data"));
        }
    }
}
