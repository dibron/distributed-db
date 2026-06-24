package com.distributeddb.storage;

import java.util.BitSet;

/**
 * Bloom Filter — a probabilistic "membership test" data structure.
 *
 * WHAT IT DOES:
 *   - add("apple")                 → remembers that "apple" was added
 *   - mightContain("apple")        → returns true (it might be here)
 *   - mightContain("mango")        → returns false (DEFINITELY not here)
 *
 * HOW IT WORKS:
 *   Internally it's just an array of bits (0s and 1s).
 *
 *   Adding a key:
 *     1. Hash the key with multiple different hash functions
 *     2. Each hash gives a position in the bit array
 *     3. Set those positions to 1
 *
 *   Checking a key:
 *     1. Hash the key with the same hash functions
 *     2. Check if ALL those positions are 1
 *     3. If ANY position is 0 → the key was NEVER added (100% certain)
 *     4. If ALL positions are 1 → the key MIGHT have been added (not 100% certain)
 *
 * WHY NOT 100% CERTAIN?
 *   Because different keys can hash to the same positions.
 *   "apple" might set bits 2,5,7. "banana" might set bits 1,3,5.
 *   Now bits 1,2,3,5,7 are all 1. If "mango" hashes to bits 1,2,3 —
 *   all bits are 1, so we say "maybe" — even though mango was never added.
 *
 * WHY USE IT?
 *   It's tiny (a few KB) and the check is O(1) — constant time.
 *   Checking an SSTable with binary search is O(log n) and reads from disk.
 *   The Bloom filter lets us SKIP SSTables that definitely don't have the key.
 *
 * PARAMETERS:
 *   - expectedSize: how many keys we expect to add
 *   - hashCount: how many hash functions to use (more = fewer false positives, but slower)
 *   - bitSetSize: how big the bit array is (bigger = fewer false positives, but more memory)
 */
public class BloomFilter {

    private final BitSet bitSet;       // the array of bits (0s and 1s)
    private final int bitSetSize;      // how many bits in the array
    private final int hashCount;       // how many hash functions to use

    /**
     * Create a Bloom filter sized for the expected number of keys.
     *
     * The formula for optimal size comes from probability theory:
     *   bitSetSize = -expectedSize * ln(falsePositiveRate) / (ln2)^2
     *   hashCount = (bitSetSize / expectedSize) * ln2
     *
     * With falsePositiveRate = 1% (0.01), this gives about 10 bits per key
     * and 7 hash functions. That means each key only uses ~10 bits of memory!
     *
     * 1000 keys → ~10,000 bits → ~1.2 KB. Tiny!
     */
    public BloomFilter(int expectedSize) {
        // Target 1% false positive rate
        this.bitSetSize = optimalBitSetSize(expectedSize, 0.01);
        this.hashCount = optimalHashCount(expectedSize, bitSetSize);
        this.bitSet = new BitSet(bitSetSize);
    }

    BloomFilter(int bitSetSize, int hashCount) {
        this.bitSetSize = bitSetSize;
        this.hashCount = hashCount;
        this.bitSet = new BitSet(bitSetSize);
    }

    /**
     * Add a key to the Bloom filter.
     *
     * Hashes the key multiple times and sets those bit positions to 1.
     */
    public void add(String key) {
        for (int i = 0; i < hashCount; i++) {
            int position = getHash(key, i);
            bitSet.set(position);  // set bit to 1
        }
    }

    /**
     * Check if a key MIGHT be in this set.
     *
     * Returns:
     *   false → the key is DEFINITELY NOT in the set (100% certain, safe to skip)
     *   true  → the key MIGHT be in the set (not 100% certain, must verify)
     */
    public boolean mightContain(String key) {
        for (int i = 0; i < hashCount; i++) {
            int position = getHash(key, i);
            if (!bitSet.get(position)) {
                // This bit is 0 → the key was NEVER added
                return false;
            }
        }
        // All bits are 1 → MAYBE (could be a false positive)
        return true;
    }

    /**
     * Generate the i-th hash for a key.
     *
     * We use "double hashing" — a trick where we compute just TWO base hashes
     * and combine them to create as many hash functions as we need:
     *   hash_i = (hash1 + i * hash2) % bitSetSize
     *
     * This is faster than running N completely different hash functions,
     * and mathematically gives equally good results.
     */
    private int getHash(String key, int i) {
        int hash1 = key.hashCode();
        int hash2 = fnvHash(key);
        int combined = hash1 + (i * hash2);
        // Math.floorMod handles negative numbers correctly (% can return negative in Java)
        return Math.floorMod(combined, bitSetSize);
    }

    /**
     * FNV-1a hash — a simple, fast hash function.
     * We use this as our second base hash (different from Java's hashCode).
     */
    private int fnvHash(String key) {
        int hash = 0x811c9dc5;  // FNV offset basis
        for (int j = 0; j < key.length(); j++) {
            hash ^= key.charAt(j);
            hash *= 0x01000193; // FNV prime
        }
        return hash;
    }

    private static int optimalBitSetSize(int expectedSize, double falsePositiveRate) {
        return (int) (-expectedSize * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
    }

    private static int optimalHashCount(int expectedSize, int bitSetSize) {
        return Math.max(1, (int) Math.round((double) bitSetSize / expectedSize * Math.log(2)));
    }
}
