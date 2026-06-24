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
        int flushThreshold = 5;  // flush after 5 keys (small for demo purposes)

        try (MemTableStorage storage = new MemTableStorage(walPath, dataDir, flushThreshold)) {

            // Write some data — after 5 keys, the MemTable will flush to an SSTable!
            storage.put("apple", "red");
            storage.put("banana", "yellow");
            storage.put("cherry", "red");
            storage.put("date", "brown");
            log.info("MemTable size before flush: {}", storage.memTableSize());

            storage.put("elderberry", "purple");
            // ^^^ This triggers the flush! MemTable had 5 entries → flushed to SSTable
            log.info("MemTable size after flush: {}", storage.memTableSize());
            log.info("SSTables on disk: {}", storage.sstableCount());

            // Write more data (goes into the NEW empty MemTable)
            storage.put("fig", "green");
            storage.put("grape", "purple");

            // Read data — some from MemTable, some from SSTable
            log.info("GET 'apple' = {} (from SSTable)", storage.get("apple").orElse("<not found>"));
            log.info("GET 'fig' = {} (from MemTable)", storage.get("fig").orElse("<not found>"));

            // Delete a key that's in the SSTable
            storage.delete("apple");
            log.info("GET 'apple' after delete = {}", storage.get("apple").orElse("<not found>"));

            log.info("Distributed DB stopped.");
        }
    }
}
