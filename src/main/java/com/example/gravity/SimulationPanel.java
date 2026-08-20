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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * A hand-rolled 3D engine drawn entirely with Graphics2D: an orbit camera
 * (azimuth/elevation
 * around a focus point, fixed distance), a perspective projection, and a
 * painter's-algorithm
 * depth sort. No extra rendering framework — this keeps the same fast,
 * GPU-accelerated
 * Java2D pipeline the 2D version already used.
 */
public final class SimulationPanel extends JPanel {

    // High enough that fast-orbiting moons (e.g. Phobos, 7h39 period) still get
    // ~100+ integration steps per orbit even at the solar-system preset's large
    // time acceleration; body counts here are small enough that this stays cheap.
    // This is the baseline, tuned against SUBSTEP_REFERENCE_SPEED — see
    // substepsForSpeed.
    private static final int PHYSICS_SUBSTEPS_PER_FRAME = 64;
    // The simulationSpeed the baseline substep count above was tuned against (the
    // star
    // system preset's default). Above this, substepsForSpeed scales substeps up so
    // faster
    // presets don't lose resolution on fast orbiters (e.g. Phobos, period ~7.6h).
    private static final double SUBSTEP_REFERENCE_SPEED = 1_000_000.0;
    private static final int MAX_SUBSTEP_MULTIPLIER = 4;
    private static final double TARGET_FRAME_SECONDS = 1.0 / 60.0;
    private static final double MAX_FRAME_DT = 0.1;
    private static final double DRAG_VELOCITY_SCALE = 3.0;
    private static final double DEFAULT_SPAWN_ROTATION_PERIOD_SECONDS = 6.0;
    private static final float MIN_RADIUS_FOR_ROTATION_MARKER = 6f;
    private static final float MIN_RADIUS_FOR_TEXTURE = 8f;
    private static final int MAX_TEXTURE_RENDER_SIZE = 260;
    private static final int GIZMO_RADIUS = 34;
    private static final int GIZMO_MARGIN = 60;
    private static final int STAR_COUNT = 180;
    private static final Color PHOTON_COLOR = new Color(255, 255, 240);
    // Presets are tuned in real kilometres; this keeps them readable on load
    // regardless of whatever zoom the user leaves pixelsPerUnit at otherwise.
    private static final double PRESET_PIXELS_PER_KM = 10.0;

    // Camera: orbits cameraCenter at a fixed scene-unit distance; pixelsPerUnit
    // (mouse
    // wheel) scales km into scene units exactly as it scaled km into pixels in the
    // 2D
    // version, so the camera distance doesn't need to change per preset.
    private static final double CAMERA_DISTANCE = 1400.0;
    private static final double FOCAL_LENGTH = 900.0;
    private static final double NEAR_CLIP = 5.0;
    private static final double DEFAULT_AZIMUTH = Math.toRadians(35);
    private static final double DEFAULT_ELEVATION = Math.toRadians(22);
    private static final double MAX_ELEVATION = Math.toRadians(85);

    private record Star(double xFraction, double yFraction, float baseAlpha, double phase, float size) {
    }

    private record Projected(double screenX, double screenY, double depth) {
    }

    private NewtonianSimulation simulation = new NewtonianSimulation(6000.0, new ArrayList<>());
    private Runnable currentPresetLoader = this::loadBinaryPreset;
    private final List<Star> stars = new ArrayList<>();

    private double initialEnergy;
    // Real (wall-clock) time the sim has been running, independent of
    // simulationSpeed —
    // accumulated directly from real frame durations rather than derived by
    // dividing
    // simulation.getElapsedTime() by the current speed, since that speed can change
    // mid-run (via the ControlPanel slider) and would then misrepresent past time.
    private double realElapsedSeconds;
    private long lastTickNanos = -1;
    private double simulationSpeed = 1.0;
    private double spawnMass = 2000;
    // Default: ~5 solar masses, radius close to its own Schwarzschild radius
    // (2GM/c^2 ~=
    // 14.8km for this mass) — a physically-plausible starting point the user can
    // then freely
    // detune away from (this sim has no relativity, so nothing enforces that
    // relationship).
    private double blackHoleMassKg = 1.0e31;
    private double blackHoleRadiusKm = 15.0;
    private double spawnRadius = 2.0;
    private boolean paused = false;
    // No longer a rendering gate itself (trail visibility is per-body, see
    // Body.showTrail) —
    // this is now just the default applied to bodies added via
    // drag-spawn/preset-insert.
    private boolean showTrails = false;
    private boolean showAllNames = false;
    private boolean spawnAsPhoton = false;
    private int trailLength = 0;
    private double pixelsPerUnit = Units.DEFAULT_PIXELS_PER_KM;
    private int spawnCounter = 1;
    private int photonCounter = 1;

    private Body selectedBody;
    private Body secondarySelectedBody;
    private Consumer<Body> onSelectionChanged = body -> {
    };
    private Runnable onPresetLoaded = () -> {
    };

    private double cameraAzimuth = DEFAULT_AZIMUTH;
    private double cameraElevation = DEFAULT_ELEVATION;
    private Vector3D cameraCenter = Vector3D.ZERO;
    private Body followedBody;

    // Camera basis, recomputed once per paint and reused by mouse handlers until
    // the next one.
    private Vector3D cameraPos = Vector3D.ZERO;
    private Vector3D camForward = new Vector3D(0, 0, 1);
    private Vector3D camRight = new Vector3D(1, 0, 0);
    private Vector3D camUp = new Vector3D(0, 1, 0);

    private Vector3D dragStartWorld;
    private Point dragStartScreen;
    private Point dragCurrentScreen;
    private boolean dragging;
    private boolean pressedOnExistingBody;

    private Point orbitStartScreen;
    private double orbitStartAzimuth;
    private double orbitStartElevation;
    private boolean orbitingCamera;

    private Point panStartScreen;
    private Vector3D panStartCameraCenter;
    private boolean panningCamera;

    // Two-step preset insertion: click a "Corps predefinis" button to arm
    // pendingSpawn, then
    // click (rest) or drag (with velocity) in the view to place it.
    // assigningVelocity arms a
    // similar drag gesture that sets an already-placed selected body's velocity
    // afterward.
    private PresetBodyKind pendingSpawn;
    private boolean assigningVelocity;

    private final Timer timer;

    public SimulationPanel() {
        setPreferredSize(new Dimension(1000, 750));
        setBackground(Palette.SPACE_TOP);
        setFocusable(true);
        generateStarfield();
        updateCameraBasis();

        loadBinaryPreset();

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_SPACE -> togglePause();
                    case KeyEvent.VK_R -> currentPresetLoader.run();
                    case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> deleteSelectedBody();
                    case KeyEvent.VK_C -> {
                        if (e.isShiftDown()) {
                            // Re-targets the orbit focus on the selected body without touching
                            // the current viewing angle, so you keep your orientation while
                            // framing a different body (unlike plain C, a full reset). A second
                            // Shift+C on the body already being followed releases the follow,
                            // leaving the camera parked at its last position.
                            if (followedBody != null && followedBody == selectedBody) {
                                followedBody = null;
                            } else if (selectedBody != null) {
                                followedBody = selectedBody;
                                cameraCenter = selectedBody.position;
                            }
                            repaint();
                        } else {
                            followedBody = null;
                            cameraCenter = Vector3D.ZERO;
                            cameraAzimuth = DEFAULT_AZIMUTH;
                            cameraElevation = DEFAULT_ELEVATION;
                            repaint();
                        }
                    }
                    default -> {
                    }
                }
            }
        });
        addMouseWheelListener(e -> {
            // getWheelRotation() rounds to an int and can stay 0 for gentle/smooth-scroll
            // gestures on precision touchpads (a known Windows/Swing quirk), silently
            // eating the zoom. getPreciseWheelRotation() reports the exact fractional
            // amount regardless of hardware, so it never drops small scrolls.
            double factor = Math.pow(1.1, -e.getPreciseWheelRotation());
            pixelsPerUnit = Math.max(1e-20, Math.min(200.0, pixelsPerUnit * factor));
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isRightMouseButton(e)) {
                    orbitingCamera = true;
                    orbitStartScreen = e.getPoint();
                    orbitStartAzimuth = cameraAzimuth;
                    orbitStartElevation = cameraElevation;
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && e.isControlDown()) {
                    // A manual pan overrides any active follow — otherwise the next frame's
                    // auto-recenter would immediately fight the drag.
                    followedBody = null;
                    panningCamera = true;
                    panStartScreen = e.getPoint();
                    panStartCameraCenter = cameraCenter;
                    return;
                }
                Point p = e.getPoint();
                if (assigningVelocity && selectedBody != null) {
                    // The drag can start anywhere — it's a pure direction+magnitude input
                    // (like a joystick), always anchored at the body's own position, not
                    // wherever the cursor happens to be.
                    dragStartWorld = selectedBody.position;
                    dragStartScreen = p;
                    dragCurrentScreen = p;
                    dragging = true;
                    pressedOnExistingBody = false;
                    return;
                }
                if (e.isShiftDown()) {
                    secondarySelectedBody = hitTestBody(p);
                    repaint();
                    return;
                }
                Body hit = hitTestBody(p);
                pressedOnExistingBody = hit != null;
                setSelectedBody(hit);
                dragStartWorld = hit != null ? hit.position : screenToWorldOnPlane(p, cameraCenter);
                dragStartScreen = p;
                dragCurrentScreen = p;
                dragging = true;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    orbitingCamera = false;
                    return;
                }
                if (panningCamera) {
                    // Checked via the flag (not button/modifier) since Ctrl may already be
                    // released by the time this fires, even though it was a left button.
                    panningCamera = false;
                    return;
                }
                if (assigningVelocity) {
                    assigningVelocity = false;
                    dragging = false;
                    if (selectedBody != null) {
                        Vector3D releaseWorld = screenToWorldOnPlane(e.getPoint(), dragStartWorld);
                        selectedBody.velocity = releaseWorld.subtract(dragStartWorld).scale(DRAG_VELOCITY_SCALE);
                        initialEnergy = simulation.totalEnergy();
                    }
                    repaint();
                    return;
                }
                dragging = false;
                if (pressedOnExistingBody) {
                    pressedOnExistingBody = false;
                    repaint();
                    return;
                }
                Point releaseScreen = e.getPoint();
                boolean isClick = releaseScreen.distance(dragStartScreen) < 5;
                if (pendingSpawn != null) {
                    // Preset bodies can be placed at rest (a plain click) as well as with an
                    // initial velocity (a drag) — the click case is the "insert without
                    // velocity, set direction/speed afterward" workflow.
                    Vector3D releaseWorld = screenToWorldOnPlane(releaseScreen, dragStartWorld);
                    Vector3D velocity = isClick ? Vector3D.ZERO
                            : releaseWorld.subtract(dragStartWorld).scale(DRAG_VELOCITY_SCALE);
                    Body placed = pendingSpawn.create(dragStartWorld, velocity);
                    placed.showTrail = showTrails;
                    simulation.addBody(placed);
                    initialEnergy = simulation.totalEnergy();
                    setSelectedBody(placed);
                    pendingSpawn = null;
                    repaint();
                    return;
                }
                if (isClick) {
                    repaint();
                    return;
                }
                Vector3D releaseWorld = screenToWorldOnPlane(releaseScreen, dragStartWorld);
                Body body;
                if (spawnAsPhoton) {
                    // A photon has no rest mass, so only the drag's direction matters —
                    // its speed is fixed at c, not scaled by how far you dragged.
                    Vector3D direction = releaseWorld.subtract(dragStartWorld).normalize();
                    Vector3D velocity = direction.scale(Units.SPEED_OF_LIGHT_KM_S);
                    body = new Body("Photon " + photonCounter++, 0, spawnRadius, PHOTON_COLOR, dragStartWorld,
                            velocity);
                } else {
                    Vector3D velocity = releaseWorld.subtract(dragStartWorld).scale(DRAG_VELOCITY_SCALE);
                    body = new Body("Ajout " + spawnCounter++, spawnMass, spawnRadius, randomColor(), dragStartWorld,
                            velocity);
                    body.rotationPeriodSeconds = DEFAULT_SPAWN_ROTATION_PERIOD_SECONDS;
                    body.surfaceSeed = ThreadLocalRandom.current().nextLong();
                }
                body.showTrail = showTrails;
                simulation.addBody(body);
                initialEnergy = simulation.totalEnergy();
                repaint();
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (orbitingCamera) {
                    double dx = e.getX() - orbitStartScreen.x;
                    double dy = e.getY() - orbitStartScreen.y;
                    cameraAzimuth = orbitStartAzimuth + dx * 0.008;
                    double newElevation = orbitStartElevation - dy * 0.008;
                    cameraElevation = Math.max(-MAX_ELEVATION, Math.min(MAX_ELEVATION, newElevation));
                    repaint();
                    return;
                }
                if (panningCamera) {
                    // Screen-space pan: shift the focus point so that, at its own depth
                    // (exactly CAMERA_DISTANCE from the camera), scene content tracks the
                    // cursor 1:1 — the same "grab the canvas" feel as the old 2D panning.
                    double dx = e.getX() - panStartScreen.x;
                    double dy = e.getY() - panStartScreen.y;
                    double sceneShiftRight = dx * CAMERA_DISTANCE / FOCAL_LENGTH;
                    double sceneShiftUp = -dy * CAMERA_DISTANCE / FOCAL_LENGTH;
                    Vector3D sceneShift = camRight.scale(sceneShiftRight).add(camUp.scale(sceneShiftUp));
                    cameraCenter = panStartCameraCenter.subtract(sceneShift.scale(1.0 / pixelsPerUnit));
                    repaint();
                    return;
                }
                dragCurrentScreen = e.getPoint();
                if (pressedOnExistingBody && selectedBody != null) {
                    selectedBody.position = screenToWorldOnPlane(dragCurrentScreen, dragStartWorld);
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

    public double getRotationSpeedMultiplier() {
        return simulation.getRotationSpeedMultiplier();
    }

    public void setRotationSpeedMultiplier(double multiplier) {
        simulation.setRotationSpeedMultiplier(multiplier);
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

    /**
     * Arms the next click/drag in the view to place this preset body (see
     * mousePressed/mouseReleased).
     */
    public void armPresetSpawn(PresetBodyKind kind) {
        this.pendingSpawn = kind;
        repaint();
    }

    /**
     * Arms the next drag in the view to set the currently selected body's
     * direction/speed. No-op if nothing is selected.
     */
    public void armVelocityAssignment() {
        if (selectedBody != null) {
            this.assigningVelocity = true;
            repaint();
        }
    }

    public void setBlackHoleMassKg(double massKg) {
        this.blackHoleMassKg = massKg;
    }

    public double getBlackHoleMassKg() {
        return blackHoleMassKg;
    }

    public void setBlackHoleRadiusKm(double radiusKm) {
        this.blackHoleRadiusKm = Math.max(1e-6, radiusKm);
    }

    public double getBlackHoleRadiusKm() {
        return blackHoleRadiusKm;
    }

    /**
     * kg/m^3 — a live readout next to the mass/radius sliders, not an independently
     * settable value.
     */
    public double getBlackHoleDensityKgPerM3() {
        double radiusM = blackHoleRadiusKm * 1000.0;
        double volumeM3 = (4.0 / 3.0) * Math.PI * radiusM * radiusM * radiusM;
        return volumeM3 > 0 ? blackHoleMassKg / volumeM3 : 0;
    }

    /**
     * Arms placement of a black hole built from the current mass/radius sliders
     * (see drawBlackHole for its look).
     */
    public void armBlackHoleSpawn() {
        this.pendingSpawn = new PresetBodyKind("Trou noir", blackHoleMassKg, blackHoleRadiusKm, Color.BLACK, 0);
        repaint();
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

    public void setShowAllNames(boolean show) {
        this.showAllNames = show;
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

    /**
     * Full reset: reloads the current preset from scratch (discarding any
     * added/moved
     * bodies and restoring G, speed, trail length etc. to that preset's defaults —
     * the
     * same mechanism the R key already uses), zeroing both the simulated and
     * real-elapsed
     * clocks along the way (see afterPresetLoaded), and pauses so the reset state
     * holds
     * until the user explicitly resumes.
     */
    public void resetSystem() {
        currentPresetLoader.run();
        paused = true;
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

    public void setSelectedBodyShowTrail(boolean show) {
        if (selectedBody != null) {
            selectedBody.showTrail = show;
            repaint();
        }
    }

    /**
     * Sets the selected body's speed while keeping its current direction of travel.
     */
    public void setSelectedBodySpeed(double speedKmPerSecond) {
        if (selectedBody != null) {
            Vector3D direction = selectedBody.velocity.magnitude() > 1e-9
                    ? selectedBody.velocity.normalize()
                    : new Vector3D(1, 0, 0);
            selectedBody.velocity = direction.scale(speedKmPerSecond);
            repaint();
        }
    }

    public void loadBinaryPreset() {
        currentPresetLoader = this::loadBinaryPreset;
        // A toy-scale value, not Units.G_INTERNAL_REAL: at this preset's toy
        // masses/distances
        // (thousands of mass-units, tens of km), the real gravitational constant
        // (~2e-9)
        // produces negligible acceleration — the orbit would be effectively frozen.
        // Tuned so
        // the pair completes a full circular orbit in ~3.5s at simulationSpeed=1.
        double g = Units.G_INTERNAL_REAL;
        simulation.setG(g);
        simulation.loadBodies(Presets.binaryBodies(g));
        simulation.setCollisionMergingEnabled(false);
        pixelsPerUnit = PRESET_PIXELS_PER_KM;
        simulationSpeed = 1.0;
        trailLength = 300;
        afterPresetLoaded();
    }

    public void loadThreeBodyPreset() {
        currentPresetLoader = this::loadThreeBodyPreset;
        // Toy-scale, same reasoning as loadBinaryPreset — real G would leave the
        // triangle
        // essentially frozen. Tuned for a ~3.5s period, matching the binary preset's
        // pace.
        double g = Units.G_INTERNAL_REAL;
        simulation.setG(g);
        simulation.loadBodies(Presets.threeBodyBodies(g));
        simulation.setCollisionMergingEnabled(false);
        pixelsPerUnit = PRESET_PIXELS_PER_KM;
        simulationSpeed = 1.0;
        trailLength = 300;
        afterPresetLoaded();
    }

    /**
     * The real Sun and its eight planets: real masses, real orbital distances and
     * inclinations, and G derived from the real gravitational constant (see
     * Units.G_INTERNAL_REAL) — so orbital periods come out physically correct
     * (Earth =
     * 365.2 simulated days, in simulated seconds). At 1x speed that's unwatchable
     * in
     * real time, hence the large default time acceleration.
     */
    public void loadStarSystemPreset() {
        currentPresetLoader = this::loadStarSystemPreset;
        double g = Units.G_INTERNAL_REAL;
        simulation.setG(g);
        simulation.loadBodies(Presets.realSolarSystemBodies(g));
        simulation.setCollisionMergingEnabled(false);
        pixelsPerUnit = 6e-8;
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
        simulation.loadBodies(Presets.photonDeflectionBodies());
        simulation.setCollisionMergingEnabled(false);
        pixelsPerUnit = 0.02;
        simulationSpeed = 0.015;
        trailLength = 300;
        afterPresetLoaded();
    }

    /**
     * ~400 bodies of varied mass scattered through a sphere, given a collective
     * rotation at
     * 70% of the (point-mass-approximated) circular speed — bound but sub-circular,
     * so the
     * cloud keeps mixing and migrating rather than settling into a static shape.
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
        // Toy-scale, same reasoning as loadBinaryPreset — real G would leave the cloud
        // essentially static instead of mixing/migrating as the class doc describes.
        double g = Units.G_INTERNAL_REAL;
        simulation.setG(g);
        simulation.loadBodies(Presets.cloudBodies(g));
        simulation.setCollisionMergingEnabled(true);
        pixelsPerUnit = 6.0;
        simulationSpeed = 1.0;
        trailLength = 80;
        afterPresetLoaded();
    }

    private void afterPresetLoaded() {
        initialEnergy = simulation.totalEnergy();
        realElapsedSeconds = 0;
        spawnCounter = 1;
        cameraCenter = Vector3D.ZERO;
        cameraAzimuth = DEFAULT_AZIMUTH;
        cameraElevation = DEFAULT_ELEVATION;
        followedBody = null;
        setSelectedBody(null);
        secondarySelectedBody = null;
        onPresetLoaded.run();
    }

    // ---- Selection ----

    private void setSelectedBody(Body body) {
        if (selectedBody != body) {
            selectedBody = body;
            onSelectionChanged.accept(body);
            // Selecting a body keeps the camera locked onto it every frame (see onTick),
            // so it appears to stand still while everything else moves around it.
            // Deselecting (body == null) releases the camera, which stays parked in place.
            followedBody = body;
            if (body != null) {
                cameraCenter = body.position;
            }
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
            Projected proj = project(body.position);
            if (proj.depth() < NEAR_CLIP) {
                continue;
            }
            double screenRadius = apparentRadius(body, proj) + 4;
            double distance = screenPoint.distance(proj.screenX(), proj.screenY());
            if (distance <= screenRadius && distance < closestDistance) {
                closest = body;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static Color randomColor() {
        return Color.getHSBColor(ThreadLocalRandom.current().nextFloat(), 0.65f, 1f);
    }

    // ---- Simulation loop ----

    private void onTick() {
        // Measured, not assumed: javax.swing.Timer's requested period is a nominal
        // target,
        // not a guarantee — e.g. (int)(1000.0/60) truncates to 16ms (a real ~62.5Hz,
        // not
        // 60Hz), and actual firing also jitters with EDT/render load. Adding a fixed
        // TARGET_FRAME_SECONDS per tick regardless would let both clocks drift out of
        // sync
        // with an actual stopwatch even at x1 speed. Clamped so a stall (e.g. the
        // window
        // being minimized) doesn't inject one huge catch-up jump into the physics.
        long now = System.nanoTime();
        double actualDt = lastTickNanos < 0 ? TARGET_FRAME_SECONDS : (now - lastTickNanos) / 1_000_000_000.0;
        lastTickNanos = now;
        actualDt = Math.min(actualDt, MAX_FRAME_DT);

        if (!paused) {
            realElapsedSeconds += actualDt;
            double frameDt = actualDt * simulationSpeed;
            int substeps = substepsForSpeed(simulationSpeed);
            double substepDt = frameDt / substeps;
            for (int i = 0; i < substeps; i++) {
                simulation.step(substepDt);
            }
            // Recorded once per frame, not per substep: with PHYSICS_SUBSTEPS_PER_FRAME as
            // high as 256 at extreme speeds, recording every substep would let the fixed
            // 500-point trail cap represent well under a second of real playback — visually
            // indistinguishable from no trail at all. One point per frame ties trail
            // history
            // to real time (500 points spans ~8s at 60fps) regardless of substep count.
            for (Body b : simulation.getBodies()) {
                b.recordTrailPoint();
            }
            if (selectedBody != null && !simulation.getBodies().contains(selectedBody)) {
                setSelectedBody(null);
            }
            if (secondarySelectedBody != null && !simulation.getBodies().contains(secondarySelectedBody)) {
                secondarySelectedBody = null;
            }
            if (followedBody != null && !simulation.getBodies().contains(followedBody)) {
                followedBody = null;
            }
        }
        // Skipped while actively dragging the followed body itself: recentering on its
        // live position mid-drag would shift the very reference frame the drag math
        // uses
        // for the next mouse position, compounding into a runaway feedback loop.
        if (followedBody != null && !(dragging && pressedOnExistingBody)) {
            cameraCenter = followedBody.position;
        }
        repaint();
    }

    /**
     * More substeps at higher simulationSpeed keep per-substep dt from growing
     * without
     * bound — otherwise fast orbiters (e.g. Phobos) go unresolved and fly apart
     * well before
     * reaching the top of the speed slider. Scaling as sqrt(speed) balances
     * resolution
     * against the O(bodies^2) cost of each extra substep. Never drops below the
     * original
     * tuned baseline, so every preset at or below the reference speed is
     * unaffected.
     */
    private static int substepsForSpeed(double speed) {
        double multiplier = Math.min(MAX_SUBSTEP_MULTIPLIER, Math.sqrt(speed / SUBSTEP_REFERENCE_SPEED));
        return (int) Math.round(Math.max(PHYSICS_SUBSTEPS_PER_FRAME, PHYSICS_SUBSTEPS_PER_FRAME * multiplier));
    }

    // ---- Camera and projection ----

    private void updateCameraBasis() {
        Vector3D worldUp = new Vector3D(0, 1, 0);
        Vector3D direction = new Vector3D(
                Math.cos(cameraElevation) * Math.sin(cameraAzimuth),
                Math.sin(cameraElevation),
                Math.cos(cameraElevation) * Math.cos(cameraAzimuth));
        Vector3D sceneCameraCenter = cameraCenter.scale(pixelsPerUnit);
        cameraPos = sceneCameraCenter.add(direction.scale(CAMERA_DISTANCE));
        camForward = sceneCameraCenter.subtract(cameraPos).normalize();
        camRight = camForward.cross(worldUp).normalize();
        camUp = camRight.cross(camForward);
    }

    private Projected project(Vector3D worldPointKm) {
        Vector3D scenePoint = worldPointKm.scale(pixelsPerUnit);
        Vector3D relative = scenePoint.subtract(cameraPos);
        double camX = relative.dot(camRight);
        double camY = relative.dot(camUp);
        double camZ = relative.dot(camForward);
        double originX = getWidth() / 2.0;
        double originY = getHeight() / 2.0;
        double screenX = originX + (camX / camZ) * FOCAL_LENGTH;
        double screenY = originY - (camY / camZ) * FOCAL_LENGTH;
        return new Projected(screenX, screenY, camZ);
    }

    private double apparentRadius(Body body, Projected proj) {
        return Math.max(2, (body.radius * pixelsPerUnit / proj.depth()) * FOCAL_LENGTH);
    }

    /**
     * Intersects the camera ray through screenPoint with the plane through
     * planePointKm facing the camera.
     */
    private Vector3D screenToWorldOnPlane(Point screenPoint, Vector3D planePointKm) {
        double originX = getWidth() / 2.0;
        double originY = getHeight() / 2.0;
        double ndcX = (screenPoint.x - originX) / FOCAL_LENGTH;
        double ndcY = -(screenPoint.y - originY) / FOCAL_LENGTH;
        Vector3D rayDir = camForward.add(camRight.scale(ndcX)).add(camUp.scale(ndcY)).normalize();

        Vector3D scenePlanePoint = planePointKm.scale(pixelsPerUnit);
        double denom = rayDir.dot(camForward);
        if (Math.abs(denom) < 1e-9) {
            return planePointKm;
        }
        double t = scenePlanePoint.subtract(cameraPos).dot(camForward) / denom;
        Vector3D sceneWorldPoint = cameraPos.add(rayDir.scale(t));
        return sceneWorldPoint.scale(1.0 / pixelsPerUnit);
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

        updateCameraBasis();
        drawBackground(g2);

        List<Body> bodies = simulation.getBodies();
        Map<Body, Projected> projections = new IdentityHashMap<>();
        for (Body body : bodies) {
            projections.put(body, project(body.position));
        }

        for (Body body : bodies) {
            if (body.showTrail) {
                drawTrail(g2, body);
            }
        }

        List<Body> drawOrder = new ArrayList<>(bodies);
        drawOrder.sort((a, b) -> Double.compare(projections.get(b).depth(), projections.get(a).depth()));
        for (Body body : drawOrder) {
            Projected proj = projections.get(body);
            if (proj.depth() >= NEAR_CLIP) {
                drawBody(g2, body, proj);
            }
        }

        if (dragging && !pressedOnExistingBody) {
            drawDragArrow(g2);
        }
        drawDistanceMeasurement(g2);

        drawHud(g2);
        drawAxisGizmo(g2);
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
        List<Vector3D> points = new ArrayList<>(body.getTrail());
        int start = Math.max(0, points.size() - trailLength);
        int count = points.size() - start;
        if (count < 2) {
            return;
        }

        Composite originalComposite = g2.getComposite();
        g2.setStroke(new BasicStroke(2f));
        Projected previous = project(points.get(start));
        for (int i = start + 1; i < points.size(); i++) {
            Projected current = project(points.get(i));
            if (previous.depth() >= NEAR_CLIP && current.depth() >= NEAR_CLIP) {
                float alpha = (float) (i - start) / count;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * 0.7f));
                g2.setColor(body.color);
                g2.draw(new Line2D.Double(previous.screenX(), previous.screenY(), current.screenX(),
                        current.screenY()));
            }
            previous = current;
        }
        g2.setComposite(originalComposite);
    }

    private void drawBody(Graphics2D g2, Body body, Projected proj) {
        float radius = (float) apparentRadius(body, proj);
        Point2D.Float center = new Point2D.Float((float) proj.screenX(), (float) proj.screenY());

        if (body.name.equals("Trou noir")) {
            drawBlackHole(g2, center, radius);
        } else {
            float haloRadius = radius * 2.2f;
            g2.setPaint(new RadialGradientPaint(center, haloRadius, new float[] { 0f, 1f },
                    new Color[] { Palette.withAlpha(body.color, 100), Palette.withAlpha(body.color, 0) }));
            g2.fill(new Ellipse2D.Double(center.x - haloRadius, center.y - haloRadius, haloRadius * 2, haloRadius * 2));

            boolean textured = radius >= MIN_RADIUS_FOR_TEXTURE
                    && (body.name.equals("Terre") || body.name.equals("Lune"));
            if (textured) {
                drawTexturedSphere(g2, body, center, radius);
            } else {
                g2.setPaint(new RadialGradientPaint(center, radius, new float[] { 0f, 1f },
                        new Color[] { brighten(body.color), body.color }));
                g2.fill(new Ellipse2D.Double(center.x - radius, center.y - radius, radius * 2, radius * 2));
                drawRotationMarkers(g2, body, center, radius);
            }
        }

        if (body == selectedBody) {
            g2.setColor(Color.WHITE);
            g2.setStroke(
                    new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] { 4, 4 }, 0));
            float ring = radius + 6;
            g2.draw(new Ellipse2D.Double(center.x - ring, center.y - ring, ring * 2, ring * 2));
        }
        if (body == secondarySelectedBody) {
            g2.setColor(Palette.ACCENT);
            g2.setStroke(
                    new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] { 4, 4 }, 0));
            float ring = radius + (body == selectedBody ? 11 : 6);
            g2.draw(new Ellipse2D.Double(center.x - ring, center.y - ring, ring * 2, ring * 2));
        }

        if (showAllNames || body == selectedBody || body == secondarySelectedBody) {
            g2.setColor(Palette.TEXT);
            g2.drawString(body.name, center.x + radius + 6, center.y);
        }
    }

    /**
     * A small marker on the body's "equator" (spin axis = world Y, matching the
     * flat XZ
     * orbit plane's up direction) at its current rotationAngle, projected via the
     * same 3D
     * pipeline as everything else — so it genuinely swings around and disappears
     * behind the
     * sphere as it spins, rather than being a flat screen-space decoration.
     */
    /**
     * A solid black event horizon with a bright, warm accretion-glow rim — distinct
     * from the
     * normal radial-gradient sphere look, since a black hole with body.color =
     * black would
     * otherwise just render as a nearly-invisible dark blob against the starfield.
     */
    private void drawBlackHole(Graphics2D g2, Point2D.Float center, float radius) {
        float haloRadius = radius * 3.2f;
        Color glow = new Color(255, 170, 70);
        g2.setPaint(new RadialGradientPaint(center, haloRadius, new float[] { 0f, 0.4f, 1f },
                new Color[] { Palette.withAlpha(glow, 110), Palette.withAlpha(glow, 45), Palette.withAlpha(glow, 0) }));
        g2.fill(new Ellipse2D.Double(center.x - haloRadius, center.y - haloRadius, haloRadius * 2, haloRadius * 2));

        g2.setColor(Color.BLACK);
        g2.fill(new Ellipse2D.Double(center.x - radius, center.y - radius, radius * 2, radius * 2));

        float strokeWidth = Math.max(1.2f, radius * 0.08f);
        float rimRadius = radius - strokeWidth / 2;
        g2.setColor(Palette.withAlpha(glow, 230));
        g2.setStroke(new BasicStroke(strokeWidth));
        g2.draw(new Ellipse2D.Double(center.x - rimRadius, center.y - rimRadius, rimRadius * 2, rimRadius * 2));
    }

    /**
     * A handful of small surface blotches at seeded (stable per body)
     * latitude/longitude
     * offsets, each projected via the same 3D pipeline as everything else and
     * culled to the
     * camera-facing hemisphere — cheaper than a full textured sphere but still
     * reads as a
     * genuine rotating, 3D surface rather than a flat screen-space decoration. Used
     * for every
     * rotating body except Earth/Moon, which get the full per-pixel texture
     * instead.
     */
    private void drawRotationMarkers(Graphics2D g2, Body body, Point2D.Float center, float radius) {
        if (body.rotationPeriodSeconds == 0 || radius < MIN_RADIUS_FOR_ROTATION_MARKER) {
            return;
        }
        java.util.Random random = new java.util.Random(body.surfaceSeed);
        int count = 3 + random.nextInt(2);
        for (int i = 0; i < count; i++) {
            double latRad = Math.toRadians(random.nextDouble() * 140 - 70);
            double lonOffset = Math.toRadians(random.nextDouble() * 360);
            double blotchLon = lonOffset + body.rotationAngle;

            Vector3D outward = new Vector3D(
                    Math.cos(latRad) * Math.cos(blotchLon),
                    Math.sin(latRad),
                    Math.cos(latRad) * Math.sin(blotchLon));
            // Visible only on the camera-facing hemisphere: a surface point's outward
            // normal
            // must point roughly toward the camera, i.e. against camForward (which points
            // from the camera into the scene). Approximated as body-independent since the
            // body's radius is tiny next to the camera distance.
            if (outward.dot(camForward) >= 0) {
                continue;
            }
            Vector3D markerWorldPos = body.position.add(outward.scale(body.radius));
            Projected markerProj = project(markerWorldPos);
            if (markerProj.depth() < NEAR_CLIP) {
                continue;
            }
            float markerRadius = Math.max(1.2f, radius * (0.10f + random.nextFloat() * 0.10f));
            Color markerColor = random.nextBoolean() ? body.color.darker().darker() : brighten(body.color);
            g2.setColor(Palette.withAlpha(markerColor, 190));
            g2.fill(new Ellipse2D.Double(markerProj.screenX() - markerRadius, markerProj.screenY() - markerRadius,
                    markerRadius * 2, markerRadius * 2));
        }
    }

    /**
     * Genuine per-pixel sphere texture mapping for Earth/Moon: for each pixel of
     * the body's
     * on-screen circle, back-project to the corresponding point on the unit sphere
     * (locally
     * near-orthographic — a reasonable approximation since the body is small on
     * screen
     * relative to the whole scene), convert to latitude/longitude relative to the
     * spin axis
     * (offset by the live rotationAngle), sample the procedural texture there, and
     * shade by
     * the real direction to the Sun — so Earth genuinely shows a day/night
     * terminator that
     * rotates as it spins, not a fixed painted-on highlight.
     */
    private void drawTexturedSphere(Graphics2D g2, Body body, Point2D.Float center, float radius) {
        boolean isEarth = body.name.equals("Terre");
        int[] texture = isEarth ? SurfaceTextures.EARTH_TEXTURE : SurfaceTextures.MOON_TEXTURE;
        int texW = isEarth ? SurfaceTextures.EARTH_TEX_W : SurfaceTextures.MOON_TEX_W;
        int texH = isEarth ? SurfaceTextures.EARTH_TEX_H : SurfaceTextures.MOON_TEX_H;

        Vector3D lightDir = findSunDirection(body);

        int size = Math.min(MAX_TEXTURE_RENDER_SIZE, (int) Math.ceil(radius * 2) + 2);
        double sphereRadiusPx = size / 2.0 - 1;
        int[] pixels = new int[size * size];

        for (int py = 0; py < size; py++) {
            double dy = (py - size / 2.0 + 0.5) / sphereRadiusPx;
            for (int px = 0; px < size; px++) {
                double dx = (px - size / 2.0 + 0.5) / sphereRadiusPx;
                double distSquared = dx * dx + dy * dy;
                if (distSquared > 1.0) {
                    continue;
                }
                double dz = Math.sqrt(1.0 - distSquared);
                Vector3D normal = camRight.scale(dx).add(camUp.scale(dy)).subtract(camForward.scale(dz));

                double lat = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, normal.y))));
                double worldLonDeg = Math.toDegrees(Math.atan2(normal.z, normal.x));
                double textureLonDeg = worldLonDeg - Math.toDegrees(body.rotationAngle);
                double u = (((textureLonDeg + 180) % 360 + 360) % 360) / 360.0;
                double v = (90 - lat) / 180.0;

                int tx = Math.min(texW - 1, (int) (u * texW));
                int ty = Math.min(texH - 1, (int) (v * texH));
                int rgb = texture[ty * texW + tx];

                double shade = lightDir == null ? 0.85 : Math.max(0.12, normal.dot(lightDir));
                int r = clampByte((int) (((rgb >> 16) & 0xFF) * shade));
                int g = clampByte((int) (((rgb >> 8) & 0xFF) * shade));
                int b = clampByte((int) ((rgb & 0xFF) * shade));
                pixels[py * size + px] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);

        // Drawn at the body's true apparent diameter, not at the (possibly capped)
        // buffer
        // size: at typical zoom levels size == radius*2 already and this is a 1:1 draw,
        // but
        // once radius exceeds MAX_TEXTURE_RENDER_SIZE/2 the buffer stays capped for
        // performance while the display size keeps growing — stretching it here (with
        // bilinear smoothing) keeps the texture's apparent size correct at any zoom,
        // instead
        // of it visibly stopping growing while the halo/selection ring around it kept
        // scaling.
        Object previousInterpolation = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int displayDiameter = Math.round(radius * 2);
        g2.drawImage(image, Math.round(center.x - radius), Math.round(center.y - radius),
                displayDiameter, displayDiameter, null);
        if (previousInterpolation != null) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previousInterpolation);
        }
    }

    /**
     * Null if the Sun isn't present in the current preset (only the star system has
     * one).
     */
    private Vector3D findSunDirection(Body from) {
        for (Body b : simulation.getBodies()) {
            if (b.name.equals("Soleil")) {
                return b.position.subtract(from.position).normalize();
            }
        }
        return null;
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private void drawDistanceMeasurement(Graphics2D g2) {
        if (selectedBody == null || secondarySelectedBody == null) {
            return;
        }
        Projected from = project(selectedBody.position);
        Projected to = project(secondarySelectedBody.position);
        if (from.depth() < NEAR_CLIP || to.depth() < NEAR_CLIP) {
            return;
        }

        g2.setColor(Palette.withAlpha(Palette.ACCENT, 200));
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] { 6, 6 }, 0));
        g2.draw(new Line2D.Double(from.screenX(), from.screenY(), to.screenX(), to.screenY()));

        double distanceKm = selectedBody.position.subtract(secondarySelectedBody.position).magnitude();
        String label = String.format(Locale.US, "%,.0f km", distanceKm);
        double midX = (from.screenX() + to.screenX()) / 2;
        double midY = (from.screenY() + to.screenY()) / 2;

        FontMetrics metrics = g2.getFontMetrics();
        int textWidth = metrics.stringWidth(label);
        g2.setColor(Palette.withAlpha(Palette.PANEL_BG, 220));
        g2.fillRoundRect((int) (midX - textWidth / 2.0 - 6), (int) (midY - metrics.getAscent() - 2), textWidth + 12,
                metrics.getHeight() + 4, 8, 8);
        g2.setColor(Palette.TEXT);
        g2.drawString(label, (float) (midX - textWidth / 2.0), (float) midY);
    }

    private static Color brighten(Color c) {
        return new Color(Math.min(255, c.getRed() + 80), Math.min(255, c.getGreen() + 80),
                Math.min(255, c.getBlue() + 80));
    }

    // Elapsed time treated as a calendar date/time counted up from year 0, day 1 of
    // month 1 —
    // gives correct calendar semantics (month 1-12, day 1-31 bounded by that
    // month's actual
    // length, leap years) for free via LocalDateTime instead of a fixed-length
    // approximation.
    private static final java.time.LocalDateTime ELAPSED_TIME_EPOCH = java.time.LocalDateTime.of(0, 1, 1, 0, 0, 0);

    private static String formatElapsedTime(double totalSeconds) {
        java.time.LocalDateTime dateTime = ELAPSED_TIME_EPOCH.plusSeconds((long) totalSeconds);
        return String.format(Locale.US, "%da %02dm %02dj %02d:%02d:%02d",
                dateTime.getYear(), dateTime.getMonthValue(), dateTime.getDayOfMonth(),
                dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond());
    }

    private void drawDragArrow(Graphics2D g2) {
        Projected from = project(dragStartWorld);
        if (from.depth() < NEAR_CLIP) {
            return;
        }
        Point to = dragCurrentScreen;

        g2.setColor(Palette.withAlpha(Palette.ACCENT, 220));
        g2.setStroke(new BasicStroke(2f));
        g2.draw(new Line2D.Double(from.screenX(), from.screenY(), to.x, to.y));

        double angle = Math.atan2(to.y - from.screenY(), to.x - from.screenX());
        double arrowSize = 10;
        for (double sign : new double[] { -1, 1 }) {
            double a = angle + sign * Math.toRadians(150);
            g2.draw(new Line2D.Double(to.x, to.y, to.x + arrowSize * Math.cos(a), to.y + arrowSize * Math.sin(a)));
        }

        Vector3D velocity = screenToWorldOnPlane(to, dragStartWorld).subtract(dragStartWorld)
                .scale(DRAG_VELOCITY_SCALE);
        double kmh = Units.kmPerSecondToKmPerHour(velocity.magnitude());
        g2.setColor(Palette.TEXT);
        g2.drawString(String.format(Locale.US, "v = %,.0f km/h", kmh), to.x + 12, to.y);
    }

    private void drawHud(Graphics2D g2) {
        Vector3D momentum = simulation.totalMomentum();
        double energy = simulation.totalEnergy();
        double energyDriftPercent = initialEnergy == 0 ? 0 : 100.0 * (energy - initialEnergy) / Math.abs(initialEnergy);

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.US, "t = %s   x%.2f   %s",
                formatElapsedTime(simulation.getElapsedTime()), simulationSpeed, paused ? "PAUSE" : ""));
        lines.add("Temps reel ecoule (sans multiplicateur) = " + formatElapsedTime(realElapsedSeconds));
        double gValue = simulation.getG();
        String gText = gValue >= 0.01 ? String.format(Locale.US, "%.2f", gValue)
                : String.format(Locale.US, "%.4e", gValue);
        lines.add(String.format(Locale.US, "G = %s   adoucissement = %.1f km   corps = %d",
                gText, simulation.getSoftening(), simulation.getBodies().size()));
        lines.add(pixelsPerUnit >= 1
                ? String.format(Locale.US, "Echelle : %.2f px/km", pixelsPerUnit)
                : String.format(Locale.US, "Echelle : %,.0f km/px", 1.0 / pixelsPerUnit));
        lines.add(String.format(Locale.US, "Energie totale = %.1f  (derive %.3f%%)", energy, energyDriftPercent));
        lines.add(String.format(Locale.US, "Quantite de mouvement = (%.3f, %.3f, %.3f)", momentum.x, momentum.y,
                momentum.z));
        if (selectedBody != null && secondarySelectedBody != null) {
            double distanceKm = selectedBody.position.subtract(secondarySelectedBody.position).magnitude();
            lines.add(String.format(Locale.US, "Distance %s - %s = %,.0f km",
                    selectedBody.name, secondarySelectedBody.name, distanceKm));
        }
        if (followedBody != null) {
            lines.add("Camera : suivi de " + followedBody.name);
        }
        if (pendingSpawn != null) {
            lines.add("Pret a inserer : " + pendingSpawn.name + " (clic = pose immobile, glisser = pose + vitesse)");
        }
        if (assigningVelocity && selectedBody != null) {
            lines.add("Glisser pour definir la direction et la vitesse de " + selectedBody.name);
        }

        String hint = "Clic: selectionner (suivi camera) | Glisser: deplacer/lancer | Shift-clic: 2e corps | "
                + "Clic droit: orbite | Ctrl+clic gauche: panoramique (relache le suivi) | C: recentrer | Shift+C: reprendre/relacher le suivi";

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

    private record GizmoAxis(String label, Vector3D direction, Color color) {
    }

    private static final GizmoAxis[] GIZMO_AXES = {
            new GizmoAxis("X", new Vector3D(1, 0, 0), new Color(230, 90, 90)),
            new GizmoAxis("Y", new Vector3D(0, 1, 0), new Color(110, 210, 110)),
            new GizmoAxis("Z", new Vector3D(0, 0, 1), new Color(100, 150, 240)),
    };

    /**
     * A small fixed-size orientation gizmo in the bottom-left corner: the world
     * X/Y/Z axes
     * projected through the camera's current orthonormal basis (no perspective
     * divide — a
     * pure orientation indicator, not a scene object), updating live as the camera
     * orbits.
     * Positive directions are bright, labeled dots; negative directions are dim,
     * unlabeled
     * hollow dots — the standard convention in 3D tools (Blender, CAD, etc.).
     */
    private void drawAxisGizmo(Graphics2D g2) {
        double cx = GIZMO_MARGIN;
        double cy = getHeight() - GIZMO_MARGIN;

        record Arm(double screenX, double screenY, double depth, Color color, String label, boolean positive) {
        }
        List<Arm> arms = new ArrayList<>();
        for (GizmoAxis axis : GIZMO_AXES) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                Vector3D dir = axis.direction().scale(sign);
                double camX = dir.dot(camRight);
                double camY = dir.dot(camUp);
                double camZ = dir.dot(camForward);
                double screenX = cx + camX * GIZMO_RADIUS;
                double screenY = cy - camY * GIZMO_RADIUS;
                arms.add(new Arm(screenX, screenY, camZ, axis.color(), axis.label(), sign > 0));
            }
        }
        // Painter's algorithm: farthest arm first so nearer ones draw on top where they
        // overlap.
        arms.sort((a, b) -> Double.compare(b.depth(), a.depth()));

        double backdropRadius = GIZMO_RADIUS + 16;
        g2.setColor(Palette.withAlpha(Palette.PANEL_BG, 160));
        g2.fill(new Ellipse2D.Double(cx - backdropRadius, cy - backdropRadius, backdropRadius * 2, backdropRadius * 2));

        g2.setStroke(new BasicStroke(2f));
        for (Arm arm : arms) {
            g2.setColor(Palette.withAlpha(arm.color(), arm.positive() ? 255 : 110));
            g2.draw(new Line2D.Double(cx, cy, arm.screenX(), arm.screenY()));
            if (arm.positive()) {
                float dotRadius = 6f;
                g2.fill(new Ellipse2D.Double(arm.screenX() - dotRadius, arm.screenY() - dotRadius,
                        dotRadius * 2, dotRadius * 2));
                g2.setColor(Color.WHITE);
                g2.drawString(arm.label(), (float) arm.screenX() - 4, (float) arm.screenY() + 4);
            } else {
                float dotRadius = 4f;
                g2.setColor(Palette.SPACE_TOP);
                g2.fill(new Ellipse2D.Double(arm.screenX() - dotRadius, arm.screenY() - dotRadius,
                        dotRadius * 2, dotRadius * 2));
                g2.setColor(Palette.withAlpha(arm.color(), 150));
                g2.draw(new Ellipse2D.Double(arm.screenX() - dotRadius, arm.screenY() - dotRadius,
                        dotRadius * 2, dotRadius * 2));
            }
        }
    }
}
