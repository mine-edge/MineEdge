package dev.mineedge.movement;

/** World-space blocks or blocks/second, depending on the owning contract. */
public record MovementVector(double x, double y, double z) {
    public static final MovementVector ZERO = new MovementVector(0, 0, 0);

    public MovementVector add(MovementVector other) {
        return new MovementVector(x + other.x, y + other.y, z + other.z);
    }

    public MovementVector subtract(MovementVector other) {
        return new MovementVector(x - other.x, y - other.y, z - other.z);
    }

    public MovementVector scale(double factor) {
        return new MovementVector(x * factor, y * factor, z * factor);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double horizontalSpeed() {
        return Math.hypot(x, z);
    }

    public double dot(MovementVector other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public MovementVector normalized() {
        double length = length();
        return length < 1.0E-12 ? ZERO : scale(1 / length);
    }

    public MovementVector clampLength(double maximum) {
        double length = length();
        return length > maximum ? scale(maximum / length) : this;
    }

    public double axis(int axis) {
        return switch (axis) { case 0 -> x; case 1 -> y; case 2 -> z;
            default -> throw new IllegalArgumentException("Invalid axis"); };
    }

    public static MovementVector axis(int axis, double value) {
        return switch (axis) { case 0 -> new MovementVector(value, 0, 0);
            case 1 -> new MovementVector(0, value, 0); case 2 -> new MovementVector(0, 0, value);
            default -> throw new IllegalArgumentException("Invalid axis"); };
    }
}
