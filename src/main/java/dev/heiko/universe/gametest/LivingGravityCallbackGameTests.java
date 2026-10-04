package dev.heiko.universe.gametest;

import dev.heiko.universe.api.gravity.GravitySample;
import dev.heiko.universe.integration.gravity.LivingGravityAdapter;
import dev.heiko.universe.integration.gravity.LivingGravityDomain;
import dev.heiko.universe.ships.Vec3;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/** Actual vanilla calls produce callbacks, including a second call that proves temporary listener removal. */
@GameTestHolder("universe")
@PrefixGameTestTemplate(false)
public final class LivingGravityCallbackGameTests {
    private static final String FRAME="gravity_callback_test:world_y";
    private static final ResourceLocation FOREIGN=ResourceLocation.fromNamespaceAndPath("gravity_callback_test","foreign");

    @GameTest(template="empty",timeoutTicks=40,required=true)
    public static void cancelledActualDeathRestoresHealthAndKeepsExactOwnership(GameTestHelper helper) {
        Pig pig=fixture(helper);
        var domain=domain(helper);
        try {
            var token=claim(helper,domain,pig);
            var attribute=pig.getAttribute(Attributes.GRAVITY);
            var own=attribute.getModifier(LivingGravityAdapter.MODIFIER_ID);
            var foreign=attribute.getModifier(FOREIGN);
            var origin=pig.level();var uuid=pig.getUUID();
            int[] receipts={0};boolean[] sawLethal={false};
            Consumer<LivingDeathEvent> callback=event->{
                if(event.getEntity()!=pig)return;
                receipts[0]++;sawLethal[0]=pig.getHealth()<=0;
                event.setCanceled(true);
                pig.setHealth(pig.getMaxHealth());
            };
            // Listener lifetime ends on synchronous hurt return/failure, before delayed observations.
            try(var listener=ListenerLease.install(LivingDeathEvent.class,callback)) {
                pig.hurt(pig.damageSources().generic(),1000);
                helper.assertTrue(receipts[0]==1 && sawLethal[0],"Real lethal hurt did not deliver exactly one death callback");
            }
            helper.assertTrue(pig.isAlive() && !pig.isRemoved() && pig.getHealth()==pig.getMaxHealth(),"Cancelled death did not restore a living source");
            helper.assertTrue(pig.level()==origin && pig.getUUID().equals(uuid),"Cancelled death changed source/context");
            helper.assertTrue(token.isActive() && domain.activeCount()==1 && attribute.getModifier(LivingGravityAdapter.MODIFIER_ID)==own,
                    "Cancelled death prematurely released exact ownership");
            helper.runAfterDelay(3,()->{
                try {
                    helper.assertTrue(receipts[0]==1 && pig.isAlive() && !pig.isRemoved(),"Source died or callback repeated");
                    helper.assertTrue(token.isActive() && domain.activeCount()==1,"Post-callback server sweep released restored living source");
                    helper.assertTrue(pig.level()==origin && pig.getUUID().equals(uuid),"Delayed context changed");
                    helper.assertTrue(attribute.getModifier(LivingGravityAdapter.MODIFIER_ID)==own,"Own modifier replaced after cancelled death");
                    assertForeign(helper,pig,foreign);near(helper,attribute.getValue(),0.04,"Active target");
                    token.close();
                    helper.assertTrue(attribute.getModifier(LivingGravityAdapter.MODIFIER_ID)==null,"Explicit final release failed");
                    assertForeign(helper,pig,foreign);near(helper,attribute.getValue(),0.10,"Current foreign state after release");
                    pig.invulnerableTime=0;
                    pig.hurt(pig.damageSources().generic(),1000);
                    helper.assertTrue(receipts[0]==1 && !pig.isAlive(),"Removed death consumer still cancelled a second real lethal hurt");
                    helper.succeed();
                } finally {domain.close();pig.discard();}
            });
        } catch(RuntimeException failure) {domain.close();pig.discard();throw failure;}
    }

    @GameTest(template="empty",timeoutTicks=60,required=true)
    public static void cancelledActualTravelAfterCoreRelayRequiresFreshOptIn(GameTestHelper helper) {
        var destination=helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(destination!=null && destination!=helper.getLevel(),"Real target ServerLevel required; no synthetic-event fallback");
        Pig pig=fixture(helper);var domain=domain(helper);
        net.minecraft.world.entity.Entity[] movedForCleanup={null};
        try {
            var token=claim(helper,domain,pig);var attribute=pig.getAttribute(Attributes.GRAVITY);
            var foreign=attribute.getModifier(FOREIGN);var origin=pig.level();var uuid=pig.getUUID();
            helper.assertTrue(destination.getEntity(uuid)==null,"Destination already contains source UUID");
            int[] receipts={0};boolean[] sawCoreRelease={false};
            Consumer<EntityTravelToDimensionEvent> callback=event->{
                if(event.getEntity()!=pig)return;
                receipts[0]++;
                // NORMAL core relay must precede this LOWEST cancellation, independent of listener order within priority.
                sawCoreRelease[0]=!token.isActive() && domain.activeCount()==0
                        && attribute.getModifier(LivingGravityAdapter.MODIFIER_ID)==null
                        && pig.level()==origin && event.getDimension().equals(destination.dimension());
                event.setCanceled(true);
            };
            try(var listener=ListenerLease.install(EntityTravelToDimensionEvent.class,callback)) {
                var moved=pig.changeDimension(new DimensionTransition(destination,
                        new net.minecraft.world.phys.Vec3(0,100,0),net.minecraft.world.phys.Vec3.ZERO,
                        0,0,DimensionTransition.DO_NOTHING));
                helper.assertTrue(moved==null && receipts[0]==1 && sawCoreRelease[0],"LOWEST cancel did not observe NORMAL core release before actual transition");
            }
            helper.assertTrue(pig.level()==origin && pig.getUUID().equals(uuid) && pig.isAlive() && !pig.isRemoved(),
                    "Cancelled travel changed/destroyed source");
            helper.assertTrue(destination.getEntity(uuid)==null,"Cancelled travel created destination instance");
            helper.assertTrue(!token.isActive() && domain.activeCount()==0,"Cancelled attempt retained old ownership");
            assertForeign(helper,pig,foreign);near(helper,attribute.getValue(),0.10,"Foreign state after cancelled attempt");
            helper.runAfterDelay(3,()->{
                try {
                    helper.assertTrue(receipts[0]==1 && pig.level()==origin && !pig.isRemoved() && destination.getEntity(uuid)==null,
                            "Cancelled source/context changed after ticks");
                    helper.assertTrue(!token.isActive() && domain.activeCount()==0
                            && attribute.getModifier(LivingGravityAdapter.MODIFIER_ID)==null,"Old frame auto-reactivated");
                    var successor=claim(helper,domain,pig);token.close();
                    helper.assertTrue(successor.isActive() && domain.activeCount()==1,"Old token removed explicit successor");
                    helper.assertTrue(token.update(sample())==LivingGravityAdapter.Result.NOT_OWNED,"Old token updated successor");
                    assertForeign(helper,pig,foreign);near(helper,attribute.getValue(),0.04,"Fresh opt-in target");
                    successor.close();assertForeign(helper,pig,foreign);near(helper,attribute.getValue(),0.10,"Successor release");
                    movedForCleanup[0]=pig.changeDimension(new DimensionTransition(destination,
                            new net.minecraft.world.phys.Vec3(0,100,0),net.minecraft.world.phys.Vec3.ZERO,
                            0,0,DimensionTransition.DO_NOTHING));
                    helper.assertTrue(receipts[0]==1 && movedForCleanup[0]!=null && movedForCleanup[0].level()==destination
                            && movedForCleanup[0].getUUID().equals(uuid) && pig.isRemoved(),
                            "Removed travel consumer still cancelled a second real dimension request");
                    helper.succeed();
                } finally {domain.close();pig.discard();if(movedForCleanup[0]!=null)movedForCleanup[0].discard();}
            });
        } catch(RuntimeException failure) {domain.close();pig.discard();if(movedForCleanup[0]!=null)movedForCleanup[0].discard();throw failure;}
    }

    /** At most one consumer per test; identity-filtered, unregistered even if invocation/assertion fails. */
    private static final class ListenerLease implements AutoCloseable {
        private final Consumer<?> callback;
        private boolean closed;
        private ListenerLease(Consumer<?> callback) {this.callback=callback;}
        static <T extends Event> ListenerLease install(Class<T> type,Consumer<T> callback) {
            var lease=new ListenerLease(callback);
            try {
                NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,type,callback);
                return lease;
            } catch(RuntimeException failure) {lease.close();throw failure;}
        }
        @Override public void close() {
            if(!closed) {closed=true;NeoForge.EVENT_BUS.unregister(callback);}
        }
    }
    private static LivingGravityDomain domain(GameTestHelper helper) {
        return LivingGravityDomain.open(helper.getLevel().getServer(),"gravity_callback_test:host_"+UUID.randomUUID(),1);
    }
    private static LivingGravityDomain.Registration claim(GameTestHelper helper,LivingGravityDomain domain,Pig pig) {
        var attempt=domain.register(pig,sample(),FRAME);
        helper.assertTrue(attempt.applied() && attempt.registration()!=null,"Opt-in registration failed: "+attempt.result());
        return attempt.registration();
    }
    private static GravitySample sample() {
        return new GravitySample("gravity_callback_test:field",0,FRAME,new Vec3(0,-16,0));
    }
    private static Pig fixture(GameTestHelper helper) {
        for(int x=0;x<=4;x++)for(int z=0;z<=4;z++) {
            helper.setBlock(x,0,z,Blocks.STONE);
            for(int y=1;y<=12;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        Pig pig=helper.spawn(EntityType.PIG,2,8,2);
        pig.setNoAi(true); // Lifecycle only: do not claim travel/client trajectory evidence.
        pig.getAttribute(Attributes.GRAVITY).addPermanentModifier(new AttributeModifier(FOREIGN,0.02,AttributeModifier.Operation.ADD_VALUE));
        return pig;
    }
    private static void assertForeign(GameTestHelper helper,Pig pig,AttributeModifier foreign) {
        helper.assertTrue(pig.getAttribute(Attributes.GRAVITY).getModifier(FOREIGN)==foreign,"Foreign modifier identity changed");
        near(helper,pig.getAttribute(Attributes.GRAVITY).getBaseValue(),0.08,"Base gravity changed");
        helper.assertTrue(!pig.isNoGravity(),"NoGravity changed");
    }
    private static void near(GameTestHelper helper,double actual,double expected,String message) {
        helper.assertTrue(Double.isFinite(actual) && Math.abs(actual-expected)<1e-9,message+": "+actual);
    }
}
