package dev.micolash.jasm.acceptor;

import dev.micolash.jasm.battery.BatteryBlockEntity;
import dev.micolash.jasm.network.CableNetwork;
import dev.micolash.jasm.network.NetworkPowerSource;
import dev.micolash.jasm.network.Networks;
import dev.micolash.jasm.network.PowerReceiver;
import dev.micolash.jasm.network.PowerSides;
import dev.micolash.jasm.registry.JasmBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The Power Acceptor block. It moves power between a JASM network and other mods' blocks on every side it has, as its mode
 * says (see {@link AcceptorFlow}). Out, it empties the Batteries it touches and those on the cables of the networks it touches.
 */
public class PowerAcceptorBlockEntity extends BlockEntity implements NetworkPowerSource, MenuProvider, ModeHolder {
    private final PowerSides sides = new PowerSides();
    private final CableNetwork[] poolNetworks = new CableNetwork[6];
    private final PowerReceiver[] poolSides = new PowerReceiver[6];
    private @Nullable List<BatteryBlockEntity> pool;
    private final AcceptorFlow flow = new AcceptorFlow(new Around());

    public PowerAcceptorBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.POWER_ACCEPTOR_ENTITY.get(), pos, state);
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, PowerAcceptorBlockEntity acceptor) {
        acceptor.flow.tick((ServerLevel) level);
    }

    @Override
    public AcceptorMode mode() {
        return flow.mode();
    }

    @Override
    public void setMode(AcceptorMode mode) {
        if (flow.setMode(mode)) {
            setChanged();
        }
    }

    /** What other mods see on that side: they may push power in, never take it out. */
    public EnergyHandler input(@Nullable Direction side) {
        return flow.input(side);
    }

    @Override
    public void neighboursChanged() {
        sides.changed();
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        ContainerLevelAccess access = ContainerLevelAccess.create(level, worldPosition);
        return new PowerAcceptorMenu(containerId, this, flow.data(), user -> access.evaluate((world, pos) -> world.getBlockEntity(pos) == this
                && user.distanceToSqr(Vec3.atCenterOf(pos)) <= 64, true));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("mode", AcceptorMode.CODEC, mode());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // Acceptors saved before it had modes only took power in.
        flow.setMode(input.read("mode", AcceptorMode.CODEC).orElse(AcceptorMode.INPUT));
    }

    /** All six neighbours: JASM blocks are what it feeds, the rest are the outside blocks it trades with. */
    private final class Around implements AcceptorFlow.Site {
        @Override
        public @Nullable Level level() {
            return level;
        }

        @Override
        public BlockPos pos() {
            return worldPosition;
        }

        @Override
        public void refresh(ServerLevel level) {
            sides.refresh(level, worldPosition);
        }

        @Override
        public boolean open(Direction side) {
            return !sides.jasm(side);
        }

        @Override
        public int forward(int amount, TransactionContext transaction) {
            int taken = 0;
            for (Direction side : Direction.values()) {
                if (taken >= amount) {
                    break;
                }
                PowerReceiver target = sides.receiver(side);
                if (target != null) {
                    taken += target.insert(amount - taken, transaction);
                }
            }
            return taken;
        }

        /**
         * The Battery blocks it may empty: those it touches, and those on the cables of the networks of the blocks it touches.
         * A network is replaced whenever something on it changes, so the list is only built again when a side or a network
         * is new.
         */
        @Override
        public List<BatteryBlockEntity> batteries(ServerLevel level) {
            boolean same = true;
            for (Direction side : Direction.values()) {
                PowerReceiver receiver = sides.receiver(side);
                CableNetwork network = receiver == null ? null : Networks.at(level, worldPosition.relative(side));
                if (poolNetworks[side.ordinal()] != network || poolSides[side.ordinal()] != receiver) {
                    poolNetworks[side.ordinal()] = network;
                    poolSides[side.ordinal()] = receiver;
                    same = false;
                }
            }
            if (same && pool != null) {
                return pool;
            }
            List<BatteryBlockEntity> found = new ArrayList<>();
            for (Direction side : Direction.values()) {
                if (poolSides[side.ordinal()] != null && level.getBlockEntity(worldPosition.relative(side)) instanceof BatteryBlockEntity battery) {
                    found.add(battery);
                }
            }
            for (CableNetwork network : poolNetworks) {
                if (network != null) {
                    found.addAll(network.batteries());
                }
            }
            pool = found;
            return found;
        }
    }
}
