package dev.mineedge.integration;

/** Opt-in, current-thread counters for the transformed-world benchmark. No timing on normal play. */
public final class MovementProfile {
    private static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    public static final class Sample {
        public long queries, gathers, boxes, gatherNanos, cacheHits;
    }
    public static Sample begin() {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested movement profile");
        var sample = new Sample();
        CURRENT.set(sample);
        return sample;
    }
    public static void end() { CURRENT.remove(); }
    public static void query() {
        Sample sample = CURRENT.get();
        if (sample != null) sample.queries++;
    }
    public static void hit() {
        Sample sample = CURRENT.get();
        if (sample != null) sample.cacheHits++;
    }
    public static long startGather() { return CURRENT.get() == null ? 0 : System.nanoTime(); }
    public static void gathered(long start, int count) {
        Sample sample = CURRENT.get();
        if (sample != null) {
            sample.gathers++;
            sample.boxes += count;
            sample.gatherNanos += System.nanoTime() - start;
        }
    }
    private MovementProfile() {}
}
