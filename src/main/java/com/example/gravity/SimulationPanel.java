package com.example.gravity;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class SimulationPanel extends JPanel {

    // High enough that fast-orbiting moons (e.g. Phobos, 7h39 period) still get
    // ~100+ integration steps per orbit even at the solar-system preset's large
    // time acceleration; body counts here are small enough that this stays cheap.
    private static final int PHYSICS_SUBSTEPS_PER_FRAME = 64;
    private static final double TARGET_FRAME_SECONDS = 1.0 / 60.0;
    private static final double DRAG_VELOCITY_SCALE = 3.0;
    private static final int STAR_COUNT = 180;
    private static final Color PHOTON_COLOR = new Color(255, 255, 240);
    // Presets are tuned in real kilometres; this keeps them readable on load
    // regardless of whatever zoom the user leaves pixelsPerUnit at otherwise.
    private static final double PRESET_PIXELS_PER_KM = 10.0;

    private record Star(double xFraction, double yFraction, float baseAlpha, double phase, float size) {
    }

    private NewtonianSimulation simulation = new NewtonianSimulation(6000.0, new ArrayList<>());
    private Runnable currentPresetLoader = this::loadBinaryPreset;
    private final List<Star> stars = new ArrayList<>();

    private double initialEnergy;
    private double simulationSpeed = 1.0;
    private double spawnMass = 2000;
    private double spawnRadius = 2.0;
    private boolean paused = false;
    private boolean showTrails = true;
    private boolean spawnAsPhoton = false;
    private int trailLength = 300;
    private double pixelsPerUnit = Units.DEFAULT_PIXELS_PER_KM;
    private int spawnCounter = 1;
    private int photonCounter = 1;

    private Body selectedBody;
    private Body secondarySelectedBody;
    private Consumer<Body> onSelectionChanged = body -> {
    };
    private Runnable onPresetLoaded = () -> {
    };

    private Vector2D dragStartWorld;
    private Point dragStartScreen;
    private Point dragCurrentScreen;
    private boolean dragging;
    private boolean pressedOnExistingBody;

    private Vector2D cameraCenter = Vector2D.ZERO;
    private Point panStartScreen;
    private Vector2D panStartCamera;
    private boolean panning;

    private final Timer timer;

    public SimulationPanel() {
        setPreferredSize(new Dimension(1000, 750));
        setBackground(Palette.SPACE_TOP);
        setFocusable(true);
        generateStarfield();

        loadBinaryPreset();

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_SPACE -> togglePause();
                    case KeyEvent.VK_R -> currentPresetLoader.run();
                    case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> deleteSelectedBody();
                    case KeyEvent.VK_C -> {
                        cameraCenter = Vector2D.ZERO;
                        repaint();
                    }
                    default -> {
                    }
                }
            }
        });
        addMouseWheelListener(e -> {
            double factor = Math.pow(1.1, -e.getWheelRotation());
            pixelsPerUnit = Math.max(1e-9, Math.min(200.0, pixelsPerUnit * factor));
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isRightMouseButton(e)) {
                    panning = true;
                    panStartScreen = e.getPoint();
                    panStartCamera = cameraCenter;
                    return;
                }
                Point p = e.getPoint();
                if (e.isShiftDown()) {
                    secondarySelectedBody = hitTestBody(p);
                    repaint();
                    return;
                }
                Body hit = hitTestBody(p);
                pressedOnExistingBody = hit != null;
                setSelectedBody(hit);
                dragStartWorld = screenToWorld(p);
                dragStartScreen = p;
                dragCurrentScreen = p;
                dragging = true;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    panning = false;
                    return;
                }
                dragging = false;
                if (pressedOnExistingBody) {
                    pressedOnExistingBody = false;
                    repaint();
                    return;
                }
                Point releaseScreen = e.getPoint();
                if (releaseScreen.distance(dragStartScreen) < 5) {
                    repaint();
                    return;
                }
                Vector2D releaseWorld = screenToWorld(releaseScreen);
                Body body;
                if (spawnAsPhoton) {
                    // A photon has no rest mass, so only the drag's direction matters —
                    // its speed is fixed at c, not scaled by how far you dragged.
                    Vector2D direction = releaseWorld.subtract(dragStartWorld).normalize();
                    Vector2D velocity = direction.scale(Units.SPEED_OF_LIGHT_KM_S);
                    body = new Body("Photon " + photonCounter++, 0, spawnRadius, PHOTON_COLOR, dragStartWorld,
                            velocity);
                } else {
                    Vector2D velocity = releaseWorld.subtract(dragStartWorld).scale(DRAG_VELOCITY_SCALE);
                    body = new Body("Ajout " + spawnCounter++, spawnMass, spawnRadius, randomColor(), dragStartWorld,
                            velocity);
                }
                simulation.addBody(body);
                initialEnergy = simulation.totalEnergy();
                repaint();
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (panning) {
                    double worldDeltaX = (e.getX() - panStartScreen.x) / pixelsPerUnit;
                    double worldDeltaY = -(e.getY() - panStartScreen.y) / pixelsPerUnit;
                    cameraCenter = new Vector2D(panStartCamera.x - worldDeltaX, panStartCamera.y - worldDeltaY);
                    repaint();
                    return;
                }
                dragCurrentScreen = e.getPoint();
                if (pressedOnExistingBody && selectedBody != null) {
                    selectedBody.position = screenToWorld(dragCurrentScreen);
                }
                repaint();
            }
        });

        timer = new Timer((int) (TARGET_FRAME_SECONDS * 1000), e -> onTick());
        timer.start();
    }

    // ---- Public API used by ControlPanel ----

    public double getG() {
        return simulation.getG();
    }

    public void setG(double g) {
        simulation.setG(g);
    }

    public double getSoftening() {
        return simulation.getSoftening();
    }

    public void setSoftening(double softening) {
        simulation.setSoftening(softening);
    }

    public void setCollisionMergingEnabled(boolean enabled) {
        simulation.setCollisionMergingEnabled(enabled);
    }

    public void setSpawnMass(double mass) {
        this.spawnMass = mass;
    }

    public void setSpawnRadius(double radiusKm) {
        this.spawnRadius = radiusKm;
    }

    public void setSpawnAsPhoton(boolean photon) {
        this.spawnAsPhoton = photon;
    }

    public double getSimulationSpeed() {
        return simulationSpeed;
    }

    public void setSimulationSpeed(double speed) {
        this.simulationSpeed = speed;
    }

    public void setOnPresetLoaded(Runnable listener) {
        this.onPresetLoaded = listener;
    }

    public void setShowTrails(boolean show) {
        this.showTrails = show;
    }

    public int getTrailLength() {
        return trailLength;
    }

    public void setTrailLength(int length) {
        this.trailLength = length;
    }

    public boolean isCollisionMergingEnabled() {
        return simulation.isCollisionMergingEnabled();
    }

    public void togglePause() {
        paused = !paused;
    }

    public void clearBodies() {
        simulation.clearBodies();
        setSelectedBody(null);
        initialEnergy = 0;
        repaint();
    }

    public void setOnSelectionChanged(Consumer<Body> listener) {
        this.onSelectionChanged = listener;
    }

    public Body getSelectedBody() {
        return selectedBody;
    }

    public void setSelectedBodyMass(double mass) {
        if (selectedBody != null) {
            selectedBody.mass = mass;
            repaint();
        }
    }

    public void setSelectedBodySize(double radiusKm) {
        if (selectedBody != null) {
            selectedBody.radius = radiusKm;
            repaint();
        }
    }

    public void setSelectedBodyName(String name) {
        if (selectedBody != null) {
            selectedBody.name = name;
            repaint();
        }
    }

    /**
     * Sets the selected body's speed while keeping its current direction of travel.
     */
    public void setSelectedBodySpeed(double speedKmPerSecond) {
        if (selectedBody != null) {
            Vector2D direction = selectedBody.velocity.magnitude() > 1e-9
                    ? selectedBody.velocity.normalize()
                    : new Vector2D(1, 0);
            selectedBody.velocity = direction.scale(speedKmPerSecond);
            repaint();
        }
    }

    public void loadBinaryPreset() {
        currentPresetLoader = this::loadBinaryPreset;
        double g = 23.0;
        simulation.setG(g);
        simulation.loadBodies(binaryBodies(g));
        simulation.setCollisionMergingEnabled(false);
        pixelsPerUnit = PRESET_PIXELS_PER_KM;
        simulationSpeed = 1.0;
        trailLength = 300;
        afterPresetLoaded();
    }

    public void loadThreeBodyPreset() {
        currentPresetLoader = this::loadThreeBodyPreset;
        double g = 12.5;
        simulation.setG(g);
        simulation.loadBodies(threeBodyBodies(g));
        simulation.setCollisionMergingEnabled(false);
        pixelsPerUnit = PRESET_PIXELS_PER_KM;
        simulationSpeed = 1.0;
        trailLength = 300;
        afterPresetLoaded();
    }

    /**
     * The real Sun and its eight planets: real masses, real orbital distances, and
     * G
     * derived from the real gravitational constant (see Units.G_INTERNAL_REAL) — so
     * orbital periods come out physically correct (Earth = 365.2 simulated days, in
     * simulated seconds). At 1x speed that's unwatchable in real time, hence the
     * large default time acceleration.
     */
    public void loadStarSystemPreset() {
        currentPresetLoader = this::loadStarSystemPreset;
        double g = Units.G_INTERNAL_REAL;
        simulation.setG(g);
        simulation.loadBodies(realSolarSystemBodies(g));
        simulation.setCollisionMergingEnabled(false);
        pixelsPerUnit = 8e-8;
        simulationSpeed = 1_000_000.0;
        trailLength = 300;
        afterPresetLoaded();
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
     */
    public void loadPhotonDeflectionPreset() {
        currentPresetLoader = this::loadPhotonDeflectionPreset;
        double g = Units.G_INTERNAL_REAL;
        simulation.setG(g);

        Body mass = new Body("Masse dense (fictive)", Units.kgToInternalMass(1.0e33), 500,
                new Color(255, 140, 90), Vector2D.ZERO, Vector2D.ZERO);
        Body photon = new Body("Photon", 0, 40, PHOTON_COLOR,
                new Vector2D(-20000, 2000), new Vector2D(Units.SPEED_OF_LIGHT_KM_S, 0));
        simulation.loadBodies(new ArrayList<>(List.of(mass, photon)));
        simulation.setCollisionMergingEnabled(false);

        pixelsPerUnit = 0.02;
        simulationSpeed = 0.015;
        trailLength = 300;
        afterPresetLoaded();
    }

    /**
     * ~40 bodies of varied mass scattered through a disk, given a collective
     * rotation at
     * 70% of the (point-mass-approximated) circular speed — bound but sub-circular,
     * so the
     * cloud keeps mixing and migrating rather than settling into a static ring.
     * Every pair
     * genuinely pulls on every other (true N-body, not a fixed center), so
     * temporary
     * binaries, ejections and — since collision merging starts enabled here —
     * accretion
     * into fewer, larger bodies all emerge on their own from the same physics as
     * everything
     * else in this simulator.
     */
    public void loadGravityCloudPreset() {
        currentPresetLoader = this::loadGravityCloudPreset;
        double g = 2.5;
        simulation.setG(g);
        simulation.loadBodies(cloudBodies(g));
        simulation.setCollisionMergingEnabled(true);
        pixelsPerUnit = 6.0;
        simulationSpeed = 1.0;
        trailLength = 80;
        afterPresetLoaded();
    }

    private void afterPresetLoaded() {
        initialEnergy = simulation.totalEnergy();
        spawnCounter = 1;
        cameraCenter = Vector2D.ZERO;
        setSelectedBody(null);
        secondarySelectedBody = null;
        onPresetLoaded.run();
    }

    // ---- Selection ----

    private void setSelectedBody(Body body) {
        if (selectedBody != body) {
            selectedBody = body;
            onSelectionChanged.accept(body);
        }
        repaint();
    }

    private void deleteSelectedBody() {
        if (selectedBody != null) {
            simulation.removeBody(selectedBody);
            setSelectedBody(null);
        }
    }

    private Body hitTestBody(Point screenPoint) {
        Body closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Body body : simulation.getBodies()) {
            Point screen = worldToScreen(body.position);
            double hitRadius = Math.max(2, body.radius * pixelsPerUnit) + 4;
            double distance = screenPoint.distance(screen);
            if (distance <= hitRadius && distance < closestDistance) {
                closest = body;
                closestDistance = distance;
            }
        }
        return closest;
    }

    // ---- Preset systems ----

    /**
     * Two comparable masses with zero net momentum: they orbit their common,
     * stationary barycenter.
     */
    private static List<Body> binaryBodies(double g) {
        double m1 = 6000.0;
        double m2 = 3000.0;
        double totalMass = m1 + m2;
        double separationKm = 40.0;

        double relativeSpeed = Math.sqrt(g * totalMass / separationKm);
        Vector2D separationVector = new Vector2D(separationKm, 0);
        Vector2D pos1 = separationVector.scale(-m2 / totalMass);
        Vector2D pos2 = separationVector.scale(m1 / totalMass);
        Vector2D tangent = separationVector.normalize().perpendicular();
        Vector2D vel1 = tangent.scale(relativeSpeed * m2 / totalMass);
        Vector2D vel2 = tangent.scale(-relativeSpeed * m1 / totalMass);

        Body a = new Body("Objet A", m1, 2.4, new Color(255, 176, 59), pos1, vel1);
        Body b = new Body("Objet B", m2, 1.8, new Color(90, 190, 255), pos2, vel2);
        return new ArrayList<>(List.of(a, b));
    }

    /**
     * Lagrange's equilateral-triangle solution: three equal masses at the vertices
     * of an
     * equilateral triangle, each orbiting the common centroid on a circle of speed
     * sqrt(G*m/side) — an exact periodic Newtonian solution, not an approximation.
     */
    private static List<Body> threeBodyBodies(double g) {
        double mass = 4000.0;
        double sideKm = 36.0;
        double circumradius = sideKm / Math.sqrt(3);
        double speed = Math.sqrt(g * mass / sideKm);
        double[] anglesDeg = { 90, 210, 330 };
        Color[] colors = { new Color(255, 120, 120), new Color(120, 255, 150), new Color(140, 160, 255) };

        List<Body> bodies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double angle = Math.toRadians(anglesDeg[i]);
            Vector2D pos = new Vector2D(Math.cos(angle), Math.sin(angle)).scale(circumradius);
            Vector2D vel = pos.normalize().perpendicular().scale(speed);
            bodies.add(new Body("Corps " + (i + 1), mass, 2.2, colors[i], pos, vel));
        }
        return bodies;
    }

    /** A moon's orbit is expressed relative to its parent planet, not the Sun. */
    private record MoonData(String name, double massKg, double radiusKm, double distanceKm,
            double angleDeg, boolean retrograde, Color color) {
    }

    private record PlanetData(String name, double massKg, double radiusKm, double distanceKm,
            double angleDeg, Color color, List<MoonData> moons) {
    }

    private static final List<PlanetData> REAL_PLANETS = List.of(
            new PlanetData("Mercure", 3.301e23, 2440, 57.9e6, 20, new Color(180, 170, 160), List.of()),
            new PlanetData("Venus", 4.867e24, 6052, 108.2e6, 70, new Color(230, 200, 140), List.of()),
            new PlanetData("Terre", 5.972e24, 6371, 149.6e6, 130, new Color(90, 160, 255), List.of(
                    new MoonData("Lune", 7.342e22, 1737, 384_400, 0, false, new Color(210, 210, 210)))),
            new PlanetData("Mars", 6.417e23, 3390, 227.9e6, 190, new Color(220, 100, 70), List.of(
                    new MoonData("Phobos", 1.0659e16, 11.3, 9_376, 0, false, new Color(190, 160, 140)),
                    new MoonData("Deimos", 1.4762e15, 6.2, 23_463, 140, false, new Color(190, 160, 140)))),
            new PlanetData("Jupiter", 1.898e27, 69911, 778.5e6, 240, new Color(230, 180, 130), List.of(
                    new MoonData("Io", 8.9319e22, 1821.6, 421_800, 0, false, new Color(230, 220, 120)),
                    new MoonData("Europe", 4.7998e22, 1560.8, 671_100, 80, false, new Color(200, 190, 170)),
                    new MoonData("Ganymede", 1.4819e23, 2634.1, 1_070_400, 160, false, new Color(170, 160, 150)),
                    new MoonData("Callisto", 1.0759e23, 2410.3, 1_882_700, 240, false, new Color(130, 120, 110)))),
            new PlanetData("Saturne", 5.683e26, 58232, 1434.0e6, 290, new Color(220, 200, 150), List.of(
                    new MoonData("Mimas", 3.75e19, 198.2, 185_540, 0, false, new Color(200, 200, 200)),
                    new MoonData("Enceladus", 1.08e20, 252.1, 237_948, 51, false, new Color(230, 230, 235)),
                    new MoonData("Tethys", 6.18e20, 531.1, 294_619, 103, false, new Color(210, 210, 210)),
                    new MoonData("Dione", 1.095e21, 561.4, 377_396, 154, false, new Color(200, 200, 200)),
                    new MoonData("Rhea", 2.31e21, 763.8, 527_108, 206, false, new Color(210, 205, 195)),
                    new MoonData("Titan", 1.3452e23, 2574.7, 1_221_870, 257, false, new Color(220, 180, 100)),
                    new MoonData("Iapetus", 1.805e21, 734.5, 3_560_820, 309, false, new Color(160, 150, 140)))),
            new PlanetData("Uranus", 8.681e25, 25362, 2871.0e6, 330, new Color(160, 220, 230), List.of(
                    new MoonData("Miranda", 6.4e19, 235.8, 129_900, 0, false, new Color(190, 190, 195)),
                    new MoonData("Ariel", 1.251e21, 578.9, 190_900, 72, false, new Color(200, 200, 205)),
                    new MoonData("Umbriel", 1.275e21, 584.7, 266_000, 144, false, new Color(140, 140, 145)),
                    new MoonData("Titania", 3.4e21, 788.9, 436_300, 216, false, new Color(180, 175, 175)),
                    new MoonData("Oberon", 3.076e21, 761.4, 583_500, 288, false, new Color(170, 165, 165)))),
            new PlanetData("Neptune", 1.024e26, 24622, 4495.0e6, 10, new Color(80, 110, 230), List.of(
                    new MoonData("Triton", 2.139e22, 1353.4, 354_759, 0, true, new Color(200, 220, 230)),
                    new MoonData("Nereide", 3.1e19, 178.5, 5_513_400, 120, false, new Color(180, 180, 185)),
                    new MoonData("Proteus", 4.4e19, 210, 117_647, 240, false, new Color(150, 150, 150)))));

    /**
     * The real Sun (mass 1.989e30 kg, radius 696000 km), its eight real planets at
     * their real
     * distances/masses on circular orbits at the exact speed Kepler's law gives for
     * the supplied
     * (physically real) G, plus the major real moon of every planet that has one —
     * each moon
     * orbits its planet (position/velocity of the planet plus the moon's own orbit
     * around it),
     * so its total motion is genuinely composed, not just visually offset. The Sun
     * gets a tiny
     * compensating velocity so total system momentum stays zero, matching real
     * physics.
     */
    private static List<Body> realSolarSystemBodies(double g) {
        double sunMassKg = 1.989e30;
        double sunMassInternal = Units.kgToInternalMass(sunMassKg);
        double muSun = g * sunMassInternal;

        List<Body> bodies = new ArrayList<>();
        Vector2D systemMomentum = Vector2D.ZERO;

        for (PlanetData p : REAL_PLANETS) {
            double planetMassInternal = Units.kgToInternalMass(p.massKg());
            double planetSpeed = Math.sqrt(muSun / p.distanceKm());
            double planetAngle = Math.toRadians(p.angleDeg());
            Vector2D planetPosition = new Vector2D(Math.cos(planetAngle), Math.sin(planetAngle)).scale(p.distanceKm());
            Vector2D planetVelocity = planetPosition.normalize().perpendicular().scale(planetSpeed);
            bodies.add(new Body(p.name(), planetMassInternal, p.radiusKm(), p.color(), planetPosition, planetVelocity));
            systemMomentum = systemMomentum.add(planetVelocity.scale(planetMassInternal));

            double muPlanet = g * planetMassInternal;
            for (MoonData m : p.moons()) {
                double moonMassInternal = Units.kgToInternalMass(m.massKg());
                double moonSpeed = Math.sqrt(muPlanet / m.distanceKm());
                double moonAngle = Math.toRadians(m.angleDeg());
                Vector2D offset = new Vector2D(Math.cos(moonAngle), Math.sin(moonAngle)).scale(m.distanceKm());
                Vector2D orbitalVelocity = offset.normalize().perpendicular()
                        .scale(m.retrograde() ? -moonSpeed : moonSpeed);

                Vector2D moonPosition = planetPosition.add(offset);
                Vector2D moonVelocity = planetVelocity.add(orbitalVelocity);
                bodies.add(new Body(m.name(), moonMassInternal, m.radiusKm(), m.color(), moonPosition, moonVelocity));
                systemMomentum = systemMomentum.add(moonVelocity.scale(moonMassInternal));
            }
        }

        Vector2D sunVelocity = systemMomentum.scale(-1.0 / sunMassInternal);
        Body sun = new Body("Soleil", sunMassInternal, 696000, new Color(255, 214, 100), Vector2D.ZERO, sunVelocity);
        bodies.add(0, sun);

        return bodies;
    }

    private static Color randomColor() {
        return Color.getHSBColor(ThreadLocalRandom.current().nextFloat(), 0.65f, 1f);
    }

    private record CloudParticle(Vector2D position, double mass, double radius, Color color) {
    }

    private static List<Body> cloudBodies(double g) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = 400;
        double diskRadius = 100.0;
        double circularFraction = 0.7;

        List<CloudParticle> particles = new ArrayList<>();
        double totalMass = 0;
        for (int i = 0; i < count; i++) {
            // sqrt(random) gives uniform density across the disk's *area*, instead of
            // bunching particles up near the center the way a plain uniform radius would.
            double r = diskRadius * Math.sqrt(random.nextDouble());
            double angle = random.nextDouble() * 2 * Math.PI;
            Vector2D position = new Vector2D(Math.cos(angle), Math.sin(angle)).scale(r);
            double mass = 300 + random.nextDouble() * 1700;
            double radius = 0.8 + random.nextDouble() * 1.0;
            Color color = Color.getHSBColor(random.nextFloat(), 0.55f + random.nextFloat() * 0.3f, 1f);
            particles.add(new CloudParticle(position, mass, radius, color));
            totalMass += mass;
        }

        List<Body> bodies = new ArrayList<>();
        Vector2D totalMomentum = Vector2D.ZERO;
        int index = 1;
        for (CloudParticle p : particles) {
            double r = p.position().magnitude();
            // Approximates the enclosed mass as if it were a point at the center — not
            // exact for a distributed disk, but a fine starting point given every pair
            // will immediately start perturbing every other anyway once it's running.
            double circularSpeed = r > 0.1 ? Math.sqrt(g * totalMass / r) : 0;
            Vector2D tangent = r > 0.1 ? p.position().normalize().perpendicular() : Vector2D.ZERO;
            Vector2D velocity = tangent.scale(circularSpeed * circularFraction);

            Body body = new Body("Corps " + index++, p.mass(), p.radius(), p.color(), p.position(), velocity);
            bodies.add(body);
            totalMomentum = totalMomentum.add(velocity.scale(p.mass()));
        }

        Vector2D correction = totalMomentum.scale(-1.0 / totalMass);
        for (Body b : bodies) {
            b.velocity = b.velocity.add(correction);
        }

        return bodies;
    }

    // ---- Simulation loop ----

    private void onTick() {
        if (!paused) {
            double frameDt = TARGET_FRAME_SECONDS * simulationSpeed;
            double substepDt = frameDt / PHYSICS_SUBSTEPS_PER_FRAME;
            for (int i = 0; i < PHYSICS_SUBSTEPS_PER_FRAME; i++) {
                simulation.step(substepDt);
            }
            if (selectedBody != null && !simulation.getBodies().contains(selectedBody)) {
                setSelectedBody(null);
            }
            if (secondarySelectedBody != null && !simulation.getBodies().contains(secondarySelectedBody)) {
                secondarySelectedBody = null;
            }
        }
        repaint();
    }

    // ---- Coordinate transforms ----

    private Vector2D screenToWorld(Point p) {
        double originX = getWidth() / 2.0;
        double originY = getHeight() / 2.0;
        return new Vector2D((p.x - originX) / pixelsPerUnit + cameraCenter.x,
                -(p.y - originY) / pixelsPerUnit + cameraCenter.y);
    }

    private Point worldToScreen(Vector2D v) {
        double originX = getWidth() / 2.0;
        double originY = getHeight() / 2.0;
        return new Point((int) Math.round(originX + (v.x - cameraCenter.x) * pixelsPerUnit),
                (int) Math.round(originY - (v.y - cameraCenter.y) * pixelsPerUnit));
    }

    // ---- Rendering ----

    private void generateStarfield() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < STAR_COUNT; i++) {
            stars.add(new Star(random.nextDouble(), random.nextDouble(),
                    0.3f + random.nextFloat() * 0.5f, random.nextDouble() * Math.PI * 2,
                    1f + random.nextFloat() * 1.6f));
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawBackground(g2);

        if (showTrails) {
            for (Body body : simulation.getBodies()) {
                drawTrail(g2, body);
            }
        }
        for (Body body : simulation.getBodies()) {
            drawBody(g2, body);
        }
        if (dragging && !pressedOnExistingBody) {
            drawDragArrow(g2);
        }
        drawDistanceMeasurement(g2);

        drawHud(g2);
    }

    private void drawBackground(Graphics2D g2) {
        g2.setPaint(new GradientPaint(0, 0, Palette.SPACE_TOP, 0, getHeight(), Palette.SPACE_BOTTOM));
        g2.fillRect(0, 0, getWidth(), getHeight());

        double t = System.currentTimeMillis() / 1000.0;
        Composite original = g2.getComposite();
        g2.setColor(Color.WHITE);
        for (Star star : stars) {
            float alpha = (float) Math.max(0, Math.min(1, star.baseAlpha() + 0.25 * Math.sin(t * 1.5 + star.phase())));
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            double x = star.xFraction() * getWidth();
            double y = star.yFraction() * getHeight();
            g2.fill(new Ellipse2D.Double(x, y, star.size(), star.size()));
        }
        g2.setComposite(original);
    }

    private void drawTrail(Graphics2D g2, Body body) {
        List<Vector2D> points = new ArrayList<>(body.getTrail());
        int start = Math.max(0, points.size() - trailLength);
        int count = points.size() - start;
        if (count < 2) {
            return;
        }

        Composite originalComposite = g2.getComposite();
        g2.setStroke(new BasicStroke(2f));
        Point previous = worldToScreen(points.get(start));
        for (int i = start + 1; i < points.size(); i++) {
            Point current = worldToScreen(points.get(i));
            float alpha = (float) (i - start) / count;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * 0.7f));
            g2.setColor(body.color);
            g2.draw(new Line2D.Double(previous.x, previous.y, current.x, current.y));
            previous = current;
        }
        g2.setComposite(originalComposite);
    }

    private void drawBody(Graphics2D g2, Body body) {
        Point screen = worldToScreen(body.position);
        float radius = (float) Math.max(2, body.radius * pixelsPerUnit);
        Point2D.Float center = new Point2D.Float(screen.x, screen.y);

        float haloRadius = radius * 2.2f;
        g2.setPaint(new RadialGradientPaint(center, haloRadius, new float[] { 0f, 1f },
                new Color[] { Palette.withAlpha(body.color, 100), Palette.withAlpha(body.color, 0) }));
        g2.fill(new Ellipse2D.Double(screen.x - haloRadius, screen.y - haloRadius, haloRadius * 2, haloRadius * 2));

        g2.setPaint(new RadialGradientPaint(center, radius, new float[] { 0f, 1f },
                new Color[] { brighten(body.color), body.color }));
        g2.fill(new Ellipse2D.Double(screen.x - radius, screen.y - radius, radius * 2, radius * 2));

        if (body == selectedBody) {
            g2.setColor(Color.WHITE);
            g2.setStroke(
                    new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] { 4, 4 }, 0));
            float ring = radius + 6;
            g2.draw(new Ellipse2D.Double(screen.x - ring, screen.y - ring, ring * 2, ring * 2));
        }
        if (body == secondarySelectedBody) {
            g2.setColor(Palette.ACCENT);
            g2.setStroke(
                    new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] { 4, 4 }, 0));
            float ring = radius + (body == selectedBody ? 11 : 6);
            g2.draw(new Ellipse2D.Double(screen.x - ring, screen.y - ring, ring * 2, ring * 2));
        }

        g2.setColor(Palette.TEXT);
        g2.drawString(body.name, (float) (screen.x + radius + 6), (float) screen.y);
    }

    private void drawDistanceMeasurement(Graphics2D g2) {
        if (selectedBody == null || secondarySelectedBody == null) {
            return;
        }
        Point from = worldToScreen(selectedBody.position);
        Point to = worldToScreen(secondarySelectedBody.position);

        g2.setColor(Palette.withAlpha(Palette.ACCENT, 200));
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] { 6, 6 }, 0));
        g2.draw(new Line2D.Double(from.x, from.y, to.x, to.y));

        double distanceKm = selectedBody.position.subtract(secondarySelectedBody.position).magnitude();
        String label = String.format(Locale.US, "%,.0f km", distanceKm);
        int midX = (from.x + to.x) / 2;
        int midY = (from.y + to.y) / 2;

        FontMetrics metrics = g2.getFontMetrics();
        int textWidth = metrics.stringWidth(label);
        g2.setColor(Palette.withAlpha(Palette.PANEL_BG, 220));
        g2.fillRoundRect(midX - textWidth / 2 - 6, midY - metrics.getAscent() - 2, textWidth + 12,
                metrics.getHeight() + 4, 8, 8);
        g2.setColor(Palette.TEXT);
        g2.drawString(label, midX - textWidth / 2, midY);
    }

    private static Color brighten(Color c) {
        return new Color(Math.min(255, c.getRed() + 80), Math.min(255, c.getGreen() + 80),
                Math.min(255, c.getBlue() + 80));
    }

    private void drawDragArrow(Graphics2D g2) {
        Point from = worldToScreen(dragStartWorld);
        Point to = dragCurrentScreen;

        g2.setColor(Palette.withAlpha(Palette.ACCENT, 220));
        g2.setStroke(new BasicStroke(2f));
        g2.draw(new Line2D.Double(from.x, from.y, to.x, to.y));

        double angle = Math.atan2(to.y - from.y, to.x - from.x);
        double arrowSize = 10;
        for (double sign : new double[] { -1, 1 }) {
            double a = angle + sign * Math.toRadians(150);
            g2.draw(new Line2D.Double(to.x, to.y, to.x + arrowSize * Math.cos(a), to.y + arrowSize * Math.sin(a)));
        }

        Vector2D velocity = screenToWorld(to).subtract(dragStartWorld).scale(DRAG_VELOCITY_SCALE);
        double kmh = Units.kmPerSecondToKmPerHour(velocity.magnitude());
        g2.setColor(Palette.TEXT);
        g2.drawString(String.format(Locale.US, "v = %,.0f km/h", kmh), to.x + 12, to.y);
    }

    private void drawHud(Graphics2D g2) {
        Vector2D momentum = simulation.totalMomentum();
        double energy = simulation.totalEnergy();
        double energyDriftPercent = initialEnergy == 0 ? 0 : 100.0 * (energy - initialEnergy) / Math.abs(initialEnergy);

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.US, "t = %.2f s   x%.2f   %s",
                simulation.getElapsedTime(), simulationSpeed, paused ? "PAUSE" : ""));
        double gValue = simulation.getG();
        String gText = gValue >= 0.01 ? String.format(Locale.US, "%.2f", gValue)
                : String.format(Locale.US, "%.4e", gValue);
        lines.add(String.format(Locale.US, "G = %s   adoucissement = %.1f km   corps = %d",
                gText, simulation.getSoftening(), simulation.getBodies().size()));
        lines.add(pixelsPerUnit >= 1
                ? String.format(Locale.US, "Echelle : %.2f px/km", pixelsPerUnit)
                : String.format(Locale.US, "Echelle : %,.0f km/px", 1.0 / pixelsPerUnit));
        lines.add(String.format(Locale.US, "Energie totale = %.1f  (derive %.3f%%)", energy, energyDriftPercent));
        lines.add(String.format(Locale.US, "Quantite de mouvement = (%.3f, %.3f)", momentum.x, momentum.y));
        if (selectedBody != null && secondarySelectedBody != null) {
            double distanceKm = selectedBody.position.subtract(secondarySelectedBody.position).magnitude();
            lines.add(String.format(Locale.US, "Distance %s - %s = %,.0f km",
                    selectedBody.name, secondarySelectedBody.name, distanceKm));
        }

        String hint = "Glisser: deplacer/lancer | Shift-clic: 2e corps | Clic droit: panoramique | C: recentrer";

        int padding = 12;
        int lineHeight = 17;
        int boxWidth = 520;
        int boxHeight = padding * 2 + lineHeight * lines.size() + 10 + lineHeight;

        g2.setColor(Palette.withAlpha(Palette.PANEL_BG, 215));
        g2.fillRoundRect(14, 14, boxWidth, boxHeight, 14, 14);

        g2.setColor(Palette.TEXT);
        int y = 14 + padding + lineHeight - 5;
        for (String line : lines) {
            g2.drawString(line, 14 + padding, y);
            y += lineHeight;
        }

        g2.setColor(Palette.DIVIDER);
        g2.drawLine(14 + padding, y - 4, 14 + boxWidth - padding, y - 4);
        y += 6;

        g2.setColor(Palette.TEXT_MUTED);
        g2.drawString(hint, 14 + padding, y);
    }
}
