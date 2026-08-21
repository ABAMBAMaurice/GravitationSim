package com.example.gravity;

public final class Vector3D {

    public static final Vector3D ZERO = new Vector3D(0, 0, 0);

    public final double x;
    public final double y;
    public final double z;

    public Vector3D(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3D add(Vector3D other) {
        return new Vector3D(x + other.x, y + other.y, z + other.z);
    }

    public Vector3D subtract(Vector3D other) {
        return new Vector3D(x - other.x, y - other.y, z - other.z);
    }

    public Vector3D scale(double factor) {
        return new Vector3D(x * factor, y * factor, z * factor);
    }

    public double magnitude() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double magnitudeSquared() {
        return x * x + y * y + z * z;
    }

    public Vector3D normalize() {
        double m = magnitude();
        return m == 0 ? ZERO : new Vector3D(x / m, y / m, z / m);
    }

    public double dot(Vector3D other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vector3D cross(Vector3D other) {
        return new Vector3D(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x);
    }

    /**
     * A vector perpendicular to this one within the XZ (horizontal) plane — used to build a
     * circular-orbit tangent direction before any inclination is applied via rotateAroundX.
     */
    public Vector3D perpendicularXZ() {
        return new Vector3D(-z, 0, x);
    }

    /** Rotates this vector around the X axis — used to tilt an orbit out of the XZ plane. */
    public Vector3D rotateAroundX(double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new Vector3D(x, y * cos - z * sin, y * sin + z * cos);
    }
}
