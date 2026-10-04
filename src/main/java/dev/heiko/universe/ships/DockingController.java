package dev.heiko.universe.ships;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Serialized authoritative pair controller, independent of world/chunk APIs.
 * Evidence must be computed by the server adapter, never accepted from a client packet.
 * Replay history is deliberately bounded without eviction: at capacity new operations are
 * rejected until an external durable checkpoint creates a new controller epoch.
 */
public final class DockingController {
    public enum Code { OK, INVALID, INCOMPATIBLE, BUSY, STALE, UNSAFE, EXPIRED, CONFLICT, CAPACITY }
    public record Result(Code code, DockConnection connection) {}
    public record Evidence(long firstRevision, long secondRevision, boolean permissionGranted,
                           boolean corridorClear, double gap, double axisErrorDegrees,
                           double relativeSpeed, boolean targetStable, boolean sealed,
                           boolean transferReady, boolean doorsClosed, boolean transfersFinished,
                           boolean departureClear, ShipPose relativePose) {
        public Evidence {
            if (!Double.isFinite(gap) || gap < 0 || !Double.isFinite(axisErrorDegrees)
                    || axisErrorDegrees < 0 || !Double.isFinite(relativeSpeed) || relativeSpeed < 0)
                throw new IllegalArgumentException("Invalid docking measurements");
        }
    }
    private record Reserve(UUID first, UUID second, ShipId root, long now, long lease, boolean permission) {}
    private record Step(UUID connection, long revision, DockConnection.State next, long now, Evidence evidence) {}
    private record Entry(Object command, Result result) {}
    private final Map<UUID, DockPort> ports = new HashMap<>();
    private final Map<UUID, DockConnection> connections = new HashMap<>();
    private final Map<ShipId, UUID> occupied = new HashMap<>();
    private final Map<UUID, Entry> operations = new HashMap<>();
    private final int operationLimit;

    public DockingController(int operationLimit) {
        if (operationLimit <= 0) throw new IllegalArgumentException("Operation limit");
        this.operationLimit = operationLimit;
    }

    /** Port edits invalidate any live pair and close its passage immediately. */
    public synchronized void putPort(DockPort port) {
        Objects.requireNonNull(port);
        DockPort old = ports.get(port.id());
        if (old != null) {
            if (old.equals(port)) return;
            if (!old.shipId().equals(port.shipId()) || !old.context().equals(port.context())
                    || port.revision() <= old.revision()) throw new IllegalArgumentException("Port identity/revision");
            UUID connectionId = occupied.get(port.shipId());
            if (connectionId != null) {
                DockConnection c = connections.get(connectionId);
                if (c.first().id().equals(port.id()) || c.second().id().equals(port.id()))
                    connections.put(c.id(), c.move(DockConnection.State.BLOCKED, c.relativePose()));
            }
        }
        ports.put(port.id(), port);
    }

    public synchronized Optional<DockConnection> connection(UUID id) { return Optional.ofNullable(connections.get(id)); }
    public synchronized Optional<UUID> occupiedBy(ShipId ship) { return Optional.ofNullable(occupied.get(ship)); }
    /** Includes failed commands; replays/conflicts/capacity rejections add no entries. */
    public synchronized int operationHistorySize() { return operations.size(); }
    public int operationHistoryLimit() { return operationLimit; }

    /** Recovery is fail-closed even when a saved pair claimed its passage was open. */
    public synchronized void restoreBlocked(DockConnection saved) {
        Objects.requireNonNull(saved);
        if (connections.containsKey(saved.id()) || saved.state() == DockConnection.State.RELEASED
                || occupied.containsKey(saved.first().shipId()) || occupied.containsKey(saved.second().shipId()))
            throw new IllegalArgumentException("Conflicting recovered pair");
        DockConnection blocked = saved.move(DockConnection.State.BLOCKED, saved.relativePose());
        connections.put(blocked.id(), blocked);
        occupied.put(blocked.first().shipId(), blocked.id()); occupied.put(blocked.second().shipId(), blocked.id());
    }

    public synchronized Result reserve(UUID operation, UUID first, UUID second, ShipId root,
                                       long now, long lease, boolean permission) {
        Objects.requireNonNull(operation); Objects.requireNonNull(first); Objects.requireNonNull(second);
        Objects.requireNonNull(root);
        Reserve command = new Reserve(first, second, root, now, lease, permission);
        Result replay = replay(operation, command);
        if (replay != null) return replay;
        if (operations.size() >= operationLimit) return new Result(Code.CAPACITY, null);
        DockPort a = ports.get(first), b = ports.get(second);
        Result result;
        if (connections.containsKey(operation) || now < 0 || lease <= 0 || now > Long.MAX_VALUE - lease || a == null || b == null
                || (!root.equals(a.shipId()) && !root.equals(b.shipId()))) result = new Result(Code.INVALID, null);
        else if (!a.compatibleWith(b)) result = new Result(Code.INCOMPATIBLE, null);
        else if (!permission) result = new Result(Code.UNSAFE, null);
        else if (occupied.containsKey(a.shipId()) || occupied.containsKey(b.shipId())) result = new Result(Code.BUSY, null);
        else {
            DockConnection c = new DockConnection(operation, a, b, root, DockConnection.State.RESERVED, now+lease, 0, null);
            connections.put(c.id(), c);
            occupied.put(a.shipId(), c.id()); occupied.put(b.shipId(), c.id());
            result = new Result(Code.OK, c);
        }
        operations.put(operation, new Entry(command, result));
        return result;
    }

    /** expectedRevision prevents late measurements from authorizing a newer state. */
    public synchronized Result advance(UUID operation, UUID connection, long expectedRevision,
                                       DockConnection.State next, long now, Evidence evidence) {
        Objects.requireNonNull(operation); Objects.requireNonNull(connection); Objects.requireNonNull(next);
        Step command = new Step(connection, expectedRevision, next, now, evidence);
        Result replay = replay(operation, command);
        if (replay != null) return replay;
        if (operations.size() >= operationLimit) return new Result(Code.CAPACITY, null);
        DockConnection c = connections.get(connection);
        Result result;
        if (c == null || now < 0) result = new Result(Code.INVALID, c);
        else if (c.revision() != expectedRevision) result = new Result(Code.STALE, c);
        else if (c.state() == DockConnection.State.RELEASED) result = new Result(Code.INVALID, c);
        else if (next == DockConnection.State.BLOCKED) result = update(c, next, c.relativePose(), Code.OK);
        else if (preCapture(c.state()) && now >= c.expiresAt())
            result = update(c, DockConnection.State.RELEASED, c.relativePose(), Code.EXPIRED);
        else if (next == DockConnection.State.RELEASED && preCapture(c.state()))
            result = update(c, next, c.relativePose(), Code.OK);
        else if (next == DockConnection.State.RELEASED) {
            if (evidence == null || !evidence.doorsClosed || !evidence.transfersFinished || !evidence.departureClear)
                result = update(c, DockConnection.State.BLOCKED, c.relativePose(), Code.UNSAFE);
            else result = update(c, next, c.relativePose(), Code.OK);
        } else if (!legal(c.state(), next)) result = new Result(Code.INVALID, c);
        else if (!current(c, evidence)) result = update(c, DockConnection.State.BLOCKED, c.relativePose(), Code.STALE);
        else if (!safe(next, evidence)) result = update(c, DockConnection.State.BLOCKED, c.relativePose(), Code.UNSAFE);
        else if (next == DockConnection.State.CAPTURED && (evidence.relativePose == null
                || !evidence.relativePose.context().equals(c.first().context()))) result = new Result(Code.INVALID, c);
        else result = update(c, next, next == DockConnection.State.CAPTURED ? evidence.relativePose : c.relativePose(), Code.OK);
        operations.put(operation, new Entry(command, result));
        return result;
    }
    private Result replay(UUID id, Object command) {
        Entry e = operations.get(id);
        return e == null ? null : e.command.equals(command) ? e.result : new Result(Code.CONFLICT, null);
    }
    private boolean current(DockConnection c, Evidence e) {
        return e != null && c.first().equals(ports.get(c.first().id())) && c.second().equals(ports.get(c.second().id()))
                && e.firstRevision == c.first().revision() && e.secondRevision == c.second().revision();
    }
    private static boolean preCapture(DockConnection.State state) {
        return state == DockConnection.State.RESERVED || state == DockConnection.State.APPROACH || state == DockConnection.State.ALIGNING;
    }
    private static boolean legal(DockConnection.State from, DockConnection.State to) {
        return switch (from) {
            case RESERVED -> to == DockConnection.State.APPROACH;
            case APPROACH -> to == DockConnection.State.ALIGNING;
            case ALIGNING -> to == DockConnection.State.CAPTURED;
            case CAPTURED -> to == DockConnection.State.SEALED;
            case SEALED -> to == DockConnection.State.DOCKED;
            default -> false;
        };
    }
    private static boolean safe(DockConnection.State next, Evidence e) {
        if (!e.permissionGranted || !e.targetStable || !e.corridorClear) return false;
        return switch (next) {
            case CAPTURED -> e.gap <= 1 && e.axisErrorDegrees <= 3 && e.relativeSpeed <= .5 && e.doorsClosed;
            case SEALED -> e.sealed && e.doorsClosed;
            case DOCKED -> e.sealed && e.transferReady;
            default -> true;
        };
    }
    private Result update(DockConnection c, DockConnection.State next, ShipPose relative, Code code) {
        DockConnection changed = c.move(next, relative);
        connections.put(c.id(), changed);
        if (next == DockConnection.State.RELEASED) {
            occupied.remove(c.first().shipId(), c.id()); occupied.remove(c.second().shipId(), c.id());
        }
        return new Result(code, changed);
    }
}
