package com.distributeddb.storage;

import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

public class MemTableStorage implements StorageEngine {

    private final ConcurrentSkipListMap<String, String> memTable = new ConcurrentSkipListMap<>();

    @Override
    public void put(String key, String value) {
        memTable.put(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(memTable.get(key));
    }

    @Override
    public void delete(String key) {
        memTable.remove(key);
    }
}
