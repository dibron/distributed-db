package com.distributeddb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SSTable — writing sorted data to disk and reading it back.
 */
class SSTableTest {

    @TempDir
    Path tempDir;

    @Test
    void flushAndRead() throws IOException {
        // Create some sorted data (TreeMap keeps keys sorted)
        TreeMap<String, String> data = new TreeMap<>();
        data.put("banana", "yellow");
        data.put("apple", "red");     // TreeMap sorts this to position 1
        data.put("cherry", "red");

        // Flush to disk
        Path path = tempDir.resolve("test.dat");
        SSTable sstable = SSTable.flush(data, path);

        // File should exist
        assertTrue(Files.exists(path));
        assertEquals(3, sstable.size());

        // Read keys back using binary search
        assertEquals("red", sstable.get("apple").orElse(null));
        assertEquals("yellow", sstable.get("banana").orElse(null));
        assertEquals("red", sstable.get("cherry").orElse(null));

        // Missing key
        assertTrue(sstable.get("dragonfruit").isEmpty());
    }

    @Test
    void fileIsSorted() throws IOException {
        TreeMap<String, String> data = new TreeMap<>();
        data.put("cherry", "3");
        data.put("apple", "1");
        data.put("banana", "2");

        Path path = tempDir.resolve("sorted.dat");
        SSTable.flush(data, path);

        // Read raw file — lines should be in alphabetical order
        String content = Files.readString(path);
        String[] lines = content.trim().split("\n");
        assertEquals("apple|1", lines[0].trim());
        assertEquals("banana|2", lines[1].trim());
        assertEquals("cherry|3", lines[2].trim());
    }

    @Test
    void loadExistingSSTable() throws IOException {
        TreeMap<String, String> data = new TreeMap<>();
        data.put("x", "1");
        data.put("y", "2");

        Path path = tempDir.resolve("existing.dat");
        SSTable.flush(data, path);

        // Load it as if we're restarting — create a NEW SSTable from the same file
        SSTable loaded = new SSTable(path);
        assertEquals("1", loaded.get("x").orElse(null));
        assertEquals("2", loaded.get("y").orElse(null));
        assertEquals(2, loaded.size());
    }

    @Test
    void deleteRemovesFile() throws IOException {
        TreeMap<String, String> data = new TreeMap<>();
        data.put("key", "val");

        Path path = tempDir.resolve("deleteme.dat");
        SSTable sstable = SSTable.flush(data, path);
        assertTrue(Files.exists(path));

        sstable.delete();
        assertFalse(Files.exists(path));
    }
}
