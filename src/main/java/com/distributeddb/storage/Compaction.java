package com.distributeddb.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Compaction — merges multiple SSTables into one clean SSTable.
 *
 * WHY:
 *   1. Too many SSTables = slow reads (must check each one)
 *   2. Old values waste disk space (apple=red when apple is now GREEN)
 *   3. Tombstones waste disk space (banana=TOMBSTONE can be removed)
 *
 * HOW:
 *   1. Read ALL entries from all the SSTables being compacted
 *   2. For duplicate keys, keep only the NEWEST value
 *   3. Remove tombstones (deleted keys)
 *   4. Write the clean, merged data to a new SSTable file
 *   5. Delete the old SSTable files
 *
 * EXAMPLE:
 *   SSTable 1 (older): apple=red,   banana=yellow, cherry=red
 *   SSTable 2 (newer): apple=GREEN, banana=TOMBSTONE, date=brown
 *
 *   After compaction:
 *   Merged SSTable:    apple=GREEN, cherry=red, date=brown
 *
 *   - apple: newer value (GREEN) wins over old (red)
 *   - banana: tombstone means deleted → removed entirely
 *   - cherry: only in SSTable 1, kept as-is
 *   - date: only in SSTable 2, kept as-is
 */
public class Compaction {

    private static final Logger log = LoggerFactory.getLogger(Compaction.class);

    /**
     * Merge multiple SSTables into one.
     *
     * @param sstables   the SSTables to merge (ordered NEWEST first)
     * @param outputPath where to write the merged SSTable
     * @return the new merged SSTable
     */
    public static SSTable compact(List<SSTable> sstables, Path outputPath) throws IOException {
        // TreeMap keeps keys sorted — exactly what we need for an SSTable
        TreeMap<String, String> merged = new TreeMap<>();

        // Iterate OLDEST first, so newer values overwrite older ones
        // (sstables list is newest-first, so we iterate in reverse)
        for (int i = sstables.size() - 1; i >= 0; i--) {
            SortedMap<String, String> entries = sstables.get(i).allEntries();
            merged.putAll(entries);
            // putAll overwrites existing keys — since we go oldest→newest,
            // the final value for each key is the NEWEST one. Correct!
        }

        // Remove tombstones — deleted keys don't need to be in the merged file
        merged.values().removeIf(value -> MemTableStorage.TOMBSTONE.equals(value));

        int beforeSize = sstables.stream().mapToInt(SSTable::size).sum();

        // Write the clean merged data to a new SSTable file
        SSTable result = SSTable.flush(merged, outputPath);

        // Delete the old SSTable files — their data is now in the merged file
        for (SSTable old : sstables) {
            old.delete();
        }

        log.info("Compacted {} SSTables ({} entries) → 1 SSTable ({} entries)",
                sstables.size(), beforeSize, result.size());

        return result;
    }
}
