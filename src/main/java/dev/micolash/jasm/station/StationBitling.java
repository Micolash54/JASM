package dev.micolash.jasm.station;

import dev.micolash.jasm.bitling.BitlingBody;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
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
public class StationBitling extends BitlingBody {
    private enum Mode { ROAM, HOME, CHARGING }

    private static final double TIRED_WALK = 0.085;
    private static final double HOME_WALK = 0.215;

    private @Nullable BlockPos station;
    private Mode mode = Mode.ROAM;
    private int stuckTicks;
    private @Nullable Vec3 lastPosition;

    public StationBitling(EntityType<? extends StationBitling> type, Level level) {
        super(type, level);
    }

    /** Called once, by the station that makes it. */
    public void setup(BlockPos station, BitlingKind kind, BitlingStage stage) {
        this.station = station.immutable();
        setKindAndStage(kind, stage);
        int health = JasmConfig.STATION_HEALTH.getAsInt();
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        setHealth(health);
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
        return act() == Act.REST ? StationStatus.RESTING : StationStatus.ROAMING;
    }

    private @Nullable BitlingStationBlockEntity stationEntity() {
        return station != null && level().getBlockEntity(station) instanceof BitlingStationBlockEntity home ? home : null;
    }

    @Override
    protected Vec3 gardenCentre() {
        BitlingStationBlockEntity home = stationEntity();
        return home != null ? home.padCentre() : position();
    }

    @Override
    protected int gardenRadius() {
        BitlingStationBlockEntity home = stationEntity();
        return home != null ? home.radius() : 4;
    }

    // --- staying a Bitling that belongs somewhere ---

    @Override
    public boolean removeWhenFarAway(double distanceSquared) {
        return false;
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

    /** Time to go home: the battery is low, or the network is full and the station has stopped. */
    private static boolean low(BitlingStationBlockEntity home) {
        return home.stopped() || home.critterEnergy() <= 0 || home.batteryFraction() <= JasmConfig.STATION_RETURN_AT.getAsDouble();
    }

    private void charging(BitlingStationBlockEntity home) {
        getNavigation().stop();
        Vec3 pad = home.padCentre();
        if (position().distanceToSqr(pad) > 0.01) {
            snapTo(pad.x, pad.y, pad.z);
        }
        setDeltaMovement(Vec3.ZERO);
        if (act() == Act.STARTLED && actTicks > 0) {
            if (--actTicks == 0) {
                setAct(Act.RECHARGE);
            }
            return;
        }
        setAct(Act.RECHARGE);
        if (home.batteryFraction() >= 1 && !home.stopped()) {
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
        roam(level);
    }

    // --- being petted and hurt ---

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!(level() instanceof ServerLevel level) || station == null || !(level.getBlockEntity(station) instanceof BitlingStationBlockEntity home)) {
            return InteractionResult.SUCCESS;
        }
        getNavigation().stop();
        actTicks = 0;
        boolean sleeping = mode == Mode.CHARGING || act() == Act.REST;
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
        if (hurt && isAlive() && mode == Mode.ROAM && station != null && level.getBlockEntity(station) instanceof BitlingStationBlockEntity) {
            // Startled into a run.
            startHurtRun(level);
        }
        return hurt;
    }

    /** Knocked out: it vanishes in a puff, and the station brings it back after a wait. */
    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel level) {
            poof(level);
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
        output.putInt("mode", mode.ordinal());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        station = input.read("station", BlockPos.CODEC).orElse(null);
        int saved = input.getIntOr("mode", 0);
        mode = saved >= 0 && saved < Mode.values().length ? Mode.values()[saved] : Mode.ROAM;
    }
}
