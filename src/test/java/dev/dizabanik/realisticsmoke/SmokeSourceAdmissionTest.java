package dev.dizabanik.realisticsmoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class SmokeSourceAdmissionTest {
    public static void main(String[] args) {
        checkCapacityAndReset();
        checkUpdates();
        checkScanOrder();
        checkRotatingSelection();
        checkRandomReference();
        System.out.println(
                "SmokeSourceAdmissionTest passed: unique capacity, max updates, scan order, epoch rotation, and randomized reference top-K.");
    }

    private static void checkCapacityAndReset() {
        SmokeSourceAdmission admission = new SmokeSourceAdmission();
        check(admission.size() == 0 && !admission.contains(0), "initially empty");
        admission.offer(0, 1);
        check(admission.size() == 0, "no capacity before reset");
        admission.reset(3, 1);
        long[] keys = { 0, Long.MIN_VALUE, Long.MAX_VALUE, -1, 42 };
        for (int i = 0; i < keys.length; i++) {
            admission.offer(keys[i], i + 1);
            check(admission.size() == Math.min(i + 1, 3), "strict unique capacity");
        }
        for (int i = 0; i < keys.length; i++) {
            check(admission.contains(keys[i]) == (i >= 2), "eviction removes index entry");
        }
        admission.reset(1, Long.MIN_VALUE);
        for (long key : keys) {
            check(!admission.contains(key), "reset clears index");
        }
        admission.offer(0, 2);
        admission.offer(42, 3);
        check(admission.size() == 1 && admission.contains(42), "smaller reused capacity");
        admission.reset(0, 7);
        admission.offer(0, Float.MAX_VALUE);
        check(admission.size() == 0 && !admission.contains(42), "zero capacity reset");
        admission.reset(8, 8);
        for (long key : keys) {
            admission.offer(key, 1);
        }
        check(admission.size() == keys.length, "capacity grows after zero reset");
        try {
            admission.reset(-1, 0);
            throw new AssertionError("negative capacity must be rejected");
        } catch (IllegalArgumentException expected) {
            check(admission.size() == keys.length, "invalid reset leaves selection intact");
        }
    }

    private static void checkUpdates() {
        SmokeSourceAdmission admission = new SmokeSourceAdmission();
        admission.reset(3, 0);
        admission.offer(10, 1);
        admission.offer(20, 2);
        admission.offer(30, 3);
        admission.offer(10, 100);
        admission.offer(40, 4);
        check(admission.contains(10) && !admission.contains(20), "root increase sifts down");
        admission.offer(30, 200);
        admission.offer(50, 5);
        check(admission.contains(30) && !admission.contains(40), "indexed non-root update");
        admission.offer(10, 1);
        admission.offer(10, 100);
        admission.offer(60, 99);
        check(admission.contains(10) && !admission.contains(50), "updates never decrease score");
        admission.offer(20, 300);
        check(admission.contains(20) && !admission.contains(60), "evicted key can return");
        check(admission.size() == 3, "updates preserve unique size");

        admission.reset(1, 0);
        admission.offer(1, 4);
        for (int i = 0; i < 100; i++) {
            admission.offer(1, 4);
        }
        admission.offer(2, 5);
        check(admission.contains(2) && !admission.contains(1), "duplicates use max, never sum");
        admission.offer(2, 8);
        admission.offer(3, 7);
        check(admission.contains(2) && admission.size() == 1, "single-slot update");
    }

    private static void checkScanOrder() {
        List<Offer> offers = new ArrayList<>();
        Map<Long, Float> maxima = new HashMap<>();
        for (long key = -40; key <= 40; key++) {
            offers.add(new Offer(key, 1));
            offers.add(new Offer(key, 3));
            offers.add(new Offer(key, 2));
            maxima.put(key, 3.0f);
        }
        Random random = new Random(0x51CA0DEL);
        SmokeSourceAdmission admission = new SmokeSourceAdmission();
        for (long epoch : new long[] { 0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE }) {
            Set<Long> expected = topK(maxima, 13, epoch);
            for (int pass = 0; pass < 30; pass++) {
                Collections.shuffle(offers, random);
                admission.reset(13, epoch);
                for (Offer offer : offers) {
                    admission.offer(offer.key(), offer.strength());
                    check(admission.size() <= 13, "scan stays bounded");
                }
                checkSelection(admission, maxima, expected);
            }
        }
    }

    private static void checkRotatingSelection() {
        Map<Long, Float> maxima = new HashMap<>();
        for (long key = 0; key < 32; key++) {
            maxima.put(key, 1.0f);
        }
        maxima.put(-1L, 2.0f);
        Set<Set<Long>> selections = new HashSet<>();
        Set<Long> selectedAcrossEpochs = new HashSet<>();
        SmokeSourceAdmission admission = new SmokeSourceAdmission();
        for (long epoch = 0; epoch < 128; epoch++) {
            admission.reset(5, epoch);
            maxima.forEach((key, strength) -> admission.offer(key, strength));
            Set<Long> expected = topK(maxima, 5, epoch);
            checkSelection(admission, maxima, expected);
            check(admission.contains(-1), "strength takes precedence over epoch rank");
            selections.add(expected);
            selectedAcrossEpochs.addAll(expected);
        }
        check(selections.size() > 1, "equal-strength selection rotates");
        check(selectedAcrossEpochs.size() == maxima.size(), "all tied keys get selected across epochs");
    }

    private static void checkRandomReference() {
        Random random = new Random(0x70FCAFE);
        SmokeSourceAdmission admission = new SmokeSourceAdmission();
        for (int trial = 0; trial < 300; trial++) {
            int capacity = random.nextInt(40);
            long epoch = random.nextLong();
            admission.reset(capacity, epoch);
            Map<Long, Float> maxima = new HashMap<>();
            long[] keys = new long[64];
            keys[1] = Long.MIN_VALUE;
            keys[2] = Long.MAX_VALUE;
            for (int i = 3; i < keys.length; i++) {
                keys[i] = random.nextLong();
            }
            for (int step = 0; step < 400; step++) {
                long key = keys[random.nextInt(keys.length)];
                float strength = random.nextInt(20) * 0.125f;
                maxima.merge(key, strength, Math::max);
                admission.offer(key, strength);
                checkSelection(admission, maxima, topK(maxima, capacity, epoch));
            }
        }
    }

    private static Set<Long> topK(Map<Long, Float> maxima, int capacity, long epoch) {
        List<Long> sorted = new ArrayList<>(maxima.keySet());
        sorted.sort((a, b) -> {
            int comparison = Float.compare(maxima.get(b), maxima.get(a));
            return comparison != 0
                    ? comparison
                    : Long.compareUnsigned(referenceRank(b, epoch), referenceRank(a, epoch));
        });
        return new HashSet<>(sorted.subList(0, Math.min(capacity, sorted.size())));
    }

    private static long referenceRank(long key, long epoch) {
        return referenceMix(key ^ referenceMix(epoch + 0x9E3779B97F4A7C15L));
    }

    private static long referenceMix(long value) {
        long first = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        long second = (first ^ (first >>> 27)) * 0x94D049BB133111EBL;
        return second ^ (second >>> 31);
    }

    private static void checkSelection(
            SmokeSourceAdmission admission,
            Map<Long, Float> offered,
            Set<Long> expected) {
        check(admission.size() == expected.size(), "reference unique size");
        for (long key : offered.keySet()) {
            check(admission.contains(key) == expected.contains(key), "reference membership for " + key);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Offer(long key, float strength) {
    }
}
