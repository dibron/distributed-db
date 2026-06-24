package com.distributeddb;

import com.distributeddb.storage.MemTableStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class DistributedDbApp {

    private static final Logger log = LoggerFactory.getLogger(DistributedDbApp.class);

    public static void main(String[] args) throws IOException {
        log.info("Starting Distributed DB...");

        Path walPath = Path.of("data", "wal", "wal.log");
        Path dataDir = Path.of("data", "sstables");
        int flushThreshold = 3;       // flush after 3 keys (small for demo)
        int compactionThreshold = 3;  // compact after 3 SSTables

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, flushThreshold, compactionThreshold)) {

            // Flush 1: 3 keys → SSTable 1
            storage.put("apple", "red");
            storage.put("banana", "yellow");
            storage.put("cherry", "red");
            log.info("After flush 1: {} SSTables", storage.sstableCount());

            // Flush 2: 3 keys → SSTable 2
            storage.put("date", "brown");
            storage.put("elderberry", "purple");
            storage.put("fig", "green");
            log.info("After flush 2: {} SSTables", storage.sstableCount());

            // Flush 3: 3 keys → SSTable 3 → triggers compaction!
            storage.put("grape", "purple");
            storage.put("honeydew", "green");
            storage.put("apple", "GREEN");  // update apple!
            log.info("After flush 3 + compaction: {} SSTables", storage.sstableCount());

            // All data readable from the one compacted SSTable
            log.info("GET 'apple' = {} (was red, updated to GREEN)", storage.get("apple").orElse("<not found>"));
            log.info("GET 'banana' = {}", storage.get("banana").orElse("<not found>"));
            log.info("GET 'grape' = {}", storage.get("grape").orElse("<not found>"));

            log.info("Distributed DB stopped.");
        }
    }
}
