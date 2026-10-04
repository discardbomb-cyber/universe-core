package dev.heiko.universe.lod;

/** One instance per viewer/object. Call with monotonic server ticks and local-space distance. */
public final class LodHysteresis {
    private final double enter, leave;
    private final long delay;
    private boolean detailed;
    private long outsideSince = -1, lastTick = -1;
    public LodHysteresis(double enter, double leave, long delay) {
        if (!Double.isFinite(enter) || !Double.isFinite(leave) || enter < 0 || leave <= enter || delay < 0)
            throw new IllegalArgumentException();
        this.enter = enter; this.leave = leave; this.delay = delay;
    }
    public boolean update(double distance, long tick) {
        if (!Double.isFinite(distance) || distance < 0 || tick < 0 || tick < lastTick)
            throw new IllegalArgumentException();
        lastTick = tick;
        if (!detailed) {
            if (distance <= enter) detailed = true;
        } else if (distance > leave) {
            if (outsideSince < 0) outsideSince = tick;
            if (tick - outsideSince >= delay) { detailed = false; outsideSince = -1; }
        } else outsideSince = -1;
        return detailed;
    }
}
