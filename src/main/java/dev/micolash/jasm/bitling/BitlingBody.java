package dev.micolash.jasm.bitling;

import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The body every Bitling in the world shares: what it is, what it is doing, how it turns and walks, and how it roams
 * freely around a garden. Where the garden is, and anything else it does, is up to the kind of Bitling.
 */
public abstract class BitlingBody extends PathfinderMob {
    /** What the Bitling is doing; the client picks a loop from it. */
    public enum Act {
        STAND, WALK, SPRINT, LOOK, HOP, REST, TIRED, STARTLED, PETTED, RECHARGE, INSPECT, NOD, EAT, SHAKE;

        public static Act of(int ordinal) {
            Act[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : STAND;
        }
    }

    private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(BitlingBody.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_STAGE = SynchedEntityData.defineId(BitlingBody.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ACT = SynchedEntityData.defineId(BitlingBody.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> DATA_HELD = SynchedEntityData.defineId(BitlingBody.class, EntityDataSerializers.ITEM_STACK);

    protected static final double WALK = 0.5;
    protected static final double SPRINT = 1.0;
    /** Degrees it turns in a tick. */
    private static final float TURN_STEP = 10;
    /** Facing further off than this, it stops and turns on the spot before walking on. */
    private static final float TURN_ON_SPOT = 35;
    /** Sprints a hurt Bitling makes, one after another, and how far each one goes. */
    private static final int HURT_RUNS = 3;
    private static final int RUN_LEAST = 4;
    private static final int RUN_MOST = 8;

    /** Ticks left of a timed act (a hop, a rest, a startle). */
    protected int actTicks;
    /** Ticks to stand still before choosing the next thing to do. */
    protected int waitTicks = 20;
    /** Ticks left of the current walk or sprint before it stops where it is. */
    protected int walkTicks;
    /** Sprints left of running away after being hurt. */
    protected int hurtRun;

    protected BitlingBody(EntityType<? extends BitlingBody> type, Level level) {
        super(type, level);
        this.moveControl = new TurningMoveControl(this);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 10).add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_KIND, 0);
        builder.define(DATA_STAGE, 0);
        builder.define(DATA_ACT, 0);
        builder.define(DATA_HELD, ItemStack.EMPTY);
    }

    protected void setKindAndStage(BitlingKind kind, BitlingStage stage) {
        entityData.set(DATA_KIND, kind.ordinal());
        entityData.set(DATA_STAGE, stage.ordinal());
    }

    public BitlingKind kind() {
        return BitlingKind.values()[Math.clamp(entityData.get(DATA_KIND), 0, BitlingKind.values().length - 1)];
    }

    public BitlingStage stage() {
        return BitlingStage.values()[Math.clamp(entityData.get(DATA_STAGE), 0, BitlingStage.values().length - 1)];
    }

    public Act act() {
        return Act.of(entityData.get(DATA_ACT));
    }

    protected void setAct(Act next) {
        if (entityData.get(DATA_ACT) != next.ordinal()) {
            entityData.set(DATA_ACT, next.ordinal());
        }
    }

    /** What it holds in its hands right now, for the client to draw. */
    public ItemStack held() {
        return entityData.get(DATA_HELD);
    }

    protected void setHeld(ItemStack stack) {
        entityData.set(DATA_HELD, stack);
    }

    /** The middle of the patch of ground it roams. */
    protected abstract Vec3 gardenCentre();

    /** How far from {@link #gardenCentre} it roams, in blocks. */
    protected abstract int gardenRadius();

    // --- staying put ---

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false;
    }

    /** The body always faces where the Bitling faces, so it never swivels round on the spot by itself. */
    @Override
    protected void tickHeadTurn(float yBodyRotT) {
        yBodyRot = getYRot();
        yHeadRot = getYRot();
    }

    /** Walks like any mob, but turns a little at a time, and turns on the spot before setting off another way. */
    private static final class TurningMoveControl extends MoveControl {
        TurningMoveControl(BitlingBody mob) {
            super(mob);
        }

        @Override
        public void tick() {
            boolean moving = operation == Operation.MOVE_TO;
            float before = mob.getYRot();
            super.tick();
            if (!moving) {
                return;
            }
            float wanted = Mth.wrapDegrees(mob.getYRot() - before);
            mob.setYRot(before + Mth.clamp(wanted, -TURN_STEP, TURN_STEP));
            if (Math.abs(wanted) > TURN_ON_SPOT) {
                mob.setSpeed(0);
            }
        }
    }

    // --- roaming ---

    /**
     * One tick of roaming its garden: walks, sprints, looks around, hops and lies down, and walks back in when pushed out.
     * Returns false while it stands about waiting to choose what to do next.
     */
    protected boolean roam(ServerLevel level) {
        Vec3 centre = gardenCentre();
        int radius = gardenRadius();
        if (actTicks > 0) {
            getNavigation().stop();
            if (--actTicks == 0) {
                setAct(Act.STAND);
                waitTicks = 20 + random.nextInt(40);
            }
            return true;
        }
        double flat = Math.hypot(getX() - centre.x, getZ() - centre.z);
        if (flat > radius + 1.0) {
            // Pushed out of its garden: walk back in.
            if (tickCount % 20 == 0 || getNavigation().isDone()) {
                getNavigation().moveTo(centre.x, centre.y, centre.z, 1, WALK);
            }
            setAct(Act.WALK);
            return true;
        }
        Act act = act();
        if (act == Act.WALK || act == Act.SPRINT) {
            // A walk lasts a few seconds, then it stops and does something else.
            if (--walkTicks <= 0 || getNavigation().isDone() || getNavigation().isStuck()) {
                boolean ran = hurtRun > 0;
                if (ran && --hurtRun > 0) {
                    keepRunning(level);
                    return true;
                }
                hurtRun = 0;
                getNavigation().stop();
                if (ran) {
                    afterRun();
                } else {
                    afterWalk();
                }
            }
            return true;
        }
        if (--waitTicks > 0) {
            return false;
        }
        int pick = random.nextInt(100);
        if (pick < 35) {
            walkSomewhere(level, WALK, Act.WALK, 2, radius);
        } else if (pick < 50) {
            walkSomewhere(level, SPRINT, Act.SPRINT, 3, Math.min(radius, 8));
        } else if (pick < 70) {
            setAct(Act.LOOK);
            actTicks = 50 + random.nextInt(40);
        } else if (pick < 85) {
            setAct(Act.HOP);
            actTicks = 14;
        } else {
            setAct(Act.REST);
            actTicks = 100 + random.nextInt(200);
        }
        if (act() == Act.STAND) {
            waitTicks = 20 + random.nextInt(40);
        }
        return true;
    }

    /** Having stopped, look around, hop, lie down or just stand for a moment. */
    private void afterWalk() {
        int pick = random.nextInt(100);
        if (pick < 40) {
            setAct(Act.LOOK);
            actTicks = 50 + random.nextInt(40);
        } else if (pick < 70) {
            setAct(Act.HOP);
            actTicks = 14;
        } else if (pick < 85) {
            setAct(Act.REST);
            actTicks = 100 + random.nextInt(200);
        } else {
            setAct(Act.STAND);
            waitTicks = 20 + random.nextInt(40);
        }
    }

    /** What it does once a hurt run is over; by default the same as after any walk. */
    protected void afterRun() {
        afterWalk();
    }

    protected void runSomewhere(ServerLevel level, int least, int most) {
        walkSomewhere(level, SPRINT, Act.SPRINT, least, most);
    }

    /** Startled by a hit: a few sprints away, one after another. */
    protected void startHurtRun(ServerLevel level) {
        hurtRun = HURT_RUNS;
        actTicks = 0;
        keepRunning(level);
    }

    /** The next sprint of a hurt run. With nowhere to run to, the run is over. */
    private void keepRunning(ServerLevel level) {
        runSomewhere(level, RUN_LEAST, RUN_MOST);
        if (act() != Act.SPRINT) {
            hurtRun = 0;
        }
    }

    /** True while it is sprinting away after a hit. */
    protected boolean running() {
        return hurtRun > 0 && act() == Act.SPRINT;
    }

    /** The puff it vanishes in. */
    protected void poof(ServerLevel level) {
        level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 10, 0.2, 0.2, 0.2, 0.03);
    }

    /** Heads for a reachable spot in the garden {@code least} to {@code most} blocks away; stays put if it finds none. */
    private void walkSomewhere(ServerLevel level, double speed, Act as, int least, int most) {
        Vec3 centre = gardenCentre();
        int radius = gardenRadius();
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.min(radius, least + random.nextDouble() * Math.max(0, most - least));
            double x = getX() + Math.cos(angle) * distance;
            double z = getZ() + Math.sin(angle) * distance;
            if (Math.hypot(x - centre.x, z - centre.z) > radius) {
                continue;
            }
            BlockPos ground = groundAt(level, BlockPos.containing(x, centre.y, z));
            if (ground == null) {
                continue;
            }
            Path path = getNavigation().createPath(ground, 0);
            if (path != null && path.canReach() && getNavigation().moveTo(path, speed)) {
                setAct(as);
                walkTicks = as == Act.SPRINT ? 30 + random.nextInt(30) : 60 + random.nextInt(80);
                return;
            }
        }
        setAct(Act.STAND);
        waitTicks = 30;
    }

    /** The first standable spot at or just below {@code around}'s column, up to a few blocks either way. */
    protected static @Nullable BlockPos groundAt(ServerLevel level, BlockPos around) {
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos at = around.above(dy);
            if (!level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty()
                    && level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                    && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()) {
                return at;
            }
        }
        return null;
    }

    // --- saving ---

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("kind", entityData.get(DATA_KIND));
        output.putInt("stage", entityData.get(DATA_STAGE));
        output.putInt("act", entityData.get(DATA_ACT));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        entityData.set(DATA_KIND, input.getIntOr("kind", 0));
        entityData.set(DATA_STAGE, input.getIntOr("stage", 0));
        setAct(Act.of(input.getIntOr("act", 0)));
    }
}
