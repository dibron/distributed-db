package com.distributeddb.storage;

import java.util.Optional;

public interface StorageEngine {

    void put(String key, String value);

    Optional<String> get(String key);

    void delete(String key);
}
