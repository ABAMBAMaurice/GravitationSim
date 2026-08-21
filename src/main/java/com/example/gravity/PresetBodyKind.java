package com.example.gravity;

import java.awt.Color;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A named, ready-to-place real-world body type for the "Corps predefinis" insertion buttons.
 * Naming one "Terre" or "Lune" also makes it pick up SimulationPanel's special textured
 * rendering for free, since that keys off the body's name.
 */
public final class PresetBodyKind {

    public final String name;
    private final double massKg;
    private final double radiusKm;
    private final Color color;
    private final double rotationPeriodHours;

    public PresetBodyKind(String name, double massKg, double radiusKm, Color color, double rotationPeriodHours) {
        this.name = name;
        this.massKg = massKg;
        this.radiusKm = radiusKm;
        this.color = color;
        this.rotationPeriodHours = rotationPeriodHours;
    }

    Body create(Vector3D position, Vector3D velocity) {
        Body body = new Body(name, Units.kgToInternalMass(massKg), radiusKm, color, position, velocity);
        body.rotationPeriodSeconds = rotationPeriodHours * 3600.0;
        body.rotationAngle = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
        body.surfaceSeed = ThreadLocalRandom.current().nextLong();
        return body;
    }

    // Real masses/radii/rotation periods where they exist; ISS and Satellite use nominal
    // real-world-scale values (a literal ISS is ~109m across, far too small to render as
    // more than a speck except at extreme zoom — that's expected/realistic, not a bug).
    public static final PresetBodyKind SOLEIL =
            new PresetBodyKind("Soleil", 1.989e30, 696000, new Color(255, 214, 100), 609.12);
    public static final PresetBodyKind TERRE =
            new PresetBodyKind("Terre", 5.972e24, 6371, new Color(90, 160, 255), 23.934);
    public static final PresetBodyKind LUNE =
            new PresetBodyKind("Lune", 7.342e22, 1737, new Color(210, 210, 210), 655.7);
    public static final PresetBodyKind ISS =
            new PresetBodyKind("ISS", 419725, 0.109, new Color(220, 220, 230), 1.585);
    public static final PresetBodyKind SATELLITE =
            new PresetBodyKind("Satellite", 1500, 0.02, new Color(200, 200, 210), 4.0);
    public static final PresetBodyKind PLANETE =
            new PresetBodyKind("Planete", 6.417e23, 3390, new Color(220, 100, 70), 24.623);
    public static final PresetBodyKind ASTEROIDE =
            new PresetBodyKind("Asteroide", 1.0e16, 10, new Color(150, 140, 125), 6.0);
}
