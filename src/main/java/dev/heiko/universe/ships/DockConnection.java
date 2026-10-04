package dev.heiko.universe.ships;

import java.util.Objects;
import java.util.UUID;

/** Immutable snapshot. BLOCKED always means closed passage and retained reservation. */
public record DockConnection(UUID id, DockPort first, DockPort second, ShipId root,
                             State state, long expiresAt, long revision, ShipPose relativePose) {
    public enum State { RESERVED, APPROACH, ALIGNING, CAPTURED, SEALED, DOCKED, BLOCKED, RELEASED }
    public DockConnection {
        Objects.requireNonNull(id); Objects.requireNonNull(first); Objects.requireNonNull(second);
        Objects.requireNonNull(root); Objects.requireNonNull(state);
        if (!first.compatibleWith(second) || (!root.equals(first.shipId()) && !root.equals(second.shipId()))
                || revision < 0 || expiresAt < 0) throw new IllegalArgumentException("Invalid connection");
        if ((state == State.CAPTURED || state == State.SEALED || state == State.DOCKED) && relativePose == null)
            throw new IllegalArgumentException("Captured connection needs relative pose");
        if (relativePose != null && !relativePose.context().equals(first.context()))
            throw new IllegalArgumentException("Relative pose context");
    }
    public boolean passageOpen() { return state == State.DOCKED; }
    public DockConnection move(State next, ShipPose relative) {
        return new DockConnection(id, first, second, root, next, expiresAt, Math.addExact(revision,1), relative);
    }
}
