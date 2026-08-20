package com.example.gravity;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;

public final class Body {

    private static final int MAX_TRAIL_POINTS = 500;

    public String name;
    public final Color color;

    public double mass;
    public double radius;
    public Vector3D position;
    public Vector3D velocity;
    public Vector3D acceleration = Vector3D.ZERO;

    /** Axial spin, purely visual (doesn't feed back into gravity). 0 = no rotation. Negative period = retrograde. */
    public double rotationAngle;
    public double rotationPeriodSeconds;
    /** Seeds a small deterministic set of surface markings (see SimulationPanel), stable frame to frame. */
    public long surfaceSeed;

    private final Deque<Vector3D> trail = new ArrayDeque<>();

    public Body(String name, double mass, double radius, Color color, Vector3D position, Vector3D velocity) {
        this.name = name;
        this.mass = mass;
        this.radius = radius;
        this.color = color;
        this.position = position;
        this.velocity = velocity;
    }

    void recordTrailPoint() {
        trail.addLast(position);
        if (trail.size() > MAX_TRAIL_POINTS) {
            trail.removeFirst();
        }
    }

    public Deque<Vector3D> getTrail() {
        return trail;
    }

    public double speed() {
        return velocity.magnitude();
    }

    public double kineticEnergy() {
        return 0.5 * mass * velocity.magnitudeSquared();
    }
}
