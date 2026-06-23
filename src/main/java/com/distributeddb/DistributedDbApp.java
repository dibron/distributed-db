package com.distributeddb;

import com.distributeddb.storage.MemTableStorage;
import com.distributeddb.storage.StorageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistributedDbApp {

    private static final Logger log = LoggerFactory.getLogger(DistributedDbApp.class);

    public static void main(String[] args) {
        log.info("Starting Distributed DB...");

        StorageEngine storage = new MemTableStorage();

        storage.put("hello", "world");
        storage.put("distributed", "database");

        log.info("GET 'hello' = {}", storage.get("hello").orElse("<not found>"));
        log.info("GET 'distributed' = {}", storage.get("distributed").orElse("<not found>"));

        storage.delete("hello");
        log.info("GET 'hello' after delete = {}", storage.get("hello").orElse("<not found>"));

        log.info("Distributed DB stopped.");
    }
}
