package com.distributeddb.storage;

/**
 * One line in the Write-Ahead Log file.
 *
 * Think of it like a notebook entry:
 *   "PUT apple red"     → means "store apple with value red"
 *   "DELETE apple"      → means "remove apple"
 *
 * We record WHAT happened (operation), to WHICH key, and WHAT value (if any).
 * We also record WHEN it happened (timestamp) so entries are ordered.
 */
public class WALEntry {

    // The two things you can do to a key: PUT a value, or DELETE it
    public enum Operation {
        PUT,
        DELETE
    }

    private final Operation operation;  // PUT or DELETE
    private final String key;           // which key (e.g., "apple")
    private final String value;         // what value (e.g., "red") — null for DELETE
    private final long timestamp;       // when this happened (milliseconds)

    // Constructor for PUT — you need a key AND a value
    public static WALEntry put(String key, String value) {
        return new WALEntry(Operation.PUT, key, value, System.currentTimeMillis());
    }

    // Constructor for DELETE — you only need the key
    public static WALEntry delete(String key) {
        return new WALEntry(Operation.DELETE, key, null, System.currentTimeMillis());
    }

    // Used when replaying from file — we already know the timestamp
    public static WALEntry fromFile(Operation operation, String key, String value, long timestamp) {
        return new WALEntry(operation, key, value, timestamp);
    }

    private WALEntry(Operation operation, String key, String value, long timestamp) {
        this.operation = operation;
        this.key = key;
        this.value = value;
        this.timestamp = timestamp;
    }

    // --- Getters ---

    public Operation getOperation() {
        return operation;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Convert this entry to a string that we can write to a file.
     *
     * Format: "timestamp|OPERATION|key|value"
     * Example: "1719532800000|PUT|apple|red"
     *          "1719532800001|DELETE|cherry|"
     *
     * We use | as a separator because keys/values won't contain it.
     */
    public String serialize() {
        String val = (value == null) ? "" : value;
        return timestamp + "|" + operation.name() + "|" + key + "|" + val;
    }

    /**
     * Read a string from the file and turn it back into a WALEntry.
     *
     * This is the reverse of serialize().
     * "1719532800000|PUT|apple|red" → WALEntry(PUT, "apple", "red", 1719532800000)
     */
    public static WALEntry deserialize(String line) {
        String[] parts = line.split("\\|", 4);  // split into exactly 4 parts
        // parts[0] = timestamp, parts[1] = operation, parts[2] = key, parts[3] = value

        long ts = Long.parseLong(parts[0]);
        Operation op = Operation.valueOf(parts[1]);
        String k = parts[2];
        String v = parts[3].isEmpty() ? null : parts[3];

        return WALEntry.fromFile(op, k, v, ts);
    }
}
