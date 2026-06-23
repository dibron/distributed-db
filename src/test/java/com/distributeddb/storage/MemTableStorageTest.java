package com.distributeddb.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemTableStorageTest {

    private MemTableStorage storage;

    @BeforeEach
    void setUp() {
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
}
