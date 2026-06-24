package com.distributeddb.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MemTableStorage WITHOUT WAL.
 * These test the basic in-memory behavior.
 */
class MemTableStorageTest {

    private MemTableStorage storage;

    @BeforeEach
    void setUp() {
        // No WAL — just testing the in-memory map
        storage = new MemTableStorage();
    }

    @Test
    void putAndGet() {
        storage.put("key1", "value1");
        assertEquals("value1", storage.get("key1").orElse(null));
    }

    @Test
    void getMissing() {
        assertTrue(storage.get("nonexistent").isEmpty());
    }

    @Test
    void deleteRemovesKey() {
        storage.put("key1", "value1");
        storage.delete("key1");
        assertTrue(storage.get("key1").isEmpty());
    }

    @Test
    void putOverwrites() {
        storage.put("key1", "v1");
        storage.put("key1", "v2");
        assertEquals("v2", storage.get("key1").orElse(null));
    }

    @Test
    void sizeReflectsEntries() {
        assertEquals(0, storage.size());
        storage.put("a", "1");
        storage.put("b", "2");
        assertEquals(2, storage.size());
        storage.delete("a");
        assertEquals(1, storage.size());
    }
}
