package com.example.gravity;

import java.util.Locale;

/**
 * The simulation's world unit is the kilometre; simulated time is in seconds
 * (as already shown by the "t = ... s" HUD readout). A world-space velocity
 * of 1 km per simulated second is therefore exactly 1 km/s, which this class
 * converts to km/h for display.
 *
 * Body.mass and the gravitational constant G stay in their existing, tuned
 * simulation units internally — the physics and the presets' orbits are
 * unchanged. KG_PER_MASS_UNIT only converts those numbers for display/input,
 * chosen so a body's mass in kilograms lands in the same ballpark a rocky
 * sphere of its km-scale radius would have at ~3000 kg/m3 (a few-km asteroid
 * is realistically 10^12-10^15 kg, which is what this scale produces).
 */
final class Units {

    private Units() {
    }

    static final double DEFAULT_PIXELS_PER_KM = 0.1;
    static final double KG_PER_MASS_UNIT = 3.0e10;

    // The real gravitational constant (6.674e-11 m^3 kg^-1 s^-2), re-derived for
    // this simulation's units (km, seconds, mass in KG_PER_MASS_UNIT-sized units).
    // Used only by the real-solar-system preset — every other preset keeps its
    // own tuned, non-physical G for a faster-paced toy orbit.
    private static final double REAL_G_SI = 6.674e-11;
    private static final double M3_PER_KM3 = 1.0e9;
    static final double G_INTERNAL_REAL = REAL_G_SI / M3_PER_KM3 * KG_PER_MASS_UNIT;

    /** Speed of light in vacuum, in km/s — exact by definition. */
    static final double SPEED_OF_LIGHT_KM_S = 299_792.458;

    private static final char[] SUPERSCRIPT_DIGITS = { '⁰', '¹', '²', '³', '⁴', '⁵', '⁶', '⁷', '⁸', '⁹' };

    static double kmPerSecondToKmPerHour(double kmPerSecond) {
        return kmPerSecond * 3600.0;
    }

    static double kmPerHourToKmPerSecond(double kmPerHour) {
        return kmPerHour / 3600.0;
    }

    static double internalMassToKg(double internalMass) {
        return internalMass * KG_PER_MASS_UNIT;
    }

    static double kgToInternalMass(double kg) {
        return kg / KG_PER_MASS_UNIT;
    }

    /** Formats a kilogram mass in scientific notation, e.g. "1.80 × 10¹⁴ kg". */
    static String formatKg(double kg) {
        if (kg <= 0) {
            return "0 kg";
        }
        int exponent = (int) Math.floor(Math.log10(kg));
        double mantissa = kg / Math.pow(10, exponent);
        return String.format(Locale.US, "%.2f×10%s kg", mantissa, superscript(exponent));
    }

    private static String superscript(int exponent) {
        StringBuilder sb = new StringBuilder();
        if (exponent < 0) {
            sb.append('⁻');
        }
        String digits = Integer.toString(Math.abs(exponent));
        for (int i = 0; i < digits.length(); i++) {
            sb.append(SUPERSCRIPT_DIGITS[digits.charAt(i) - '0']);
        }
        return sb.toString();
    }
}
