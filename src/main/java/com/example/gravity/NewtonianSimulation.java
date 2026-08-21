package com.example.gravity;

import java.util.List;

/**
 * Integrates Newton's law of universal gravitation (F = G m1 m2 / r^2) for an
 * arbitrary set of bodies using velocity Verlet, a symplectic integrator that
 * keeps total energy bounded over long simulated times instead of drifting
 * away as plain Euler integration would.
 */
public final class NewtonianSimulation {

    private double g;
    private double softening = 0;
    private boolean collisionMergingEnabled = true;
    // Purely visual multiplier on axial spin rate — independent of the orbital time
    // acceleration (simulationSpeed), since rotation never feeds back into gravity.
    private double rotationSpeedMultiplier = 1.0;
    private final List<Body> bodies;
    private double elapsedTime;

    public NewtonianSimulation(double g, List<Body> bodies) {
        this.g = g;
        this.bodies = bodies;
        recomputeAccelerations();
    }

    public List<Body> getBodies() {
        return bodies;
    }

    public double getG() {
        return g;
    }

    public void setG(double g) {
        this.g = g;
    }

    public double getSoftening() {
        return softening;
    }

    public void setSoftening(double softening) {
        this.softening = Math.max(0, softening);
    }

    public boolean isCollisionMergingEnabled() {
        return collisionMergingEnabled;
    }

    public void setCollisionMergingEnabled(boolean enabled) {
        this.collisionMergingEnabled = enabled;
    }

    public double getRotationSpeedMultiplier() {
        return rotationSpeedMultiplier;
    }

    public void setRotationSpeedMultiplier(double multiplier) {
        this.rotationSpeedMultiplier = multiplier;
    }

    public void addBody(Body body) {
        bodies.add(body);
        recomputeAccelerations();
    }

    public void removeBody(Body body) {
        bodies.remove(body);
    }

    public void clearBodies() {
        bodies.clear();
    }

    public void loadBodies(List<Body> newBodies) {
        bodies.clear();
        bodies.addAll(newBodies);
        recomputeAccelerations();
        elapsedTime = 0;
    }

    public double getElapsedTime() {
        return elapsedTime;
    }

    /** Zeroes the simulated clock without touching bodies, G, or any other state. */
    public void resetElapsedTime() {
        elapsedTime = 0;
    }

    public void step(double dt) {
        if (handleCollisions()) {
            // A merge changed a survivor's mass/position without touching its stored
            // acceleration, which was computed for its old (pre-merge) self. Using that
            // stale value below would inject a phantom, un-cancelled kick — silently
            // breaking momentum conservation — so refresh it before integrating.
            recomputeAccelerations();
        }

        Vector3D[] previousAcceleration = new Vector3D[bodies.size()];
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            previousAcceleration[i] = b.acceleration;
            b.position = b.position
                    .add(b.velocity.scale(dt))
                    .add(b.acceleration.scale(0.5 * dt * dt));
        }

        recomputeAccelerations();

        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            b.velocity = b.velocity.add(previousAcceleration[i].add(b.acceleration).scale(0.5 * dt));
            if (b.rotationPeriodSeconds != 0) {
                double angularSpeed = 2 * Math.PI / b.rotationPeriodSeconds;
                b.rotationAngle = (b.rotationAngle + angularSpeed * dt * rotationSpeedMultiplier) % (2 * Math.PI);
            }
        }

        elapsedTime += dt;
    }

    private void recomputeAccelerations() {
        for (Body b : bodies) {
            b.acceleration = Vector3D.ZERO;
        }
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                Body a = bodies.get(i);
                Body b = bodies.get(j);

                Vector3D delta = b.position.subtract(a.position);
                // Plummer softening: F = G m1 m2 r / (r^2 + eps^2)^1.5, the standard
                // way N-body codes avoid a diverging force at very close encounters
                // while leaving distant orbits (r >> eps) essentially unchanged.
                double softenedSquared = delta.magnitudeSquared() + softening * softening + 1e-6;
                double softenedDistance = Math.sqrt(softenedSquared);
                Vector3D direction = delta.scale(1.0 / softenedDistance);

                // Acceleration a = G*otherMass/r^2 doesn't involve the accelerated body's
                // own mass at all (it would cancel out of F=ma), so computing it this way
                // — instead of a shared force divided by each mass — stays correct even
                // for a massless body (e.g. a photon), which a force-based formula could
                // only reach via a 0/0 division.
                a.acceleration = a.acceleration.add(direction.scale(g * b.mass / softenedSquared));
                b.acceleration = b.acceleration.subtract(direction.scale(g * a.mass / softenedSquared));
            }
        }
    }

    private boolean handleCollisions() {
        if (!collisionMergingEnabled) {
            return false;
        }
        boolean anyMerged = false;
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = bodies.size() - 1; j > i; j--) {
                Body a = bodies.get(i);
                Body b = bodies.get(j);
                double distance = a.position.subtract(b.position).magnitude();
                if (distance < a.radius + b.radius) {
                    merge(a, b);
                    bodies.remove(j);
                    anyMerged = true;
                }
            }
        }
        return anyMerged;
    }

    /**
     * Merges b into a: conserves total mass, momentum and volume (assuming equal
     * density).
     */
    private static void merge(Body a, Body b) {
        double totalMass = a.mass + b.mass;
        double mergedRadius = Math.cbrt(Math.pow(a.radius, 3) + Math.pow(b.radius, 3));

        if (totalMass > 0) {
            Vector3D momentum = a.velocity.scale(a.mass).add(b.velocity.scale(b.mass));
            a.position = a.position.scale(a.mass / totalMass).add(b.position.scale(b.mass / totalMass));
            a.velocity = momentum.scale(1.0 / totalMass);
        }
        // else: two massless bodies (e.g. photons) colliding — nothing to weight by,
        // so a simply keeps its own position/velocity instead of a 0/0 division.
        a.mass = totalMass;
        a.radius = mergedRadius;
    }

    public double totalKineticEnergy() {
        double sum = 0;
        for (Body b : bodies) {
            sum += b.kineticEnergy();
        }
        return sum;
    }

    public double totalPotentialEnergy() {
        double sum = 0;
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                Body a = bodies.get(i);
                Body b = bodies.get(j);
                double distanceSquared = a.position.subtract(b.position).magnitudeSquared();
                double softenedDistance = Math.sqrt(distanceSquared + softening * softening + 1e-6);
                sum -= g * a.mass * b.mass / softenedDistance;
            }
        }
        return sum;
    }

    public double totalEnergy() {
        return totalKineticEnergy() + totalPotentialEnergy();
    }

    public Vector3D totalMomentum() {
        Vector3D sum = Vector3D.ZERO;
        for (Body b : bodies) {
            sum = sum.add(b.velocity.scale(b.mass));
        }
        return sum;
    }

    public Vector3D centerOfMass() {
        double totalMass = 0;
        Vector3D weighted = Vector3D.ZERO;
        for (Body b : bodies) {
            weighted = weighted.add(b.position.scale(b.mass));
            totalMass += b.mass;
        }
        return weighted.scale(1.0 / totalMass);
    }
}
