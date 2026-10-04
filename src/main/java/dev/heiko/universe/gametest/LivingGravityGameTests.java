package dev.heiko.universe.gametest;

import dev.heiko.universe.api.gravity.GravitySample;
import dev.heiko.universe.integration.gravity.LivingGravityAdapter;
import dev.heiko.universe.ships.Vec3;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import com.mojang.authlib.GameProfile;

import static dev.heiko.universe.integration.gravity.LivingGravityAdapter.Result.*;

/** Required real-mob tests; no fake player is used as evidence of client prediction. */
@GameTestHolder("universe")
@PrefixGameTestTemplate(false)
public final class LivingGravityGameTests {
    private static final String FRAME = "universe:gametest_world";
    private static final ResourceLocation FOREIGN = ResourceLocation.fromNamespaceAndPath("gravity_test", "foreign");

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void gravity32MatchesAirControl(GameTestHelper helper) { trajectory(helper, 32); }

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void gravity16FallsHalfAsFarAsAirControl(GameTestHelper helper) { trajectory(helper, 16); }

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void gravityZeroRemainsAboveFallingAirControl(GameTestHelper helper) { trajectory(helper, 0); }

    private static void trajectory(GameTestHelper helper, double acceleration) {
        prepareAir(helper);
        Pig subject = pig(helper, 1, 1);
        Pig control = pig(helper, 3, 3);
        LivingGravityAdapter adapter = new LivingGravityAdapter(helper.getLevel().getServer());
        double subjectStart = subject.getY();
        double controlStart = control.getY();
        double subjectX = subject.getX();
        double subjectZ = subject.getZ();
        double controlX = control.getX();
        double controlZ = control.getZ();
        try {
            helper.assertTrue(subject.isEffectiveAi() && control.isEffectiveAi()
                    && subject.isControlledByLocalInstance() && control.isControlledByLocalInstance(),
                    "Both real mobs must run ordinary server travel physics");
            helper.assertTrue(adapter.apply(subject, sample(acceleration), FRAME) == APPLIED, "Apply failed");
            helper.assertTrue(adapter.apply(subject, sample(acceleration), FRAME) == APPLIED, "Repeat apply failed");
            close(helper, subject.getAttributeValue(Attributes.GRAVITY), acceleration / 400, "Target attribute");
            helper.assertTrue(subject.getAttribute(Attributes.GRAVITY).getModifiers().size() == 1,
                    "Repeated apply stacked modifiers");
            close(helper, control.getAttributeValue(Attributes.GRAVITY), 0.08, "Untouched control gravity");
            helper.runAfterDelay(6, () -> {
                try {
                    helper.assertTrue(!subject.onGround() && !control.onGround(), "Air test contacted floor");
                    helper.assertTrue(!subject.isInWaterOrBubble() && !control.isInWaterOrBubble(), "Air test entered fluid");
                    close(helper, subject.getX(), subjectX, "Subject wandered along X");
                    close(helper, subject.getZ(), subjectZ, "Subject wandered along Z");
                    close(helper, control.getX(), controlX, "Control wandered along X");
                    close(helper, control.getZ(), controlZ, "Control wandered along Z");
                    double fallenControl = controlStart - control.getY();
                    double fallenSubject = subjectStart - subject.getY();
                    helper.assertTrue(fallenControl > 0.5 && fallenControl < 3,
                            "Control must measurably fall without reaching support: " + fallenControl);
                    if (acceleration == 0) {
                        helper.assertTrue(Math.abs(fallenSubject) < 0.02, "Zero-g subject fell: " + fallenSubject);
                    } else {
                        double ratio = fallenSubject / fallenControl;
                        helper.assertTrue(Math.abs(ratio - acceleration / 32) < 0.06,
                                "Unexpected real trajectory ratio: " + ratio);
                    }
                    helper.assertTrue(adapter.release(subject) == RELEASED, "Release failed");
                    close(helper, subject.getAttributeValue(Attributes.GRAVITY), 0.08, "Released gravity");
                    helper.succeed();
                } finally {
                    adapter.release(subject);
                    subject.discard();
                    control.discard();
                }
            });
        } catch (RuntimeException failure) {
            adapter.release(subject);
            subject.discard();
            control.discard();
            throw failure;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void reapplyAndReleasePreserveUpdatedForeignState(GameTestHelper helper) {
        prepareAir(helper);
        Pig pig = pig(helper, 1, 1);
        LivingGravityAdapter adapter = new LivingGravityAdapter(helper.getLevel().getServer());
        var attribute = pig.getAttribute(Attributes.GRAVITY);
        var baseScale = new AttributeModifier(ResourceLocation.fromNamespaceAndPath("gravity_test", "base_scale"),
                0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        var totalScale = new AttributeModifier(ResourceLocation.fromNamespaceAndPath("gravity_test", "total_scale"),
                0.25, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        try {
            attribute.addPermanentModifier(new AttributeModifier(FOREIGN, 0.02, AttributeModifier.Operation.ADD_VALUE));
            attribute.addPermanentModifier(baseScale);
            attribute.addTransientModifier(totalScale);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == APPLIED, "Initial apply failed");
            close(helper, attribute.getValue(), 0.04, "Initial effective target");
            attribute.setBaseValue(0.10);
            attribute.removeModifier(FOREIGN);
            var updated = new AttributeModifier(FOREIGN, 0.06, AttributeModifier.Operation.ADD_VALUE);
            attribute.addPermanentModifier(updated);
            helper.assertTrue(adapter.apply(pig, sample(32), FRAME) == APPLIED, "Foreign update reapply failed");
            close(helper, attribute.getValue(), 0.08, "Recomputed target");
            // The own modifier is transient, even alongside foreign permanent modifiers.
            var stored = attribute.save().getList("modifiers", 10);
            for (int i = 0; i < stored.size(); i++) {
                helper.assertTrue(!stored.getCompound(i).getString("id").equals(LivingGravityAdapter.MODIFIER_ID.toString()),
                        "Universe modifier became permanent");
            }
            helper.assertTrue(adapter.release(pig) == RELEASED, "Release failed");
            close(helper, attribute.getValue(), 0.30, "Release must expose current foreign state");
            close(helper, attribute.getBaseValue(), 0.10, "Foreign base update was lost");
            helper.assertTrue(attribute.getModifier(FOREIGN) == updated, "Foreign modifier replaced");
            helper.assertTrue(attribute.getModifier(baseScale.id()) == baseScale, "Base scale was replaced");
            helper.assertTrue(attribute.getModifier(totalScale.id()) == totalScale, "Total scale was replaced");
            helper.assertTrue(!pig.isNoGravity(), "NoGravity changed");
            helper.succeed();
        } finally { adapter.release(pig); pig.discard(); }
    }

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void occupiedModifierIdAndLostOwnershipAreNotOverwritten(GameTestHelper helper) {
        prepareAir(helper);
        Pig pig = pig(helper, 1, 1);
        LivingGravityAdapter adapter = new LivingGravityAdapter(helper.getLevel().getServer());
        var attribute = pig.getAttribute(Attributes.GRAVITY);
        var foreignCollision = new AttributeModifier(LivingGravityAdapter.MODIFIER_ID, 0.01,
                AttributeModifier.Operation.ADD_VALUE);
        try {
            attribute.addPermanentModifier(foreignCollision);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == MODIFIER_CONFLICT, "Collision was accepted");
            helper.assertTrue(adapter.release(pig) == NOT_OWNED, "Collision was claimed");
            helper.assertTrue(attribute.getModifier(LivingGravityAdapter.MODIFIER_ID) == foreignCollision,
                    "Preexisting collision changed");
            attribute.removeModifier(LivingGravityAdapter.MODIFIER_ID);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == APPLIED, "Apply failed after collision cleared");
            attribute.removeModifier(LivingGravityAdapter.MODIFIER_ID);
            attribute.addPermanentModifier(foreignCollision);
            helper.assertTrue(adapter.apply(pig, sample(0), FRAME) == MODIFIER_CONFLICT, "Lost ownership accepted");
            helper.assertTrue(attribute.getModifier(LivingGravityAdapter.MODIFIER_ID) == foreignCollision,
                    "Foreign takeover changed");
            close(helper, attribute.getBaseValue(), 0.08, "Base changed");
            close(helper, attribute.getValue(), 0.09, "Foreign collision value changed");
            helper.succeed();
        } finally { adapter.release(pig); pig.discard(); }
    }

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void invalidFramesNoGravityAndClampFailuresLeaveNoResidue(GameTestHelper helper) {
        prepareAir(helper);
        Pig pig = pig(helper, 1, 1);
        LivingGravityAdapter adapter = new LivingGravityAdapter(helper.getLevel().getServer());
        var attribute = pig.getAttribute(Attributes.GRAVITY);
        try {
            pig.setNoGravity(true);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == NO_GRAVITY, "NoGravity was accepted");
            helper.assertTrue(pig.isNoGravity() && attribute.getModifiers().isEmpty(), "NoGravity state changed");
            pig.setNoGravity(false);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == APPLIED, "Setup apply failed");
            pig.setNoGravity(true);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == NO_GRAVITY, "Active NoGravity was accepted");
            helper.assertTrue(pig.isNoGravity() && attribute.getModifiers().isEmpty(),
                    "NoGravity refusal must release only ours");
            pig.setNoGravity(false);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == APPLIED, "Second setup apply failed");
            helper.assertTrue(adapter.apply(pig, sample(16), "universe:wrong_frame") == FRAME_MISMATCH,
                    "Mismatched frame accepted");
            helper.assertTrue(attribute.getModifiers().isEmpty(), "Frame refusal left own modifier");
            for (Vec3 invalid : new Vec3[]{new Vec3(1, -16, 0), new Vec3(0, 16, 0)}) {
                helper.assertTrue(adapter.apply(pig, new GravitySample("universe:test", 0, FRAME, invalid), FRAME)
                        == INVALID_SAMPLE, "Non-downward field accepted");
            }
            attribute.setBaseValue(0);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == ZERO_BASELINE, "Zero baseline raised");
            helper.assertTrue(attribute.getModifiers().isEmpty(), "Zero baseline refusal left modifier");
            attribute.setBaseValue(2);
            helper.assertTrue(adapter.apply(pig, sample(16), FRAME) == VALUE_MISMATCH, "Clamp mismatch accepted");
            helper.assertTrue(attribute.getModifiers().isEmpty(), "Readback refusal left modifier");
            close(helper, attribute.getBaseValue(), 2, "Refusal changed base");
            helper.assertTrue(CompletableFuture.supplyAsync(() -> adapter.apply(pig, sample(16), FRAME)).join()
                    == WRONG_THREAD, "Off-thread apply accepted");
            helper.assertTrue(attribute.getModifiers().isEmpty(), "Off-thread call mutated state");
            var unsupported = helper.spawn(EntityType.CHICKEN, 3, 8, 3);
            try {
                helper.assertTrue(adapter.apply(unsupported, sample(16), FRAME) == INVALID_ENTITY,
                        "Unsupported movement class accepted");
                helper.assertTrue(unsupported.getAttribute(Attributes.GRAVITY).getModifiers().isEmpty(),
                        "Unsupported entity mutated");
            } finally { unsupported.discard(); }
            helper.succeed();
        } finally { adapter.release(pig); pig.discard(); }
    }

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void serverPlayerIsRefusedWithoutClaimingClientSupport(GameTestHelper helper) {
        // Detached ServerPlayer: this tests refusal only, never live client prediction/sync.
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "gravity-refusal"), ClientInformation.createDefault());
        LivingGravityAdapter adapter = new LivingGravityAdapter(helper.getLevel().getServer());
        double before = player.getAttributeValue(Attributes.GRAVITY);
        try {
            helper.assertTrue(adapter.apply(player, sample(16), FRAME) == PLAYER_UNSUPPORTED,
                    "ServerPlayer must be refused before live-client evidence");
            close(helper, player.getAttributeValue(Attributes.GRAVITY), before, "Player gravity changed");
            helper.assertTrue(!player.getAttribute(Attributes.GRAVITY).hasModifier(LivingGravityAdapter.MODIFIER_ID),
                    "Player refusal left modifier");
            helper.assertTrue(adapter.release(player) == NOT_OWNED, "Player was claimed");
            helper.succeed();
        } finally { adapter.release(player); }
    }

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void ownershipUsesEntityInstanceRatherThanReusedUuid(GameTestHelper helper) {
        prepareAir(helper);
        Pig original = pig(helper, 1, 1);
        Pig replacement = EntityType.PIG.create(helper.getLevel());
        helper.assertTrue(replacement != null, "Unable to create replacement instance");
        replacement.setUUID(original.getUUID()); // Detached instance avoids corrupting server entity indexes.
        LivingGravityAdapter adapter = new LivingGravityAdapter(helper.getLevel().getServer());
        try {
            helper.assertTrue(adapter.apply(original, sample(16), FRAME) == APPLIED, "Original apply failed");
            helper.assertTrue(adapter.release(replacement) == NOT_OWNED, "Reused UUID released original");
            close(helper, original.getAttributeValue(Attributes.GRAVITY), 0.04, "Original ownership lost");
            helper.assertTrue(adapter.apply(replacement, sample(0), FRAME) == APPLIED, "Distinct instance apply failed");
            helper.assertTrue(adapter.release(original) == RELEASED, "Original release failed");
            close(helper, replacement.getAttributeValue(Attributes.GRAVITY), 0, "Original release changed replacement");
            helper.succeed();
        } finally { adapter.release(original); adapter.release(replacement); original.discard(); replacement.discard(); }
    }

    @GameTest(template = "empty", timeoutTicks = 40, required = true)
    public static void activeOwnershipRejectsOffThreadCallsAndOtherAdapters(GameTestHelper helper) {
        prepareAir(helper);
        Pig pig = pig(helper, 1, 1);
        LivingGravityAdapter owner = new LivingGravityAdapter(helper.getLevel().getServer());
        LivingGravityAdapter contender = new LivingGravityAdapter(helper.getLevel().getServer());
        var attribute = pig.getAttribute(Attributes.GRAVITY);
        try {
            helper.assertTrue(owner.apply(pig, sample(16), FRAME) == APPLIED, "Owner setup failed");
            var installed = attribute.getModifier(LivingGravityAdapter.MODIFIER_ID);
            // Each task completes before the next assertion. No entity ticks, attribute reads,
            // or foreign mutations occur on these worker threads: both calls must exit early.
            helper.assertTrue(CompletableFuture.supplyAsync(() -> owner.apply(pig, sample(0), FRAME)).join()
                    == WRONG_THREAD, "Off-thread active apply accepted");
            helper.assertTrue(CompletableFuture.supplyAsync(() -> owner.release(pig)).join()
                    == WRONG_THREAD, "Off-thread active release accepted");
            helper.assertTrue(attribute.getModifier(LivingGravityAdapter.MODIFIER_ID) == installed,
                    "Off-thread calls changed ownership or the installed modifier");
            close(helper, attribute.getValue(), 0.04, "Off-thread calls changed target");
            helper.assertTrue(contender.apply(pig, sample(0), FRAME) == MODIFIER_CONFLICT,
                    "Second adapter must not overwrite the first owner's ID");
            helper.assertTrue(contender.release(pig) == NOT_OWNED, "Second adapter claimed the first owner");
            helper.assertTrue(attribute.getModifier(LivingGravityAdapter.MODIFIER_ID) == installed,
                    "Second adapter changed installed modifier");
            close(helper, attribute.getValue(), 0.04, "Second adapter changed target");
            helper.assertTrue(owner.release(pig) == RELEASED, "Original ownership was lost");
            helper.assertTrue(contender.apply(pig, sample(0), FRAME) == APPLIED,
                    "Second adapter must acquire only after explicit release");
            var nextInstalled = attribute.getModifier(LivingGravityAdapter.MODIFIER_ID);
            helper.assertTrue(owner.release(pig) == NOT_OWNED, "Former owner released the successor");
            helper.assertTrue(attribute.getModifier(LivingGravityAdapter.MODIFIER_ID) == nextInstalled,
                    "Former owner removed successor's modifier");
            close(helper, attribute.getValue(), 0, "Successor target changed");
            helper.assertTrue(contender.release(pig) == RELEASED, "Successor release failed");
            close(helper, attribute.getBaseValue(), 0.08, "Ownership operations changed base");
            close(helper, attribute.getValue(), 0.08, "Release did not expose original gravity");
            helper.assertTrue(!pig.isNoGravity() && attribute.getModifiers().isEmpty(), "Ownership left residue");
            helper.succeed();
        } finally { owner.release(pig); contender.release(pig); pig.discard(); }
    }

    private static GravitySample sample(double acceleration) {
        return new GravitySample("universe:gametest_gravity", 0, FRAME, new Vec3(0, -acceleration, 0));
    }

    private static void prepareAir(GameTestHelper helper) {
        // Explicit dry floor and headroom. Six-tick samples finish well above support;
        // subjects are separated so neither AI nor mutual collision changes the trajectory.
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
            helper.setBlock(x, 0, z, Blocks.STONE);
            for (int y = 1; y <= 12; y++) helper.setBlock(x, y, z, Blocks.AIR);
        }
    }

    private static Pig pig(GameTestHelper helper, int x, int z) {
        Pig pig = helper.spawn(EntityType.PIG, x, 8, z);
        // NoAI also disables isEffectiveAi -> isControlledByLocalInstance -> travel.
        // Remove goals before the first tick instead, keeping normal server integration.
        pig.setNoAi(false);
        pig.goalSelector.removeAllGoals(goal -> true);
        pig.targetSelector.removeAllGoals(goal -> true);
        pig.getNavigation().stop();
        pig.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        pig.setOnGround(false);
        return pig;
    }

    private static void close(GameTestHelper helper, double actual, double expected, String message) {
        helper.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) < 1.0e-9,
                message + ": expected " + expected + ", got " + actual);
    }
}
