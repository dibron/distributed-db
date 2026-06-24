package com.distributeddb.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WALEntry — making sure we can convert entries to/from strings.
 *
 * This is important because:
 * - serialize() writes entries to the WAL file
 * - deserialize() reads them back on recovery
 * If these don't match perfectly, we lose data!
 */
class WALEntryTest {

    @Test
    void serializePutEntry() {
        // Create a PUT entry and convert to string
        WALEntry entry = WALEntry.fromFile(
                WALEntry.Operation.PUT, "apple", "red", 1000L
        );

        assertEquals("1000|PUT|apple|red", entry.serialize());
    }

    @Test
    void serializeDeleteEntry() {
        // DELETE entries have an empty value
        WALEntry entry = WALEntry.fromFile(
                WALEntry.Operation.DELETE, "apple", null, 2000L
        );

        assertEquals("2000|DELETE|apple|", entry.serialize());
    }

    @Test
    void deserializePutEntry() {
        // Read a string and turn it back into an entry
        WALEntry entry = WALEntry.deserialize("1000|PUT|banana|yellow");

        assertEquals(WALEntry.Operation.PUT, entry.getOperation());
        assertEquals("banana", entry.getKey());
        assertEquals("yellow", entry.getValue());
        assertEquals(1000L, entry.getTimestamp());
    }

    @Test
    void deserializeDeleteEntry() {
        WALEntry entry = WALEntry.deserialize("2000|DELETE|banana|");

        assertEquals(WALEntry.Operation.DELETE, entry.getOperation());
        assertEquals("banana", entry.getKey());
        assertNull(entry.getValue());
        assertEquals(2000L, entry.getTimestamp());
    }

    @Test
    void roundTrip() {
        // serialize then deserialize should give back the same data
        WALEntry original = WALEntry.put("hello", "world");
        String serialized = original.serialize();
        WALEntry recovered = WALEntry.deserialize(serialized);

        assertEquals(original.getOperation(), recovered.getOperation());
        assertEquals(original.getKey(), recovered.getKey());
        assertEquals(original.getValue(), recovered.getValue());
        assertEquals(original.getTimestamp(), recovered.getTimestamp());
    }
}
