package dev.mineedge.client.movement;

/** Shared camera/model timing for a readable single-revolution landing roll. */
public final class RollPresentation {
    public static final double DURATION = 0.8;
    private static final double RAMP = 0.2;

    public static double turn(double progress) {
        progress = Math.clamp(progress, 0, 1);
        // Ease speed at the ends, then keep a steady pace through the middle rather
        // than concentrating most of the revolution into a brief high-speed burst.
        if (progress < RAMP) return entry(progress);
        if (progress > 1 - RAMP) return 1 - entry(1 - progress);
        return (progress - RAMP * 0.5) / (1 - RAMP);
    }

    private static double entry(double progress) {
        double fraction = progress / RAMP;
        return RAMP / (1 - RAMP) * fraction * fraction * fraction * (1 - fraction * 0.5);
    }

    private RollPresentation() {}
}
