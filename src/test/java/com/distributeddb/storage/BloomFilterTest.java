package com.distributeddb.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Bloom filter.
 *
 * Key things to verify:
 * 1. Added keys always return true (no false negatives — EVER)
 * 2. Non-added keys usually return false (but occasional false positives are OK)
 */
class BloomFilterTest {

    @Test
    void addedKeysAreAlwaysFound() {
        BloomFilter filter = new BloomFilter(100);

        filter.add("apple");
        filter.add("banana");
        filter.add("cherry");

        // These MUST return true — false negatives are not allowed
        assertTrue(filter.mightContain("apple"));
        assertTrue(filter.mightContain("banana"));
        assertTrue(filter.mightContain("cherry"));
    }

    @Test
    void nonAddedKeysUsuallyNotFound() {
        BloomFilter filter = new BloomFilter(100);

        filter.add("apple");
        filter.add("banana");
        filter.add("cherry");

        // These should usually return false
        // (very small chance of false positive with good settings)
        assertFalse(filter.mightContain("dragonfruit"));
        assertFalse(filter.mightContain("elderberry"));
        assertFalse(filter.mightContain("fig"));
    }

    @Test
    void emptyFilterReturnsfalse() {
        BloomFilter filter = new BloomFilter(10);

        // Nothing added — everything should be "definitely not"
        assertFalse(filter.mightContain("anything"));
        assertFalse(filter.mightContain("at all"));
    }

    @Test
    void lowFalsePositiveRate() {
        // Add 1000 keys, then check 1000 keys that were NOT added.
        // False positive rate should be around 1% (our target).
        BloomFilter filter = new BloomFilter(1000);

        for (int i = 0; i < 1000; i++) {
            filter.add("key_" + i);
        }

        // Verify all added keys are found (ZERO false negatives)
        for (int i = 0; i < 1000; i++) {
            assertTrue(filter.mightContain("key_" + i),
                    "False negative for key_" + i + "! This should NEVER happen.");
        }

        // Count false positives for keys that were never added
        int falsePositives = 0;
        int testCount = 10000;
        for (int i = 0; i < testCount; i++) {
            if (filter.mightContain("nonexistent_" + i)) {
                falsePositives++;
            }
        }

        double rate = (double) falsePositives / testCount;
        // Rate should be around 1%. Allow up to 3% to avoid flaky tests.
        assertTrue(rate < 0.03,
                "False positive rate too high: " + (rate * 100) + "%");
    }

    @Test
    void worksWithSmallFilter() {
        // Even a tiny filter with just a few bits should work (no crashes)
        BloomFilter filter = new BloomFilter(5, 2);
        filter.add("hello");
        assertTrue(filter.mightContain("hello"));
    }
}
