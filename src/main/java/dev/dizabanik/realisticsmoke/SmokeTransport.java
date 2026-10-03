package dev.dizabanik.realisticsmoke;

final class SmokeTransport {
    private SmokeTransport() {}

    static float concentration(float mass, int component) {
        int volume = Integer.bitCount(component & 0xff);
        return volume == 0 ? 0.0f : mass * (8.0f / volume);
    }

    static float openingTransfer(float available, float fraction, int openings) {
        return available * fraction * (openings / 4.0f);
    }

    static float gradientTransfer(float available, float sourceDensity, float targetDensity,
                                  float fraction, int openings) {
        if (!(sourceDensity > targetDensity) || sourceDensity <= 0.0f) {
            return 0.0f;
        }
        return openingTransfer(available, fraction, openings)
                * (1.0f - targetDensity / sourceDensity);
    }

    static float transferScale(float available, float requested) {
        return requested > available ? available / requested : 1.0f;
    }

    static float outdoorMass(float available, float retention, int openings) {
        return available * (1.0f - (1.0f - retention) * (openings / 4.0f));
    }
}
