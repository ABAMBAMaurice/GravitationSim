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
    public Vector2D position;
    public Vector2D velocity;
    public Vector2D acceleration = Vector2D.ZERO;

    private final Deque<Vector2D> trail = new ArrayDeque<>();

    public Body(String name, double mass, double radius, Color color, Vector2D position, Vector2D velocity) {
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

    public Deque<Vector2D> getTrail() {
        return trail;
    }

    public double speed() {
        return velocity.magnitude();
    }

    public double kineticEnergy() {
        return 0.5 * mass * velocity.magnitudeSquared();
    }
}
