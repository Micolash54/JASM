package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.battery.BatteryBlockEntity;
import dev.micolash.jasm.generator.CombustionGeneratorBlockEntity;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.DataCableBlockEntity;
import dev.micolash.jasm.network.NetworkPowerSource;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.network.PowerReceiver;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The Power Acceptor in the thin port shape: it sits on one face of a cable, with its plate out and its neck on the cable,
 * and trades power between that cable's network and the block in front of it. It works like the full block (see
 * {@link AcceptorFlow}) but only on that one face. Out, it feeds a JASM block in front too, unless it is on the cable's
 * network.
 */
public final class ThinPowerAcceptor implements ModeHolder, MenuProvider {
    private final DataCableBlockEntity host;
    private final Direction side;
    private final AcceptorFlow flow = new AcceptorFlow(new OnCable());
    private boolean dirty = true;
    /** Whether the block in front is an outside block, rather than one of JASM's. */
    private boolean frontOpen;
    /** The JASM block in front, if it is one, and how network power reaches it. */
    private @Nullable BlockEntity front;
    private @Nullable PowerReceiver frontReceiver;
    private @Nullable PowerReceiver cable;
    private @Nullable CableNetwork poolNetwork;
    private @Nullable List<BatteryBlockEntity> pool;
    private final AcceptorGenerators generatorCache = new AcceptorGenerators();
    private List<CombustionGeneratorBlockEntity> generators = List.of();

    public ThinPowerAcceptor(DataCableBlockEntity host, Direction side) {
        this.host = host;
        this.side = side;
    }

    public Direction side() {
        return side;
    }

    /** FE a tick going through, in or out as the mode says, averaged over the last second. */
    public int averageFlow() {
        return flow.flow();
    }

    /** The block in front, if it holds or takes power; see {@link AcceptorFlow#served()}. */
    public net.minecraft.world.level.block.@Nullable Block served() {
        return flow.served();
    }

    public void tick(ServerLevel level) {
        flow.tick(level);
    }

    @Override
    public AcceptorMode mode() {
        return flow.mode();
    }

    @Override
    public void setMode(AcceptorMode mode) {
        if (flow.setMode(mode)) {
            host.setChanged();
        }
    }

    /** What other mods see on this face: they may push power in, never take it out. */
    public EnergyHandler input() {
        return flow.input(side);
    }

    public void save(ValueOutput output) {
        output.store("mode", AcceptorMode.CODEC, mode());
    }

    public void load(ValueInput input) {
        flow.setMode(AcceptorMode.load(input));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("item.jasm.thin_power_acceptor");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PowerAcceptorMenu(containerId, this, flow.data(), user -> !host.isRemoved() && host.acceptor(side) == this
                && user.distanceToSqr(Vec3.atCenterOf(host.getBlockPos())) <= 64);
    }

    /** The cable it sits on is what it feeds; the block in front is the only outside block it trades with. */
    private final class OnCable implements AcceptorFlow.Site {
        @Override
        public @Nullable Level level() {
            return host.getLevel();
        }

        @Override
        public BlockPos pos() {
            return host.getBlockPos();
        }

        @Override
        public boolean refresh(ServerLevel level) {
            // A block placed without a neighbour update is caught within a second.
            BlockPos here = host.getBlockPos();
            if ((level.getGameTime() + here.asLong() + side.ordinal()) % 20 == 0) {
                dirty = true;
            }
            if (!dirty) {
                return false;
            }
            dirty = false;
            BlockPos front = here.relative(side);
            ThinPowerAcceptor.this.front = null;
            frontReceiver = null;
            if (!level.isLoaded(front)) {
                frontOpen = false;
                dirty = true;
                return true;
            }
            BlockEntity entity = level.getBlockEntity(front);
            PowerReceiver receiver = entity == null ? null : PowerReceiver.of(entity);
            frontOpen = !(entity instanceof NetworkPowerSource) && receiver == null;
            if (!frontOpen) {
                ThinPowerAcceptor.this.front = entity;
                frontReceiver = receiver;
            }
            return true;
        }

        @Override
        public boolean open(Direction direction) {
            return direction == side && frontOpen;
        }

        /** The JASM block in front is fed unless it is on the cable's network, which is where the power comes from. */
        @Override
        public @Nullable EnergyHandler jasmTarget(ServerLevel level, Direction direction, Supplier<@Nullable EnergyHandler> faces) {
            if (direction != side || frontOpen || front == null) {
                return null;
            }
            batteries(level);
            if (frontReceiver != null && !(front instanceof BatteryBlockEntity) && poolNetwork != null
                    && Networks.at(level, front.getBlockPos()) == poolNetwork) {
                return null;
            }
            return AcceptorFlow.jasmTarget(level, front, frontReceiver, faces);
        }

        @Override
        public int forward(int amount, TransactionContext transaction) {
            if (cable == null || !cable.live()) {
                cable = PowerReceiver.of(host);
            }
            return cable == null ? 0 : cable.insert(amount, transaction);
        }

        /** The Batteries on its cable's network. A network is replaced whenever something on it changes. */
        @Override
        public List<BatteryBlockEntity> batteries(ServerLevel level) {
            CableNetwork network = Networks.at(level, host.getBlockPos());
            if (pool == null || network != poolNetwork) {
                poolNetwork = network;
                pool = network == null ? List.of() : network.batteries();
                generators = generatorCache.find(network == null ? List.of() : List.of(network));
            }
            return pool;
        }

        @Override
        public List<CombustionGeneratorBlockEntity> generators(ServerLevel level) {
            batteries(level);
            if (generatorCache.changed() || generators.stream().anyMatch(CombustionGeneratorBlockEntity::isRemoved)) {
                generators = generatorCache.find(poolNetwork == null ? List.of() : List.of(poolNetwork));
            }
            return generators;
        }
    }
}
