package dev.dizabanik.realisticsmoke;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

public final class SmokeSourceAdmission {
    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    private long[] keys = new long[0];
    private float[] strengths = new float[0];
    private long[] ranks = new long[0];
    private int capacity;
    private int size;
    private long epochSalt;

    public SmokeSourceAdmission() {
        index.defaultReturnValue(-1);
    }

    public void reset(int capacity, long epoch) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be nonnegative");
        }
        this.capacity = capacity;
        size = 0;
        index.clear();
        epochSalt = mix64(epoch + 0x9E3779B97F4A7C15L);
        if (keys.length < capacity) {
            keys = new long[capacity];
            strengths = new float[capacity];
            ranks = new long[capacity];
        }
    }

    public void offer(long pocketKey, float strength) {
        if (capacity == 0) {
            return;
        }
        int slot = index.get(pocketKey);
        if (slot >= 0) {
            if (Float.compare(strength, strengths[slot]) > 0) {
                siftDown(slot, pocketKey, strength, ranks[slot]);
            }
            return;
        }
        long rank = mix64(pocketKey ^ epochSalt);
        if (size < capacity) {
            slot = size++;
            while (slot > 0) {
                int parent = (slot - 1) >>> 1;
                if (!weaker(strength, rank, strengths[parent], ranks[parent])) {
                    break;
                }
                put(slot, keys[parent], strengths[parent], ranks[parent]);
                slot = parent;
            }
            put(slot, pocketKey, strength, rank);
        } else if (weaker(strengths[0], ranks[0], strength, rank)) {
            index.remove(keys[0]);
            siftDown(0, pocketKey, strength, rank);
        }
    }

    public boolean contains(long key) {
        return index.get(key) >= 0;
    }

    public int size() {
        return size;
    }

    private void siftDown(int slot, long key, float strength, long rank) {
        while (slot < size / 2) {
            int child = slot * 2 + 1;
            int right = child + 1;
            if (
                right < size &&
                weaker(strengths[right], ranks[right], strengths[child], ranks[child])
            ) {
                child = right;
            }
            if (!weaker(strengths[child], ranks[child], strength, rank)) {
                break;
            }
            put(slot, keys[child], strengths[child], ranks[child]);
            slot = child;
        }
        put(slot, key, strength, rank);
    }

    private void put(int slot, long key, float strength, long rank) {
        keys[slot] = key;
        strengths[slot] = strength;
        ranks[slot] = rank;
        index.put(key, slot);
    }

    private static boolean weaker(float strength, long rank, float otherStrength, long otherRank) {
        int comparison = Float.compare(strength, otherStrength);
        return comparison < 0 ||
            (comparison == 0 && Long.compareUnsigned(rank, otherRank) < 0);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
