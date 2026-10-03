package dev.dizabanik.realisticsmoke;

import java.util.Random;

public final class SmokeTransportTest {
    public static void main(String[] args) {
        checkOpeningArea();
        checkConcentration();
        checkGradientSymmetryAndEquilibrium();
        checkRequestedNormalization();
        checkAccumulationBeforePruning();
        checkRandomizedConservation();
        System.out.println(
                "SmokeTransportTest passed: opening area, concentration, gradient symmetry/equilibrium, bounded normalization, float accumulation, and 10000 randomized mass transfers.");
    }

    private static void checkOpeningArea() {
        for (int openings = 0; openings <= 4; openings++) {
            close(SmokeTransport.openingTransfer(8.0f, 0.5f, openings), openings,
                    "transfer proportional to opening area");
            close(SmokeTransport.outdoorMass(8.0f, 0.25f, openings),
                    8.0f - 1.5f * openings, "outdoor retention proportional to opening area");
        }
        close(SmokeTransport.openingTransfer(8.0f, 0.0f, 4), 0.0f, "zero fraction");
        close(SmokeTransport.openingTransfer(0.0f, 0.5f, 4), 0.0f, "zero mass");
        close(SmokeTransport.outdoorMass(8.0f, 1.0f, 4), 8.0f, "full retention");
    }

    private static void checkConcentration() {
        close(SmokeTransport.concentration(2.0f, 0), 0.0f, "empty component");
        for (int volume = 1; volume <= 8; volume++) {
            int component = (1 << volume) - 1;
            close(SmokeTransport.concentration(volume * 0.25f, component), 2.0f,
                    "volume-normalized concentration");
        }
        close(SmokeTransport.concentration(2.0f, 0x1ff), 2.0f, "low eight bits only");
    }

    private static void checkGradientSymmetryAndEquilibrium() {
        for (int volume = 1; volume <= 8; volume++) {
            int component = (1 << volume) - 1;
            float highMass = volume * 0.75f;
            float lowMass = volume * 0.25f;
            float highDensity = SmokeTransport.concentration(highMass, component);
            float lowDensity = SmokeTransport.concentration(lowMass, component);
            for (int openings = 0; openings <= 4; openings++) {
                float forward = SmokeTransport.gradientTransfer(highMass, highDensity,
                        lowDensity, 0.5f, openings);
                float backward = SmokeTransport.gradientTransfer(lowMass, lowDensity,
                        highDensity, 0.5f, openings);
                close(forward, (highMass - lowMass) * 0.5f * (openings / 4.0f),
                        "equal-volume gradient magnitude");
                close(backward, 0.0f, "no uphill gradient flow");
                float swappedForward = SmokeTransport.gradientTransfer(lowMass, lowDensity,
                        highDensity, 0.5f, openings);
                float swappedBackward = SmokeTransport.gradientTransfer(highMass, highDensity,
                        lowDensity, 0.5f, openings);
                close(forward - backward, -(swappedForward - swappedBackward),
                        "equal-volume gradient symmetry");
                close(SmokeTransport.gradientTransfer(highMass, highDensity, highDensity,
                        0.5f, openings), 0.0f, "equal density equilibrium");
            }
        }
        float narrowDensity = SmokeTransport.concentration(0.25f, 1);
        float fullDensity = SmokeTransport.concentration(2.0f, 255);
        close(SmokeTransport.gradientTransfer(0.25f, narrowDensity, fullDensity,
                0.5f, 4), 0.0f, "unequal-volume equilibrium forward");
        close(SmokeTransport.gradientTransfer(2.0f, fullDensity, narrowDensity,
                0.5f, 4), 0.0f, "unequal-volume equilibrium reverse");
        close(SmokeTransport.gradientTransfer(0.0f, 0.0f, 0.0f,
                0.5f, 4), 0.0f, "empty equilibrium is finite");
    }

    private static void checkRequestedNormalization() {
        close(SmokeTransport.transferScale(8.0f, 0.0f), 1.0f, "no requests");
        close(SmokeTransport.transferScale(8.0f, 4.0f), 1.0f, "under budget");
        close(SmokeTransport.transferScale(8.0f, 8.0f), 1.0f, "exact budget");
        close(SmokeTransport.transferScale(8.0f, 32.0f), 0.25f, "over budget");
        close(SmokeTransport.transferScale(0.0f, 1.0f), 0.0f, "no available mass");
        close(SmokeTransport.transferScale(0.0f, 0.0f), 1.0f, "zero over zero avoided");
        float[] requests = { 6.0f, 5.0f, 4.0f, 3.0f, 2.0f, 1.0f };
        float requested = 0.0f;
        for (float request : requests) {
            requested += request;
        }
        float scale = SmokeTransport.transferScale(1.0f, requested);
        float remaining = 1.0f;
        double delivered = 0.0;
        for (float request : requests) {
            float amount = Math.min(remaining, request * scale);
            check(amount >= 0.0f && amount <= remaining, "bounded normalized request");
            remaining -= amount;
            delivered += amount;
        }
        close(delivered + remaining, 1.0, "normalized requests conserve mass");
    }

    private static void checkAccumulationBeforePruning() {
        float threshold = 0.035f;
        float contribution = 0.012f;
        float accumulated = 0.0f;
        float prematurelyPruned = 0.0f;
        for (int donor = 0; donor < 3; donor++) {
            accumulated += contribution;
            if (contribution >= threshold) {
                prematurelyPruned += contribution;
            }
        }
        check(accumulated >= threshold, "combined subthreshold contributions survive");
        check(prematurelyPruned == 0.0f, "per-transfer pruning loses a surviving pocket");
        float clippedEarly = 0.0f;
        float summed = 0.0f;
        for (float contributionMass : new float[] { 0.02f, 0.02f, 0.02f }) {
            summed += contributionMass;
            clippedEarly = Math.min(threshold, clippedEarly + contributionMass);
        }
        check(summed > clippedEarly, "accumulate raw float sums before saturation");
        close(Math.min(threshold, summed), clippedEarly, "final saturation is explicit loss");
    }

    private static void checkRandomizedConservation() {
        Random random = new Random(0x5a0ceL);
        float[] requests = new float[24];
        float[] destinations = new float[24];
        for (int trial = 0; trial < 10_000; trial++) {
            float available = random.nextFloat() * 100.0f;
            int count = 1 + random.nextInt(requests.length);
            float requested = 0.0f;
            double before = available;
            for (int index = 0; index < count; index++) {
                destinations[index] = random.nextFloat() * 100.0f;
                before += destinations[index];
                float fraction = random.nextFloat() * 0.9f;
                int openings = random.nextInt(5);
                requests[index] = index % 2 == 0
                        ? SmokeTransport.openingTransfer(available, fraction, openings)
                        : SmokeTransport.gradientTransfer(available, 8.0f,
                                random.nextFloat() * 12.0f, fraction, openings);
                requested += requests[index];
            }
            float scale = SmokeTransport.transferScale(available, requested);
            check(Float.isFinite(scale) && scale >= 0.0f && scale <= 1.0f,
                    "finite scale in [0, 1], trial " + trial);
            float remaining = available;
            for (int index = 0; index < count; index++) {
                float amount = Math.min(remaining, requests[index] * scale);
                check(Float.isFinite(amount) && amount >= 0.0f && amount <= remaining,
                        "bounded transfer, trial " + trial);
                if (random.nextInt(4) != 0) {
                    destinations[index] += amount;
                    remaining -= amount;
                }
            }
            check(remaining >= 0.0f, "nonnegative residual, trial " + trial);
            double after = remaining;
            for (int index = 0; index < count; index++) {
                after += destinations[index];
            }
            close(after, before, "float mass conservation, trial " + trial);
        }
    }

    private static void close(double actual, double expected, String description) {
        double tolerance = Math.max(1.0e-7, Math.abs(expected) * 2.0e-6);
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                description + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
