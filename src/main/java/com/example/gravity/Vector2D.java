package com.example.gravity;

public final class Vector2D {

    public static final Vector2D ZERO = new Vector2D(0, 0);

    public final double x;
    public final double y;

    public Vector2D(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public Vector2D add(Vector2D other) {
        return new Vector2D(x + other.x, y + other.y);
    }

    public Vector2D subtract(Vector2D other) {
        return new Vector2D(x - other.x, y - other.y);
    }

    public Vector2D scale(double factor) {
        return new Vector2D(x * factor, y * factor);
    }

    public double magnitude() {
        return Math.sqrt(x * x + y * y);
    }

    public double magnitudeSquared() {
        return x * x + y * y;
    }

    public Vector2D normalize() {
        double m = magnitude();
        return m == 0 ? ZERO : new Vector2D(x / m, y / m);
    }

    public Vector2D perpendicular() {
        return new Vector2D(-y, x);
    }
}
