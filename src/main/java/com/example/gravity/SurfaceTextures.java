package com.example.gravity;

import java.util.Random;

/**
 * Procedurally generated equirectangular textures for Earth and the Moon — no real imagery
 * (none is fetchable here), just stylized continent/mare blobs plus craters, generated once
 * at class-load time and reused every frame. Not geographically accurate, but recognizably
 * "Earth-like" and "Moon-like" when mapped onto a sphere by SimulationPanel.
 */
final class SurfaceTextures {

    private SurfaceTextures() {
    }

    private record Blob(double lat, double lon, double radiusDeg, double strength) {
    }

    // Rough, stylized continent placements — not accurate borders, just plausible blob
    // positions/sizes, kept small and separated so oceans actually show between them
    // instead of merging into one supercontinent. Declared before EARTH_TEXTURE below:
    // static initializers run in textual order, and generateEarthTexture() reads this
    // array, so it must be assigned first.
    private static final Blob[] EARTH_LAND = {
            new Blob(45, -100, 20, 1.0),  // North America
            new Blob(5, -75, 14, 0.9),    // Central America / N. South America
            new Blob(-15, -60, 16, 0.9),  // South America
            new Blob(20, 10, 20, 1.0),    // North Africa
            new Blob(-10, 22, 16, 0.9),   // Central/South Africa
            new Blob(52, 20, 13, 0.85),   // Europe
            new Blob(55, 90, 26, 1.0),    // Siberia / North Asia
            new Blob(30, 100, 20, 0.95),  // East/Central Asia
            new Blob(18, 78, 11, 0.8),    // India
            new Blob(-25, 135, 13, 0.85), // Australia
    };

    // Mare (dark "seas") clustered near texture longitude 0 — Presets.java sets the Moon's
    // initial rotationAngle so that longitude 0 always faces Earth (genuine tidal lock), so
    // this placement means the near side is the one showing the maria, matching reality.
    private static final Blob[] MOON_MARIA = {
            new Blob(20, -5, 22, 1.0),
            new Blob(5, 25, 16, 0.8),
            new Blob(-15, -20, 18, 0.85),
            new Blob(35, 10, 12, 0.6),
    };

    static final int EARTH_TEX_W = 240;
    static final int EARTH_TEX_H = 120;
    static final int[] EARTH_TEXTURE = generateEarthTexture();

    static final int MOON_TEX_W = 200;
    static final int MOON_TEX_H = 100;
    static final int[] MOON_TEXTURE = generateMoonTexture();

    private static int[] generateEarthTexture() {
        int[] pixels = new int[EARTH_TEX_W * EARTH_TEX_H];
        for (int v = 0; v < EARTH_TEX_H; v++) {
            double lat = 90.0 - (v + 0.5) / EARTH_TEX_H * 180.0;
            for (int u = 0; u < EARTH_TEX_W; u++) {
                double lon = (u + 0.5) / EARTH_TEX_W * 360.0 - 180.0;

                // MAX, not sum: nearby blobs shouldn't compound into one supercontinent —
                // each landmass's own edge is independent of how many others are nearby.
                double land = 0;
                for (Blob c : EARTH_LAND) {
                    double d = angularDistanceDeg(lat, lon, c.lat(), c.lon());
                    land = Math.max(land, c.strength() * smoothEdge(d, c.radiusDeg()));
                }
                // Small coastline irregularity only — kept subtle so it doesn't dominate
                // the shape the way a larger amplitude did (that read as diagonal streaks).
                land += fakeNoise(lat, lon) * 0.06;

                int base = land > 0.45 ? landColor(land) : oceanColor(lat);
                if (Math.abs(lat) > 70) {
                    double iceFactor = Math.min(1, (Math.abs(lat) - 70) / 12.0);
                    base = blend(base, 0xF0F4F8, iceFactor);
                }
                pixels[v * EARTH_TEX_W + u] = base;
            }
        }
        return pixels;
    }

    /** 1 well inside the radius, smoothly falling to 0 by the edge — a defined coastline, not a blur. */
    private static double smoothEdge(double distanceDeg, double radiusDeg) {
        double innerRadius = radiusDeg * 0.65;
        if (distanceDeg <= innerRadius) {
            return 1.0;
        }
        if (distanceDeg >= radiusDeg) {
            return 0.0;
        }
        double t = (radiusDeg - distanceDeg) / (radiusDeg - innerRadius);
        return t * t * (3 - 2 * t);
    }

    private static int[] generateMoonTexture() {
        int[] pixels = new int[MOON_TEX_W * MOON_TEX_H];

        // Fixed seed: generated once and cached, so it's deterministic across runs, not
        // random per app launch.
        Random craterRandom = new Random(42);
        int craterCount = 90;
        double[] craterLat = new double[craterCount];
        double[] craterLon = new double[craterCount];
        double[] craterRadius = new double[craterCount];
        for (int i = 0; i < craterCount; i++) {
            craterLat[i] = Math.toDegrees(Math.asin(craterRandom.nextDouble() * 2 - 1));
            craterLon[i] = craterRandom.nextDouble() * 360 - 180;
            craterRadius[i] = 2 + craterRandom.nextDouble() * 10;
        }

        for (int v = 0; v < MOON_TEX_H; v++) {
            double lat = 90.0 - (v + 0.5) / MOON_TEX_H * 180.0;
            for (int u = 0; u < MOON_TEX_W; u++) {
                double lon = (u + 0.5) / MOON_TEX_W * 360.0 - 180.0;

                double mareValue = 0;
                for (Blob m : MOON_MARIA) {
                    double d = angularDistanceDeg(lat, lon, m.lat(), m.lon());
                    mareValue += m.strength() * Math.exp(-(d * d) / (2 * m.radiusDeg() * m.radiusDeg()));
                }
                mareValue += fakeNoise(lat * 1.7, lon * 1.7) * 0.12;

                int base = clampByte(150 + (int) (fakeNoise(lat * 3, lon * 3) * 15));
                int gray = mareValue > 0.3 ? clampByte(base - 55) : base;

                for (int i = 0; i < craterCount; i++) {
                    double d = angularDistanceDeg(lat, lon, craterLat[i], craterLon[i]);
                    if (d < craterRadius[i]) {
                        double t = d / craterRadius[i];
                        gray = t > 0.75 ? clampByte(gray + 25) : clampByte(gray - 35);
                        break;
                    }
                }
                pixels[v * MOON_TEX_W + u] = (gray << 16) | (gray << 8) | gray;
            }
        }
        return pixels;
    }

    private static double angularDistanceDeg(double lat1, double lon1, double lat2, double lon2) {
        double lat1r = Math.toRadians(lat1);
        double lat2r = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double cosCentral = Math.sin(lat1r) * Math.sin(lat2r) + Math.cos(lat1r) * Math.cos(lat2r) * Math.cos(dLon);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosCentral))));
    }

    /** Deterministic multi-frequency sinusoid sum standing in for real Perlin/value noise. */
    private static double fakeNoise(double lat, double lon) {
        double a = Math.sin(Math.toRadians(lat) * 7.3 + Math.cos(Math.toRadians(lon) * 3.1));
        double b = Math.sin(Math.toRadians(lon) * 5.9 - Math.toRadians(lat) * 2.3);
        double c = Math.sin(Math.toRadians(lat) * 13.1 + Math.toRadians(lon) * 8.7);
        return (a + b + c) / 3.0;
    }

    private static int landColor(double land) {
        int r = clampByte((int) (70 + land * 30));
        int g = clampByte((int) (90 + land * 40));
        int b = 40;
        return (r << 16) | (g << 8) | b;
    }

    private static int oceanColor(double lat) {
        int b = clampByte((int) (120 + (90 - Math.abs(lat)) * 0.3));
        return (20 << 16) | (60 << 8) | b;
    }

    private static int blend(int colorA, int colorB, double t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (colorA >> 16) & 0xFF, ag = (colorA >> 8) & 0xFF, ab = colorA & 0xFF;
        int br = (colorB >> 16) & 0xFF, bg = (colorB >> 8) & 0xFF, bb = colorB & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int b = (int) (ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
