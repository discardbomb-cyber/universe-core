package dev.heiko.universe.gametest;

import dev.heiko.universe.api.gravity.GravitySample;
import dev.heiko.universe.integration.gravity.LivingGravityAdapter;
import dev.heiko.universe.integration.gravity.LivingGravityDomain;
import dev.heiko.universe.ships.Vec3;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/** Required live vanilla-mob lifecycle tests. No synthetic player/client claims. */
@GameTestHolder("universe")
@PrefixGameTestTemplate(false)
public final class LivingGravityLifecycleGameTests {
    private static final String FRAME="universe:lifecycle_test_world";
    private static final ResourceLocation FOREIGN=ResourceLocation.fromNamespaceAndPath("gravity_test","lifecycle_foreign");

    @GameTest(template="empty", timeoutTicks=40, required=true)
    public static void domainCancelAndFormerTokenCannotReleaseSuccessor(GameTestHelper helper) {
        var domain=domain(helper); Pig pig=pig(helper,1);
        try {
            var first=claim(helper,domain,pig);
            first.close(); first.close();
            clear(helper,pig,"Cancelled token");
            var second=claim(helper,domain,pig);
            first.close();
            helper.assertTrue(second.isActive() && domain.activeCount()==1,"Former token released successor");
            helper.assertTrue(first.update(sample(0))==LivingGravityAdapter.Result.NOT_OWNED,"Former token updated successor");
            closeValue(helper,pig.getAttributeValue(Attributes.GRAVITY),0.04,"Successor gravity");
            domain.close(); domain.close();
            helper.assertTrue(domain.isClosed() && !second.isActive() && domain.activeCount()==0,"Domain close left ownership");
            clear(helper,pig,"Domain close");
            helper.succeed();
        } finally {domain.close();pig.discard();}
    }

    @GameTest(template="empty", timeoutTicks=40, required=true)
    public static void liveDeathAndRemovalFreeOnlyRegisteredEntities(GameTestHelper helper) {
        var domain=domain(helper); Pig dying=pig(helper,1); Pig removed=pig(helper,3); Pig control=pig(helper,4);
        try {
            var deathToken=claim(helper,domain,dying); var removalToken=claim(helper,domain,removed);
            // Real damage invokes vanilla death/NeoForge death hooks, not a manually posted event.
            dying.hurt(dying.damageSources().generic(),1000);
            helper.assertTrue(!dying.isAlive(),"Subject did not really die");
            removed.discard();
            helper.runAfterDelay(2,()->{
                try {
                    helper.assertTrue(domain.activeCount()==0 && !deathToken.isActive() && !removalToken.isActive(),
                            "Death/removal lifecycle retained tokens");
                    clear(helper,dying,"Dead subject");clear(helper,removed,"Removed subject");
                    clear(helper,control,"Unregistered control");
                    helper.succeed();
                } finally {domain.close();dying.discard();removed.discard();control.discard();}
            });
        } catch(RuntimeException failure) {domain.close();dying.discard();removed.discard();control.discard();throw failure;}
    }

    @GameTest(template="empty", timeoutTicks=60, required=true)
    public static void actualDimensionTransitionDropsContextAndNeverAutoRegisters(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        var destination=server.getLevel(Level.NETHER);
        helper.assertTrue(destination!=null && destination!=helper.getLevel(),
                "Required actual dimension fixture missing; synthetic event is not a substitute");
        var domain=domain(helper); Pig source=pig(helper,1); Pig target=null;
        try {
            var token=claim(helper,domain,source);
            var transitioned=source.changeDimension(new DimensionTransition(destination,
                    new net.minecraft.world.phys.Vec3(0,100,0),net.minecraft.world.phys.Vec3.ZERO,
                    0,0,DimensionTransition.DO_NOTHING));
            helper.assertTrue(transitioned instanceof Pig && transitioned!=source,"Real mob transition did not create destination");
            target=(Pig)transitioned;
            helper.assertTrue(target.level()==destination && source.isRemoved(),"Wrong dimension/source lifecycle");
            helper.assertTrue(!token.isActive() && domain.activeCount()==0,"Transition retained old ownership");
            clear(helper,source,"Transition source");clear(helper,target,"Transition destination");
            Pig finalTarget=target;
            helper.runAfterDelay(2,()->{
                try {
                    clear(helper,finalTarget,"Destination after ticks");
                    helper.assertTrue(domain.activeCount()==0,"Destination silently auto-registered");
                    helper.succeed();
                } finally {domain.close();source.discard();finalTarget.discard();}
            });
        } catch(RuntimeException failure) {domain.close();source.discard();if(target!=null)target.discard();throw failure;}
    }

    @GameTest(template="empty", timeoutTicks=40, required=true)
    public static void newNbtInstanceHasNoTransientModifierOrAutomaticRegistration(GameTestHelper helper) {
        var domain=domain(helper); Pig original=pig(helper,1); Pig reloaded=null;
        try {
            var foreign=new AttributeModifier(FOREIGN,0.02,AttributeModifier.Operation.ADD_VALUE);
            original.getAttribute(Attributes.GRAVITY).addPermanentModifier(foreign);
            var token=claim(helper,domain,original);
            CompoundTag saved=original.saveWithoutId(new CompoundTag());
            original.discard();
            helper.assertTrue(!token.isActive(),"Discard did not release original token");
            reloaded=EntityType.PIG.create(helper.getLevel());
            helper.assertTrue(reloaded!=null,"Cannot create NBT reload subject");
            reloaded.load(saved);
            helper.assertTrue(reloaded.getUUID().equals(original.getUUID()),"Reload changed persisted UUID");
            helper.assertTrue(helper.getLevel().addFreshEntity(reloaded),"Reload not admitted to real server level");
            clear(helper,reloaded,"Reloaded instance");
            helper.assertTrue(reloaded.getAttribute(Attributes.GRAVITY).hasModifier(FOREIGN),"Foreign permanent modifier lost");
            closeValue(helper,reloaded.getAttributeValue(Attributes.GRAVITY),0.10,"Reload foreign state");
            Pig finalReloaded=reloaded;
            helper.runAfterDelay(2,()->{
                try {
                    helper.assertTrue(domain.activeCount()==0,"Reload auto-registered by UUID");
                    clear(helper,finalReloaded,"Reload after ticks");helper.succeed();
                } finally {domain.close();original.discard();finalReloaded.discard();}
            });
        } catch(RuntimeException failure) {domain.close();original.discard();if(reloaded!=null)reloaded.discard();throw failure;}
    }

    @GameTest(template="empty", timeoutTicks=40, required=true)
    public static void foreignTakeoverDropsTokenWithoutDeletingForeignState(GameTestHelper helper) {
        var domain=domain(helper); Pig subject=pig(helper,1);
        try {
            var token=claim(helper,domain,subject);
            var attribute=subject.getAttribute(Attributes.GRAVITY);
            attribute.removeModifier(LivingGravityAdapter.MODIFIER_ID);
            var takeover=new AttributeModifier(LivingGravityAdapter.MODIFIER_ID,0.03,AttributeModifier.Operation.ADD_VALUE);
            attribute.addPermanentModifier(takeover);attribute.setBaseValue(0.10);subject.setNoGravity(true);
            helper.runAfterDelay(2,()->{
                try {
                    helper.assertTrue(!token.isActive() && domain.activeCount()==0,"Foreign takeover retained ownership");
                    token.close();domain.close();
                    helper.assertTrue(attribute.getModifier(LivingGravityAdapter.MODIFIER_ID)==takeover,"Cleanup deleted foreign takeover");
                    closeValue(helper,attribute.getValue(),0.13,"Current foreign state");
                    closeValue(helper,attribute.getBaseValue(),0.10,"Base state");
                    helper.assertTrue(subject.isNoGravity(),"Cleanup overwrote NoGravity");helper.succeed();
                } finally {domain.close();subject.discard();}
            });
        } catch(RuntimeException failure) {domain.close();subject.discard();throw failure;}
    }

    @GameTest(template="empty", timeoutTicks=40, required=true)
    public static void realMobHostCapAndThreadGatesRejectBeforeMutation(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        var domain=LivingGravityDomain.open(server,"gravity_test:cap_"+UUID.randomUUID(),2);
        var contender=domain(helper);
        Pig first=pig(helper,1),second=pig(helper,2),overflow=pig(helper,3);
        try {
            var token=claim(helper,domain,first);claim(helper,domain,second);
            helper.assertTrue(domain.register(overflow,sample(16),FRAME).result()==LivingGravityAdapter.Result.CAPACITY_REACHED,
                    "Configured host cap exceeded");clear(helper,overflow,"Capacity refusal");
            helper.assertTrue(contender.register(first,sample(0),FRAME).result()==LivingGravityAdapter.Result.MODIFIER_CONFLICT,
                    "Second host stole ownership");
            helper.assertTrue(CompletableFuture.supplyAsync(()->{
                try {token.close();return false;}catch(IllegalStateException expected){return true;}
            }).join(),"Off-thread token close accepted");
            helper.assertTrue(CompletableFuture.supplyAsync(()->{
                try {domain.close();return false;}catch(IllegalStateException expected){return true;}
            }).join(),"Off-thread domain close accepted");
            helper.assertTrue(domain.activeCount()==2 && token.isActive(),"Rejected thread operation mutated ownership");
            try {LivingGravityDomain.open(server,"gravity_test:invalid_limit",1025);helper.fail("Limit >1024 accepted");}
            catch(IllegalArgumentException expected) { /* validation before registration */ }
            token.close();claim(helper,domain,overflow);
            helper.assertTrue(domain.activeCount()==2,"Freed capacity not reusable");helper.succeed();
        } finally {domain.close();contender.close();first.discard();second.discard();overflow.discard();}
    }

    private static LivingGravityDomain domain(GameTestHelper helper) {
        return LivingGravityDomain.open(helper.getLevel().getServer(),"gravity_test:host_"+UUID.randomUUID());
    }

    @GameTest(template="empty", timeoutTicks=100, required=true)
    public static void sharedServerCapIs1024AndLeavesUnrelatedOwnersIntact(GameTestHelper helper) {
        var server=helper.getLevel().getServer();
        var first=domain(helper);var second=domain(helper);
        var subjects=new ArrayList<Pig>();
        int before=LivingGravityDomain.activeCount(server);
        try {
            // Synchronous main-thread fixture: no world tick occurs while filling/releasing.
            // Respect concurrently scheduled tests' existing registrations instead of assuming zero.
            int available=LivingGravityDomain.MAX_ENTRIES-before;
            helper.assertTrue(available>=0,"Server already exceeded gravity cap");
            for(int i=0;i<available;i++) {
                Pig subject=pig(helper,1);subjects.add(subject);
                claim(helper,(i&1)==0?first:second,subject);
            }
            helper.assertTrue(LivingGravityDomain.activeCount(server)==1024,"Shared server cap not reached");
            Pig overflow=pig(helper,3);subjects.add(overflow);
            helper.assertTrue(second.register(overflow,sample(16),FRAME).result()==LivingGravityAdapter.Result.CAPACITY_REACHED,
                    "Two hosts exceeded shared server cap");clear(helper,overflow,"Shared capacity refusal");
            first.close();second.close();
            helper.assertTrue(LivingGravityDomain.activeCount(server)==before,"Cleanup changed unrelated owners");
            for(Pig subject:subjects)clear(helper,subject,"Released capacity fixture");
            helper.succeed();
        } finally {first.close();second.close();subjects.forEach(Pig::discard);}
    }
    private static LivingGravityDomain.Registration claim(GameTestHelper helper,LivingGravityDomain domain,Pig pig) {
        var attempt=domain.register(pig,sample(16),FRAME);
        helper.assertTrue(attempt.applied() && attempt.registration()!=null,"Domain registration failed: "+attempt.result());
        return attempt.registration();
    }
    private static GravitySample sample(double acceleration) {
        return new GravitySample("gravity_test:lifecycle",0,FRAME,new Vec3(0,-acceleration,0));
    }
    private static Pig pig(GameTestHelper helper,int x) {
        Pig pig=helper.spawn(EntityType.PIG,x,8,2);
        pig.setNoAi(false);pig.goalSelector.removeAllGoals(goal->true);pig.targetSelector.removeAllGoals(goal->true);
        pig.getNavigation().stop();pig.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        return pig;
    }
    private static void clear(GameTestHelper helper,Pig pig,String message) {
        helper.assertTrue(!pig.getAttribute(Attributes.GRAVITY).hasModifier(LivingGravityAdapter.MODIFIER_ID),message+" left modifier");
    }
    private static void closeValue(GameTestHelper helper,double actual,double expected,String message) {
        helper.assertTrue(Double.isFinite(actual) && Math.abs(actual-expected)<1e-9,message+": "+actual);
    }
}
