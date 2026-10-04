package dev.heiko.universe.integration.gravity;

import dev.heiko.universe.api.ApiIds;
import dev.heiko.universe.api.gravity.GravitySample;
import dev.heiko.universe.api.gravity.VerticalGravityProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in, server-thread-only milestone A adapter. Hosts explicitly apply/update/release;
 * no listeners, global ticks or automatic dimension/death cleanup are installed.
 * Players (including ServerPlayer) are refused until actual client prediction is tested.
 * Only the listed vanilla ground mobs and ordinary dry, unmounted movement are supported.
 * A sample names a host-verified fixed-world-Y frame: matching its ID is not proof that
 * an arbitrary ship/rotating frame is suitable. The host supplies that proof.
 *
 * <p>One transient ADD_MULTIPLIED_TOTAL modifier targets effective gravity. Minecraft
 * 1.21.1 AttributeInstance calculates add-value, multiplied-base, multiplied-total, then
 * clamps. A detached AttributeInstance uses that actual implementation to evaluate the
 * current foreign state without ours. Clamping/nonlinear conflicts are detected by
 * reading back the live result; they are refused rather than overwriting foreign state.
 * Never changes base gravity, NoGravity or any modifier it does not own by identity.
 *
 * <p>Keep one adapter per server/host registration domain. Calling release is mandatory
 * before abandoning registrations. Full lifecycle milestone B is intentionally absent.
 */
public final class LivingGravityAdapter {
    public static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("universe", "living_gravity_target");
    public static final int MAX_OWNERS = 1024;
    private static final double VALUE_TOLERANCE = 1.0e-10;
    private static final Set<EntityType<?>> SUPPORTED_TYPES = Set.of(
            EntityType.PIG, EntityType.COW, EntityType.SHEEP, EntityType.ZOMBIE,
            EntityType.HUSK, EntityType.SKELETON, EntityType.STRAY,
            EntityType.CREEPER, EntityType.VILLAGER);
    private final MinecraftServer server;
    private final IdentityHashMap<LivingEntity, Ownership> owners = new IdentityHashMap<>();

    public LivingGravityAdapter(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public enum Result {
        APPLIED, RELEASED, NOT_OWNED, WRONG_THREAD, WRONG_SERVER, INVALID_ENTITY,
        PLAYER_UNSUPPORTED, UNSUPPORTED_MOVEMENT, INVALID_SAMPLE, FRAME_MISMATCH,
        NO_GRAVITY, MISSING_ATTRIBUTE, MODIFIER_CONFLICT, ZERO_BASELINE,
        VALUE_MISMATCH, CAPACITY_REACHED
    }

    private record Ownership(AttributeInstance attribute, AttributeModifier modifier) {}

    /**
     * Updates the target without stacking. On a same-server-thread refusal any still-owned
     * modifier is released. Off-thread/wrong-server calls make no mutations at all.
     * Reapply explicitly after foreign attribute changes; no automatic tracking is installed.
     */
    public Result apply(LivingEntity entity, GravitySample sample, String expectedFrameId) {
        if (!server.isSameThread()) return Result.WRONG_THREAD;
        if (entity == null) return Result.INVALID_ENTITY;
        if (!(entity.level() instanceof ServerLevel level) || level.getServer() != server) {
            return Result.WRONG_SERVER;
        }
        if (entity instanceof Player) return refuse(entity, Result.PLAYER_UNSUPPORTED);
        if (!(entity instanceof Mob) || !SUPPORTED_TYPES.contains(entity.getType())
                || !entity.getClass().getName().startsWith("net.minecraft.world.entity.")
                || !entity.isAlive() || entity.isRemoved()) {
            return refuse(entity, Result.INVALID_ENTITY);
        }
        if (entity.isNoGravity()) return refuse(entity, Result.NO_GRAVITY);
        if (entity.isInWaterOrBubble() || entity.isInLava() || entity.isFallFlying()
                || entity.isPassenger() || entity.isVehicle()
                || entity.hasEffect(MobEffects.LEVITATION) || entity.hasEffect(MobEffects.SLOW_FALLING)) {
            return refuse(entity, Result.UNSUPPORTED_MOVEMENT);
        }
        if (sample == null) return refuse(entity, Result.INVALID_SAMPLE);
        try {
            ApiIds.require(expectedFrameId);
        } catch (RuntimeException invalidId) {
            return refuse(entity, Result.INVALID_SAMPLE);
        }
        if (!sample.frameId().equals(expectedFrameId)) return refuse(entity, Result.FRAME_MISMATCH);
        var acceleration = sample.accelerationMetersPerSecondSquared();
        double magnitude = -acceleration.y();
        if (acceleration.x() != 0 || acceleration.z() != 0 || !Double.isFinite(magnitude)
                || magnitude < 0 || magnitude > VerticalGravityProfile.MAX_ACCELERATION) {
            return refuse(entity, Result.INVALID_SAMPLE);
        }
        double target = magnitude / (GravitySample.TICKS_PER_SECOND * GravitySample.TICKS_PER_SECOND);
        AttributeInstance attribute = entity.getAttribute(Attributes.GRAVITY);
        if (attribute == null) return refuse(entity, Result.MISSING_ATTRIBUTE);
        Ownership previous = owners.get(entity);
        AttributeModifier current = attribute.getModifier(MODIFIER_ID);
        if (current != null && (previous == null || previous.attribute() != attribute
                || previous.modifier() != current)) {
            return refuse(entity, Result.MODIFIER_CONFLICT);
        }
        if (previous == null && owners.size() >= MAX_OWNERS) {
            return Result.CAPACITY_REACHED;
        }

        // Evaluate through the mapped implementation, not a guessed modifier ordering.
        AttributeInstance foreign = new AttributeInstance(attribute.getAttribute(), ignored -> {});
        if (!Double.isFinite(attribute.getBaseValue())) return refuse(entity, Result.VALUE_MISMATCH);
        foreign.setBaseValue(attribute.getBaseValue());
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (modifier == current && previous != null && previous.modifier() == current) continue;
            if (!Double.isFinite(modifier.amount())) return refuse(entity, Result.VALUE_MISMATCH);
            foreign.addTransientModifier(modifier);
        }
        double baseline = foreign.getValue();
        if (!Double.isFinite(baseline) || baseline < 0) return refuse(entity, Result.VALUE_MISMATCH);
        if (baseline == 0 && target > 0) return refuse(entity, Result.ZERO_BASELINE);
        double factor = baseline == 0 ? 0 : target / baseline;
        if (!Double.isFinite(factor)) return refuse(entity, Result.VALUE_MISMATCH);
        releaseOwned(entity);
        AttributeModifier replacement = new AttributeModifier(MODIFIER_ID, factor - 1,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        attribute.addTransientModifier(replacement);
        owners.put(entity, new Ownership(attribute, replacement));
        if (!Double.isFinite(attribute.getValue()) || Math.abs(attribute.getValue() - target) > VALUE_TOLERANCE) {
            return refuse(entity, Result.VALUE_MISMATCH);
        }
        return Result.APPLIED;
    }

    /** Removes only the exact transient modifier instance installed by this adapter. */
    public Result release(LivingEntity entity) {
        if (!server.isSameThread()) return Result.WRONG_THREAD;
        if (entity == null) return Result.INVALID_ENTITY;
        if (!(entity.level() instanceof ServerLevel level) || level.getServer() != server) {
            return Result.WRONG_SERVER;
        }
        return releaseOwned(entity) ? Result.RELEASED : Result.NOT_OWNED;
    }

    /** Lifecycle-only cleanup after the entity leaves its original context. */
    void releaseForLifecycle(LivingEntity entity) {
        if (!server.isSameThread()) throw new IllegalStateException("Gravity cleanup requires server thread");
        releaseOwned(entity);
    }

    /** Ownership follows exact entity, attribute and modifier instances. */
    boolean ownsExact(LivingEntity entity) {
        if (!server.isSameThread()) throw new IllegalStateException("Gravity ownership requires server thread");
        Ownership owned = owners.get(entity);
        return owned != null && entity.getAttribute(Attributes.GRAVITY) == owned.attribute()
                && owned.attribute().getModifier(MODIFIER_ID) == owned.modifier();
    }

    private Result refuse(LivingEntity entity, Result reason) {
        releaseOwned(entity);
        return reason;
    }

    private boolean releaseOwned(LivingEntity entity) {
        Ownership owned = owners.remove(entity);
        if (owned == null) return false;
        if (owned.attribute().getModifier(MODIFIER_ID) == owned.modifier()) {
            owned.attribute().removeModifier(MODIFIER_ID);
            return true;
        }
        return false;
    }
}
