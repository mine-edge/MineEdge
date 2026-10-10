package dev.mineedge.movement;

/** Minecraft-native body policy. LOW is shared by sliding and clearance-forced crawling. */
public enum MovementBody {
    STANDING(1.8), CROUCHING(1.5), LOW(0.6);

    private final double height;

    MovementBody(double height) { this.height = height; }
    public double height() { return height; }
}
