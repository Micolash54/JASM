package dev.micolash.jasm.station;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The little Bitling that lives around a Bitling Station. It is only a body: the station keeps its battery (the critter
 * item's charge), decides when it exists, and takes it back if it goes missing. It wanders, sprints, hops, looks around
 * and lies down, walks home when its battery runs low, sits on the pad to charge, and can be petted. Knocked out, it
 * vanishes and the station brings it back after a wait.
 */
public class StationBitling extends PathfinderMob {
    /** What the Bitling is doing; the client picks a loop from it. */
    public enum Act {
        STAND, WALK, SPRINT, LOOK, HOP, REST, TIRED, STARTLED, PETTED, RECHARGE;

        public static Act of(int ordinal) {
            Act[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : STAND;
        }
    }

    private enum Mode { ROAM, HOME, CHARGING }

    private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(StationBitling.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_STAGE = SynchedEntityData.defineId(StationBitling.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ACT = SynchedEntityData.defineId(StationBitling.class, EntityDataSerializers.INT);

    private static final double WALK = 0.5;
    private static final double SPRINT = 1.0;
    private static final double TIRED_WALK = 0.085;
    private static final double HOME_WALK = 0.215;
    /** Degrees it turns in a tick. */
    private static final float TURN_STEP = 10;
    /** Facing further off than this, it stops and turns on the spot before walking on. */
    private static final float TURN_ON_SPOT = 35;

    private @Nullable BlockPos station;
    private Mode mode = Mode.ROAM;
    private Act act = Act.STAND;
    /** Ticks left of a timed act (a hop, a rest, a startle). */
    private int actTicks;
    /** Ticks to stand still before choosing the next thing to do. */
    private int waitTicks = 20;
    private int stuckTicks;
    /** Ticks left of the current walk or sprint before it stops where it is. */
    private int walkTicks;
    /** Ticks left of running away after being hurt. */
    private int hurtRun;
    private @Nullable Vec3 lastPosition;

    public StationBitling(EntityType<? extends StationBitling> type, Level level) {
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
    }

    /** Called once, by the station that makes it. */
    public void setup(BlockPos station, BitlingKind kind, BitlingStage stage) {
        this.station = station.immutable();
        entityData.set(DATA_KIND, kind.ordinal());
        entityData.set(DATA_STAGE, stage.ordinal());
        int health = JasmConfig.STATION_HEALTH.getAsInt();
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        setHealth(health);
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

    /** The station this Bitling belongs to. */
    public @Nullable BlockPos home() {
        return station;
    }

    public boolean sittingOnPad() {
        return mode == Mode.CHARGING;
    }

    /** What the station's screen says the Bitling is doing. */
    public StationStatus status() {
        if (mode == Mode.CHARGING) {
            return StationStatus.RECHARGING;
        }
        if (mode == Mode.HOME) {
            return StationStatus.HEADING_HOME;
        }
        return act == Act.REST ? StationStatus.RESTING : StationStatus.ROAMING;
    }

    private void setAct(Act next) {
        act = next;
        if (entityData.get(DATA_ACT) != next.ordinal()) {
            entityData.set(DATA_ACT, next.ordinal());
        }
    }

    // --- staying a Bitling that belongs somewhere ---

    @Override
    public boolean removeWhenFarAway(double distanceSquared) {
        return false;
    }

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
        TurningMoveControl(StationBitling mob) {
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

    // --- the mind ---

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (station == null) {
            discard();
            return;
        }
        if (!level.isLoaded(station)) {
            // The station's area isn't loaded: stand still until it is.
            getNavigation().stop();
            return;
        }
        if (!(level.getBlockEntity(station) instanceof BitlingStationBlockEntity home)) {
            discard();
            return;
        }
        if (tickCount % 20 == 0 && !home.claims(getUUID())) {
            discard();
            return;
        }
        switch (mode) {
            case CHARGING -> charging(home);
            case HOME -> heading(level, home);
            case ROAM -> roaming(level, home);
        }
    }

    private static boolean low(BitlingStationBlockEntity home) {
        return home.critterEnergy() <= 0 || home.batteryFraction() <= JasmConfig.STATION_RETURN_AT.getAsDouble();
    }

    private void charging(BitlingStationBlockEntity home) {
        getNavigation().stop();
        Vec3 pad = home.padCentre();
        if (position().distanceToSqr(pad) > 0.01) {
            snapTo(pad.x, pad.y, pad.z);
        }
        setDeltaMovement(Vec3.ZERO);
        if (act == Act.STARTLED && actTicks > 0) {
            if (--actTicks == 0) {
                setAct(Act.RECHARGE);
            }
            return;
        }
        setAct(Act.RECHARGE);
        if (home.batteryFraction() >= 1) {
            mode = Mode.ROAM;
            setAct(Act.HOP);
            actTicks = 14;
            waitTicks = 10;
        }
    }

    private void heading(ServerLevel level, BitlingStationBlockEntity home) {
        Vec3 pad = home.padCentre();
        double flat = Math.hypot(getX() - pad.x, getZ() - pad.z);
        if (flat < 0.5 && Math.abs(getY() - pad.y) < 1.2) {
            arrive(home);
            return;
        }
        boolean tired = home.critterEnergy() <= 0;
        setAct(tired ? Act.TIRED : Act.WALK);
        if (lastPosition != null && position().distanceToSqr(lastPosition) < 0.0004) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPosition = position();
        int limit = JasmConfig.STATION_STUCK_SECONDS.getAsInt() * 20;
        if (tickCount % 10 == 0 || getNavigation().isDone()) {
            Path path = getNavigation().createPath(BlockPos.containing(pad), 0);
            if (path == null || !path.canReach()) {
                // Empty and no way home: straight to the pad.
                if (tired || stuckTicks >= limit) {
                    teleportHome(level, home);
                    return;
                }
                stuckTicks++;
            } else {
                getNavigation().moveTo(path, tired ? TIRED_WALK : HOME_WALK);
            }
        }
        if (stuckTicks >= limit) {
            teleportHome(level, home);
        }
    }

    private void teleportHome(ServerLevel level, BitlingStationBlockEntity home) {
        level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 8, 0.15, 0.15, 0.15, 0.02);
        arrive(home);
        level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 8, 0.15, 0.15, 0.15, 0.02);
    }

    private void arrive(BitlingStationBlockEntity home) {
        Vec3 pad = home.padCentre();
        getNavigation().stop();
        snapTo(pad.x, pad.y, pad.z);
        mode = Mode.CHARGING;
        stuckTicks = 0;
        actTicks = 0;
        setAct(Act.RECHARGE);
    }

    private void roaming(ServerLevel level, BitlingStationBlockEntity home) {
        if (low(home)) {
            mode = Mode.HOME;
            stuckTicks = 0;
            actTicks = 0;
            hurtRun = 0;
            getNavigation().stop();
            return;
        }
        Vec3 pad = home.padCentre();
        if (actTicks > 0) {
            getNavigation().stop();
            if (--actTicks == 0) {
                setAct(Act.STAND);
                waitTicks = 20 + random.nextInt(40);
            }
            return;
        }
        double flat = Math.hypot(getX() - pad.x, getZ() - pad.z);
        if (flat > home.radius() + 1.0) {
            // Pushed out of its garden: walk back in.
            if (tickCount % 20 == 0 || getNavigation().isDone()) {
                getNavigation().moveTo(pad.x, pad.y, pad.z, 1, WALK);
            }
            setAct(Act.WALK);
            return;
        }
        if (act == Act.WALK || act == Act.SPRINT) {
            // A walk lasts a few seconds, then it stops and does something else.
            if (--walkTicks <= 0 || getNavigation().isDone() || getNavigation().isStuck()) {
                if (hurtRun > 0 && --hurtRun > 0) {
                    runSomewhere(level, home, 4, 8);
                    return;
                }
                hurtRun = 0;
                getNavigation().stop();
                afterWalk();
            }
            return;
        }
        if (--waitTicks > 0) {
            return;
        }
        int pick = random.nextInt(100);
        if (pick < 35) {
            walkSomewhere(level, home, WALK, Act.WALK, 2, home.radius());
        } else if (pick < 50) {
            walkSomewhere(level, home, SPRINT, Act.SPRINT, 3, Math.min(home.radius(), 8));
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
        if (act == Act.STAND) {
            waitTicks = 20 + random.nextInt(40);
        }
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

    private void runSomewhere(ServerLevel level, BitlingStationBlockEntity home, int least, int most) {
        walkSomewhere(level, home, SPRINT, Act.SPRINT, least, most);
    }

    /** Heads for a reachable spot in the garden {@code least} to {@code most} blocks away; stays put if it finds none. */
    private void walkSomewhere(ServerLevel level, BitlingStationBlockEntity home, double speed, Act as, int least, int most) {
        Vec3 pad = home.padCentre();
        int radius = home.radius();
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.min(radius, least + random.nextDouble() * Math.max(0, most - least));
            double x = getX() + Math.cos(angle) * distance;
            double z = getZ() + Math.sin(angle) * distance;
            if (Math.hypot(x - pad.x, z - pad.z) > radius) {
                continue;
            }
            BlockPos ground = groundAt(level, BlockPos.containing(x, pad.y, z));
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
    private static @Nullable BlockPos groundAt(ServerLevel level, BlockPos around) {
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

    // --- being petted and hurt ---

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!(level() instanceof ServerLevel level) || station == null || !(level.getBlockEntity(station) instanceof BitlingStationBlockEntity home)) {
            return InteractionResult.SUCCESS;
        }
        getNavigation().stop();
        actTicks = 0;
        boolean sleeping = mode == Mode.CHARGING || act == Act.REST;
        if (sleeping) {
            setAct(Act.STARTLED);
            actTicks = 20;
            if (mode == Mode.ROAM && low(home)) {
                mode = Mode.HOME;
                stuckTicks = 0;
                actTicks = 0;
            }
        } else {
            setAct(Act.PETTED);
            actTicks = 30;
            level.sendParticles(ParticleTypes.HEART, getX(), getY() + getBbHeight() + 0.1, getZ(), 4, 0.2, 0.1, 0.2, 0.0);
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        boolean hurt = super.hurtServer(level, source, damage);
        if (hurt && isAlive() && mode == Mode.ROAM && station != null && level.getBlockEntity(station) instanceof BitlingStationBlockEntity home) {
            // Startled into a run.
            hurtRun = 3;
            actTicks = 0;
            runSomewhere(level, home, 4, 8);
        }
        return hurt;
    }

    /** Knocked out: it vanishes in a puff, and the station brings it back after a wait. */
    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 10, 0.2, 0.2, 0.2, 0.03);
            if (station != null && level.getBlockEntity(station) instanceof BitlingStationBlockEntity home && home.claims(getUUID())) {
                home.knockedOut();
            }
        }
        discard();
    }

    // --- saving ---

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("station", BlockPos.CODEC, station);
        output.putInt("kind", entityData.get(DATA_KIND));
        output.putInt("stage", entityData.get(DATA_STAGE));
        output.putInt("mode", mode.ordinal());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        station = input.read("station", BlockPos.CODEC).orElse(null);
        entityData.set(DATA_KIND, input.getIntOr("kind", 0));
        entityData.set(DATA_STAGE, input.getIntOr("stage", 0));
        int saved = input.getIntOr("mode", 0);
        mode = saved >= 0 && saved < Mode.values().length ? Mode.values()[saved] : Mode.ROAM;
    }
}
