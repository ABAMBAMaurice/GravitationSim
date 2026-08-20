package com.example.gravity;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Body-list generators for every preset system. Orbits are built in a flat,
 * horizontal XZ
 * plane (Y is "up") using 2D-style circular-orbit math (position via cos/sin,
 * tangential
 * velocity via perpendicularXZ), then tilted out of plane with rotateAroundX so
 * the 3D-ness
 * is genuine — not just a flat scene viewed with a 3D camera. Two-body-style
 * orbits stay
 * exactly planar under gravity regardless of tilt, so this rotation doesn't
 * change the
 * physics, only its orientation in space.
 */
final class Presets {

    private Presets() {
    }

    private static final double TOY_INCLINATION = Math.toRadians(18);
    private static final double ASTRONOMICAL_UNIT_KM = 149.6e6;
    // Toy presets don't have real-world rotation data, but still spin at a fixed
    // scene-scale period so the feature (and its speed slider) is visible
    // everywhere.
    private static final double TOY_ROTATION_PERIOD_SECONDS = 6.0;

    private static double randomInitialPhase() {
        return ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
    }

    private static long randomSeed() {
        return ThreadLocalRandom.current().nextLong();
    }

    /**
     * Two comparable masses with zero net momentum: they orbit their common,
     * stationary barycenter.
     */
    static List<Body> binaryBodies(double g) {
        double m1 = 6000.0;
        double m2 = 3000.0;
        double totalMass = m1 + m2;
        double separationKm = 40.0;

        double relativeSpeed = Math.sqrt(g * totalMass / separationKm);
        Vector3D separationVector = new Vector3D(separationKm, 0, 0);
        Vector3D pos1 = separationVector.scale(-m2 / totalMass).rotateAroundX(TOY_INCLINATION);
        Vector3D pos2 = separationVector.scale(m1 / totalMass).rotateAroundX(TOY_INCLINATION);
        Vector3D tangent = separationVector.normalize().perpendicularXZ();
        Vector3D vel1 = tangent.scale(relativeSpeed * m2 / totalMass).rotateAroundX(TOY_INCLINATION);
        Vector3D vel2 = tangent.scale(-relativeSpeed * m1 / totalMass).rotateAroundX(TOY_INCLINATION);

        Body a = new Body("Objet A", m1, 2.4, new Color(255, 176, 59), pos1, vel1);
        Body b = new Body("Objet B", m2, 1.8, new Color(90, 190, 255), pos2, vel2);
        a.rotationPeriodSeconds = TOY_ROTATION_PERIOD_SECONDS;
        b.rotationPeriodSeconds = TOY_ROTATION_PERIOD_SECONDS * 0.7;
        a.surfaceSeed = randomSeed();
        b.surfaceSeed = randomSeed();
        return new ArrayList<>(List.of(a, b));
    }

    /**
     * Lagrange's equilateral-triangle solution: three equal masses at the vertices
     * of an
     * equilateral triangle, each orbiting the common centroid on a circle of speed
     * sqrt(G*m/side) — an exact periodic Newtonian solution, not an approximation.
     */
    static List<Body> threeBodyBodies(double g) {
        double mass = 4000.0;
        double sideKm = 36.0;
        double circumradius = sideKm / Math.sqrt(3);
        double speed = Math.sqrt(g * mass / sideKm);
        double[] anglesDeg = { 90, 210, 330 };
        Color[] colors = { new Color(255, 120, 120), new Color(120, 255, 150), new Color(140, 160, 255) };

        List<Body> bodies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double angle = Math.toRadians(anglesDeg[i]);
            Vector3D flat = new Vector3D(Math.cos(angle), 0, Math.sin(angle));
            Vector3D pos = flat.scale(circumradius).rotateAroundX(TOY_INCLINATION);
            Vector3D vel = flat.perpendicularXZ().scale(speed).rotateAroundX(TOY_INCLINATION);
            Body body = new Body("Corps " + (i + 1), mass, 2.2, colors[i], pos, vel);
            body.rotationPeriodSeconds = TOY_ROTATION_PERIOD_SECONDS;
            body.surfaceSeed = randomSeed();
            bodies.add(body);
        }
        return bodies;
    }

    /** A moon's orbit is expressed relative to its parent planet, not the Sun. */
    private record MoonData(String name, double massKg, double radiusKm, double distanceKm,
            double angleDeg, double inclinationDeg, boolean retrograde, Color color) {
    }

    private record PlanetData(String name, double massKg, double radiusKm, double distanceKm,
            double angleDeg, double inclinationDeg, double rotationPeriodHours,
            Color color, List<MoonData> moons) {
    }

    // Real semi-major axes, masses, (approximate, real) orbital inclinations and
    // real
    // sidereal rotation periods (hours; negative = retrograde spin, e.g. Venus,
    // Uranus).
    private static final List<PlanetData> REAL_PLANETS = List.of(
            new PlanetData("Mercure", 3.301e23, 2440, 57.9e6, 20, 7.00, 1407.6, new Color(180, 170, 160), List.of()),
            new PlanetData("Venus", 4.867e24, 6052, 108.2e6, 70, 3.39, -5832.6, new Color(230, 200, 140), List.of()),
            new PlanetData("Terre", 5.972e24, 6371, 149.6e6, 130, 0.00, 23.934, new Color(90, 160, 255), List.of(
                    new MoonData("Lune", 7.342e22, 1737, 384_400, 0, 5.14, false, new Color(210, 210, 210)))),
            new PlanetData("Mars", 6.417e23, 3390, 227.9e6, 190, 1.85, 24.623, new Color(220, 100, 70), List.of(
                    new MoonData("Phobos", 1.0659e16, 11.3, 9_376, 0, 1.1, false, new Color(190, 160, 140)),
                    new MoonData("Deimos", 1.4762e15, 6.2, 23_463, 140, 1.8, false, new Color(190, 160, 140)))),
            // The largest object in the asteroid belt, and the only one massive enough to
            // be
            // round under its own gravity (a dwarf planet, not just an asteroid).
            new PlanetData("Ceres", 9.393e20, 469.7, 414.02e6, 100, 10.59, 9.074, new Color(170, 165, 160), List.of()),
            new PlanetData("Jupiter", 1.898e27, 69911, 778.5e6, 240, 1.30, 9.925, new Color(230, 180, 130), List.of(
                    new MoonData("Io", 8.9319e22, 1821.6, 421_800, 0, 0.04, false, new Color(230, 220, 120)),
                    new MoonData("Europe", 4.7998e22, 1560.8, 671_100, 80, 0.47, false, new Color(200, 190, 170)),
                    new MoonData("Ganymede", 1.4819e23, 2634.1, 1_070_400, 160, 0.20, false, new Color(170, 160, 150)),
                    new MoonData("Callisto", 1.0759e23, 2410.3, 1_882_700, 240, 0.19, false,
                            new Color(130, 120, 110)))),
            new PlanetData("Saturne", 5.683e26, 58232, 1434.0e6, 290, 2.49, 10.656, new Color(220, 200, 150), List.of(
                    new MoonData("Mimas", 3.75e19, 198.2, 185_540, 0, 1.53, false, new Color(200, 200, 200)),
                    new MoonData("Enceladus", 1.08e20, 252.1, 237_948, 51, 0.02, false, new Color(230, 230, 235)),
                    new MoonData("Tethys", 6.18e20, 531.1, 294_619, 103, 1.09, false, new Color(210, 210, 210)),
                    new MoonData("Dione", 1.095e21, 561.4, 377_396, 154, 0.02, false, new Color(200, 200, 200)),
                    new MoonData("Rhea", 2.31e21, 763.8, 527_108, 206, 0.33, false, new Color(210, 205, 195)),
                    new MoonData("Titan", 1.3452e23, 2574.7, 1_221_870, 257, 0.35, false, new Color(220, 180, 100)),
                    new MoonData("Iapetus", 1.805e21, 734.5, 3_560_820, 309, 15.47, false, new Color(160, 150, 140)))),
            new PlanetData("Uranus", 8.681e25, 25362, 2871.0e6, 330, 0.77, -17.24, new Color(160, 220, 230), List.of(
                    new MoonData("Miranda", 6.4e19, 235.8, 129_900, 0, 4.34, false, new Color(190, 190, 195)),
                    new MoonData("Ariel", 1.251e21, 578.9, 190_900, 72, 0.04, false, new Color(200, 200, 205)),
                    new MoonData("Umbriel", 1.275e21, 584.7, 266_000, 144, 0.13, false, new Color(140, 140, 145)),
                    new MoonData("Titania", 3.4e21, 788.9, 436_300, 216, 0.08, false, new Color(180, 175, 175)),
                    new MoonData("Oberon", 3.076e21, 761.4, 583_500, 288, 0.07, false, new Color(170, 165, 165)))),
            new PlanetData("Neptune", 1.024e26, 24622, 4495.0e6, 10, 1.77, 16.11, new Color(80, 110, 230), List.of(
                    new MoonData("Triton", 2.139e22, 1353.4, 354_759, 0, 20.0, true, new Color(200, 220, 230)),
                    new MoonData("Nereide", 3.1e19, 178.5, 5_513_400, 120, 7.23, false, new Color(180, 180, 185)),
                    new MoonData("Proteus", 4.4e19, 210, 117_647, 240, 0.04, false, new Color(150, 150, 150)))));

    /**
     * The real Sun (mass 1.989e30 kg, radius 696000 km), its eight real planets at
     * their real
     * distances/masses/orbital inclinations, on circular orbits at the exact speed
     * Kepler's
     * law gives for the supplied (physically real) G, plus the major real moon of
     * every planet
     * that has one — each moon orbits its planet (position/velocity of the planet
     * plus the
     * moon's own orbit around it, also tilted by its own real inclination), so its
     * total
     * motion is genuinely composed, not just visually offset. The Sun gets a tiny
     * compensating velocity so total system momentum stays zero, matching real
     * physics.
     */
    static List<Body> realSolarSystemBodies(double g) {
        double sunMassKg = 1.989e30;
        double sunMassInternal = Units.kgToInternalMass(sunMassKg);
        double muSun = g * sunMassInternal;

        List<Body> bodies = new ArrayList<>();
        Vector3D systemMomentum = Vector3D.ZERO;

        for (PlanetData p : REAL_PLANETS) {
            double planetMassInternal = Units.kgToInternalMass(p.massKg());
            double planetSpeed = Math.sqrt(muSun / p.distanceKm());
            double planetAngle = Math.toRadians(p.angleDeg());
            double planetIncl = Math.toRadians(p.inclinationDeg());

            Vector3D flatPos = new Vector3D(Math.cos(planetAngle), 0, Math.sin(planetAngle)).scale(p.distanceKm());
            Vector3D planetPosition = flatPos.rotateAroundX(planetIncl);
            Vector3D planetVelocity = flatPos.normalize().perpendicularXZ().scale(planetSpeed)
                    .rotateAroundX(planetIncl);

            Body planet = new Body(p.name(), planetMassInternal, p.radiusKm(), p.color(), planetPosition,
                    planetVelocity);
            planet.rotationPeriodSeconds = p.rotationPeriodHours() * 3600.0;
            planet.rotationAngle = randomInitialPhase();
            planet.surfaceSeed = randomSeed();
            // Trails are shown by default for planets and moons only (not the Sun or the
            // asteroid belt), so the orbital motion reads clearly without cluttering the view.
            planet.showTrail = true;
            bodies.add(planet);
            systemMomentum = systemMomentum.add(planetVelocity.scale(planetMassInternal));

            double muPlanet = g * planetMassInternal;
            for (MoonData m : p.moons()) {
                double moonMassInternal = Units.kgToInternalMass(m.massKg());
                double moonSpeed = Math.sqrt(muPlanet / m.distanceKm());
                double moonAngle = Math.toRadians(m.angleDeg());
                double moonIncl = Math.toRadians(m.inclinationDeg());

                Vector3D flatOffset = new Vector3D(Math.cos(moonAngle), 0, Math.sin(moonAngle)).scale(m.distanceKm());
                Vector3D offset = flatOffset.rotateAroundX(moonIncl);
                Vector3D orbitalVelocity = flatOffset.normalize().perpendicularXZ()
                        .scale(m.retrograde() ? -moonSpeed : moonSpeed).rotateAroundX(moonIncl);

                Vector3D moonPosition = planetPosition.add(offset);
                Vector3D moonVelocity = planetVelocity.add(orbitalVelocity);
                Body moon = new Body(m.name(), moonMassInternal, m.radiusKm(), m.color(), moonPosition, moonVelocity);
                // Virtually every close-in real moon is tidally locked (rotation period =
                // orbital period) — the same physics that keeps our own Moon's near side
                // always facing Earth.
                double orbitalPeriodSeconds = 2 * Math.PI * m.distanceKm() / moonSpeed;
                moon.rotationPeriodSeconds = m.retrograde() ? -orbitalPeriodSeconds : orbitalPeriodSeconds;
                // +PI, not just moonAngle: this makes texture-longitude 0 face the planet at
                // t=0, and since rotation and revolution now advance at the identical rate,
                // it keeps facing the planet forever — genuine tidal locking, not just a
                // matching period. drawTexturedSphere's mare placement relies on this.
                moon.rotationAngle = moonAngle + Math.PI;
                moon.surfaceSeed = randomSeed();
                moon.showTrail = true;
                bodies.add(moon);
                systemMomentum = systemMomentum.add(moonVelocity.scale(moonMassInternal));
            }
        }

        systemMomentum = addAsteroidBelt(bodies, muSun, systemMomentum);

        Vector3D sunVelocity = systemMomentum.scale(-1.0 / sunMassInternal);
        Body sun = new Body("Soleil", sunMassInternal, 696000, new Color(255, 214, 100), Vector3D.ZERO, sunVelocity);
        sun.rotationPeriodSeconds = 609.12 * 3600.0; // ~25.4 days at the equator
        sun.rotationAngle = randomInitialPhase();
        sun.surfaceSeed = randomSeed();
        bodies.add(0, sun);

        return bodies;
    }

    /**
     * A representative scatter of small bodies between Mars and Jupiter (2.2-3.2
     * AU, the real
     * belt's span), each a genuine N-body participant on its own circular orbit
     * rather than a
     * decorative overlay — physically negligible in mass (the real belt's total
     * mass is only
     * ~4% of the Moon's) but still exerting (tiny) real gravity like everything
     * else here.
     */
    private static Vector3D addAsteroidBelt(List<Body> bodies, double muSun, Vector3D systemMomentum) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = 180;
        double innerKm = 2.2 * ASTRONOMICAL_UNIT_KM;
        double outerKm = 3.2 * ASTRONOMICAL_UNIT_KM;
        double innerSquared = innerKm * innerKm;
        double outerSquared = outerKm * outerKm;

        for (int i = 0; i < count; i++) {
            // Interpolating in r^2 (not r) gives uniform density per unit *area* across the
            // ring, instead of clumping toward the inner edge the way uniform-in-r would.
            double distanceKm = Math.sqrt(innerSquared + random.nextDouble() * (outerSquared - innerSquared));
            double angle = random.nextDouble() * 2 * Math.PI;
            // Real belt inclinations mostly sit under ~20 degrees.
            double inclination = Math.toRadians(random.nextDouble() * 20);
            double massKg = Math.pow(10, 12 + random.nextDouble() * 6);
            double radiusKm = 3 + random.nextDouble() * 12;
            int shade = 130 + random.nextInt(50);
            Color color = new Color(shade, shade - 10, shade - 25);

            double massInternal = Units.kgToInternalMass(massKg);
            double speed = Math.sqrt(muSun / distanceKm);
            Vector3D flat = new Vector3D(Math.cos(angle), 0, Math.sin(angle)).scale(distanceKm);
            Vector3D position = flat.rotateAroundX(inclination);
            Vector3D velocity = flat.normalize().perpendicularXZ().scale(speed).rotateAroundX(inclination);

            Body asteroid = new Body("Asteroide " + (i + 1), massInternal, radiusKm, color, position, velocity);
            // Real asteroid rotation periods are typically a few hours, tumbling in either
            // direction.
            double rotationHours = 2 + random.nextDouble() * 10;
            asteroid.rotationPeriodSeconds = (random.nextBoolean() ? 1 : -1) * rotationHours * 3600.0;
            asteroid.rotationAngle = randomInitialPhase();
            asteroid.surfaceSeed = randomSeed();
            bodies.add(asteroid);
            systemMomentum = systemMomentum.add(velocity.scale(massInternal));
        }
        return systemMomentum;
    }

    /**
     * A photon (massless, launched at exactly c) grazing a compact mass, bent
     * purely by
     * ordinary Newtonian gravity — no relativity involved, since a = G*M/r^2 never
     * depended
     * on the accelerated body's own mass to begin with. This is exactly the
     * calculation
     * Cavendish and von Soldner did in the 18th/19th century, before Einstein: it
     * predicts
     * real light bending, but only half of general relativity's value (which is why
     * the 1919
     * eclipse expedition measuring the GR value, not this one, was the famous
     * confirmation).
     * The mass here is fictional and deliberately huge for a visibly dramatic bend
     * — the
     * real Sun only deflects light by about 1.75 arcseconds, far too small to see
     * by eye.
     * Tilted by a fixed angle so it reads as a 3D encounter rather than a flat
     * scene — a
     * two-body trajectory stays exactly planar under gravity regardless of that
     * tilt.
     */
    static List<Body> photonDeflectionBodies() {
        Body mass = new Body("Masse dense (fictive)", Units.kgToInternalMass(1.0e33), 500,
                new Color(255, 140, 90), Vector3D.ZERO, Vector3D.ZERO);
        mass.rotationPeriodSeconds = TOY_ROTATION_PERIOD_SECONDS * 3;
        mass.surfaceSeed = randomSeed();
        // A photon has no rest mass and isn't a solid body — it doesn't spin.
        Vector3D photonPos = new Vector3D(-20000, 0, 2000).rotateAroundX(TOY_INCLINATION);
        Vector3D photonVel = new Vector3D(Units.SPEED_OF_LIGHT_KM_S, 0, 0).rotateAroundX(TOY_INCLINATION);
        Body photon = new Body("Photon", 0, 40, new Color(255, 255, 240), photonPos, photonVel);
        return new ArrayList<>(List.of(mass, photon));
    }

    private record CloudParticle(Vector3D position, double mass, double radius, Color color) {
    }

    /**
     * ~400 bodies of varied mass scattered through a sphere (uniform by volume, not
     * just by
     * radius), given a collective rotation around the Y (vertical) axis at 70% of
     * the
     * (point-mass approximated) circular speed — bound but sub-circular, so the
     * cloud keeps
     * mixing and migrating rather than settling into a static shape. Every pair
     * genuinely
     * pulls on every other (true N-body, not a fixed center).
     */
    static List<Body> cloudBodies(double g) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = 1000;
        double sphereRadius = 100.0;
        double circularFraction = 0.7;

        List<CloudParticle> particles = new ArrayList<>();
        double totalMass = 0;
        for (int i = 0; i < count; i++) {
            // cbrt(random) gives uniform density across the sphere's *volume*, instead of
            // bunching particles up near the center the way a plain uniform radius would.
            double r = sphereRadius * Math.cbrt(random.nextDouble());
            double theta = random.nextDouble() * 2 * Math.PI;
            double phi = Math.acos(2 * random.nextDouble() - 1);
            Vector3D position = new Vector3D(
                    r * Math.sin(phi) * Math.cos(theta),
                    r * Math.cos(phi),
                    r * Math.sin(phi) * Math.sin(theta));
            double mass = 300 + random.nextDouble() * 1700;
            double radius = 0.8 + random.nextDouble() * 1.0;
            Color color = Color.getHSBColor(random.nextFloat(), 0.55f + random.nextFloat() * 0.3f, 1f);
            particles.add(new CloudParticle(position, mass, radius, color));
            totalMass += mass;
        }

        List<Body> bodies = new ArrayList<>();
        Vector3D totalMomentum = Vector3D.ZERO;
        int index = 1;
        for (CloudParticle p : particles) {
            // Rotation is around the Y (vertical) axis: only the XZ (cylindrical) radius
            // sets
            // the tangential speed, and the tangent stays in the XZ plane (y-velocity = 0),
            // giving a spinning, slightly flattening cloud rather than a static ball.
            double cylindricalR = Math.hypot(p.position().x, p.position().z);
            double circularSpeed = cylindricalR > 0.1 ? Math.sqrt(g * totalMass / cylindricalR) : 0;
            Vector3D radialXZ = new Vector3D(p.position().x, 0, p.position().z);
            Vector3D tangent = cylindricalR > 0.1 ? radialXZ.normalize().perpendicularXZ() : Vector3D.ZERO;
            Vector3D velocity = tangent.scale(circularSpeed * circularFraction);

            Body body = new Body("Corps " + index++, p.mass(), p.radius(), p.color(), p.position(), velocity);
            body.rotationPeriodSeconds = (random.nextBoolean() ? 1 : -1) * (1.5 + random.nextDouble() * 4);
            body.rotationAngle = randomInitialPhase();
            body.surfaceSeed = randomSeed();
            bodies.add(body);
            totalMomentum = totalMomentum.add(velocity.scale(p.mass()));
        }

        Vector3D correction = totalMomentum.scale(-1.0 / totalMass);
        for (Body b : bodies) {
            b.velocity = b.velocity.add(correction);
        }

        return bodies;
    }
}
