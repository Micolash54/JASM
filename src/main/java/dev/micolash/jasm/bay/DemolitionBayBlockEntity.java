package dev.micolash.jasm.bay;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmClientConfig;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmTags;
import dev.micolash.jasm.storage.WaferSettings;
import java.util.ArrayList;
import java.util.List;
import dev.micolash.jasm.wafer.FluidAmounts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/** Breaks the block in front of it into its grid, as its owner would. */
public class DemolitionBayBlockEntity extends BayBlockEntity {
    /** The laser's shot. Only ever played in players' own games, so it isn't registered. */
    private static final SoundEvent LASER = SoundEvent.createVariableRangeEvent(Jasm.id("bay.laser"));

    private ItemEnchantments enchantments = ItemEnchantments.EMPTY;

    // Client side only.
    private @Nullable BlockPos cracking;
    private boolean fired;

    public DemolitionBayBlockEntity(BlockPos pos, BlockState state) {
        super(JasmBlocks.DEMOLITION_BAY_ENTITY.get(), pos, state);
    }

    @Override
    public BayKind kind() {
        return BayKind.DEMOLITION;
    }

    @Override
    protected Plan plan(ServerLevel level, BlockPos front) {
        BlockState state = level.getBlockState(front);
        FluidState fluid = level.getFluidState(front);
        if (scoopable(state, fluid)) {
            if (passes(fluid.getType())) {
                if (!level.mayInteract(fakePlayer(level), front)) return Plan.idle(BayStatus.NOT_ALLOWED);
                return roomForBucket(fluid.getType()) ? Plan.scoop(JasmConfig.DEMOLITION_SCOOP_COST.getAsInt())
                        : Plan.idle(BayStatus.TANK_FULL);
            }
            // A plain pool is refused; a waterlogged block may still be broken.
            if (state.liquid()) return Plan.idle(BayStatus.FILTERED);
        }
        if (!breakable(level, front, state)) return Plan.idle(BayStatus.SLEEPING);
        if (!passes(state)) return Plan.idle(BayStatus.FILTERED);
        FakePlayer player = fakePlayer(level);
        if (!level.mayInteract(player, front)) return Plan.idle(BayStatus.NOT_ALLOWED);
        List<ItemStack> drops = drops(level, front, state, player);
        if (!fitsAll(drops)) return Plan.idle(BayStatus.GRID_FULL);
        return Plan.work(breakCost(level, front, state, drops));
    }

    @Override
    protected BayStatus act(ServerLevel level, BlockPos front) {
        BlockState state = level.getBlockState(front);
        FluidState fluid = level.getFluidState(front);
        if (scoopable(state, fluid) && passes(fluid.getType())) {
            FakePlayer player = fakePlayer(level);
            if (!level.mayInteract(player, front)) return BayStatus.NOT_ALLOWED;
            if (!roomForBucket(fluid.getType())) return BayStatus.TANK_FULL;
            int cost = JasmConfig.DEMOLITION_SCOOP_COST.getAsInt();
            if (energy.getAmountAsInt() < cost) return BayStatus.NO_POWER;
            // The bay's own tank: only what ports and pipes see refuses fluid.
            if (FluidUtil.tryPickupFluid(tank, player, level, front, (TransactionContext) null).isEmpty()) return BayStatus.SLEEPING;
            pay(cost);
            return BayStatus.WORKING;
        }
        if (scoopable(state, fluid) && state.liquid()) return BayStatus.FILTERED;
        if (!breakable(level, front, state)) return BayStatus.SLEEPING;
        if (!passes(state)) return BayStatus.FILTERED;
        FakePlayer player = fakePlayer(level);
        if (!level.mayInteract(player, front)) return BayStatus.NOT_ALLOWED;
        List<ItemStack> drops = drops(level, front, state, player);
        if (!fitsAll(drops)) return BayStatus.GRID_FULL;
        int cost = breakCost(level, front, state, drops);
        if (energy.getAmountAsInt() < cost) return BayStatus.NO_POWER;
        // The same event a player breaking it would fire, so claim mods can stop it.
        if (CommonHooks.fireBlockBreak(level, GameType.SURVIVAL, player, front, state).isCanceled()) return BayStatus.NOT_ALLOWED;
        if (!level.destroyBlock(front, false)) return BayStatus.SLEEPING;
        for (ItemStack drop : drops) {
            insertIntoGridPartly(drop);
            // Whatever no longer fits lies in front and is picked up once there's room.
            if (!drop.isEmpty()) Block.popResource(level, front, drop);
        }
        // Blocks that throw their contents out as they break (barrels, chests) leave them lying here. The filter let
        // the block through, so what it held comes along too.
        for (ItemEntity spilled : level.getEntitiesOfClass(ItemEntity.class, new AABB(front).inflate(0.2), ItemEntity::isAlive)) {
            storeEntity(spilled);
        }
        if (unbreakingPays(level)) pay(cost);
        return BayStatus.WORKING;
    }

    /** A source block a bucket could take, such as still water or lava, or a waterlogged block's water. */
    private static boolean scoopable(BlockState state, FluidState fluid) {
        return fluid.isSource() && state.getBlock() instanceof BucketPickup && !fluid.is(JasmTags.DEMOLITION_BLACKLIST_FLUIDS);
    }

    private boolean roomForBucket(Fluid fluid) {
        try (var tx = Transaction.openRoot()) {
            return tank.insert(FluidResource.of(fluid), FluidAmounts.PER_BUCKET, tx) == FluidAmounts.PER_BUCKET;
        }
    }

    /** Items lying against the front face go in, as far as they fit, at no cost. */
    @Override
    protected void everyHalfSecond(ServerLevel level) {
        if (allFull() || !level.isLoaded(front())) return;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, faceBox(worldPosition, facing()), ItemEntity::isAlive)) {
            // What the filter refuses stays on the ground.
            if (passes(entity.getItem())) storeEntity(entity);
        }
    }

    private boolean allFull() {
        for (int slot = 0; slot < GRID; slot++) {
            ItemStack stack = items.get(slot);
            if (stack.getCount() < stack.getMaxStackSize()) return false;
        }
        return true;
    }

    /** A thin slice of the front space against the bay's face. */
    static AABB faceBox(BlockPos pos, Direction facing) {
        AABB box = new AABB(pos.relative(facing));
        double depth = 0.25;
        return switch (facing) {
            case UP -> box.setMaxY(box.minY + depth);
            case DOWN -> box.setMinY(box.maxY - depth);
            case NORTH -> box.setMinZ(box.maxZ - depth);
            case SOUTH -> box.setMaxZ(box.minZ + depth);
            case WEST -> box.setMinX(box.maxX - depth);
            case EAST -> box.setMaxX(box.minX + depth);
        };
    }

    /**
     * Whether the filter lets this block be broken. The block is named by its item where it has one, and by the block
     * itself for block tags and blocks without an item.
     */
    private boolean passes(BlockState state) {
        WaferSettings filter = filter();
        if (filter.rules().isEmpty()) return true;
        Item item = state.getBlock().asItem();
        var block = BuiltInRegistries.BLOCK.wrapAsHolder(state.getBlock());
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        for (WaferSettings.Filter rule : filter.rules()) {
            if (!rule.enabled()) continue;
            if (item != Items.AIR && rule.matches(item) || rule.matches(block, id)) return rule.allow();
        }
        return !filter.hasAllow();
    }

    private static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.liquid() || state.is(JasmTags.DEMOLITION_BLACKLIST_BLOCKS)) return false;
        return state.getDestroySpeed(level, pos) >= 0;
    }

    /** What the block drops to a diamond tool of its kind carrying the bay's enchantments. */
    private List<ItemStack> drops(ServerLevel level, BlockPos pos, BlockState state, FakePlayer player) {
        ItemStack tool;
        boolean fallback = false;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) tool = new ItemStack(Items.DIAMOND_PICKAXE);
        else if (state.is(BlockTags.MINEABLE_WITH_AXE)) tool = new ItemStack(Items.DIAMOND_AXE);
        else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) tool = new ItemStack(Items.DIAMOND_SHOVEL);
        else if (state.is(BlockTags.MINEABLE_WITH_HOE)) tool = new ItemStack(Items.DIAMOND_HOE);
        else {
            tool = new ItemStack(Items.DIAMOND_PICKAXE);
            fallback = true;
        }
        if (!enchantments.isEmpty()) {
            EnchantmentHelper.setEnchantments(tool, enchantments);
            fallback = false;
        }
        // As by hand: no tool where none is needed and nothing enchants it (glass breaks to nothing).
        if (!state.requiresCorrectToolForDrops() && fallback) tool = ItemStack.EMPTY;
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, tool)) {
            if (!drop.isEmpty()) drops.add(drop);
        }
        return drops;
    }

    private int breakCost(ServerLevel level, BlockPos pos, BlockState state, List<ItemStack> drops) {
        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        int efficiency = enchantments.getLevel(registry.getOrThrow(Enchantments.EFFICIENCY));
        int unbreaking = enchantments.getLevel(registry.getOrThrow(Enchantments.UNBREAKING));
        int total = 0;
        for (var entry : enchantments.entrySet()) total += entry.getIntValue();
        int count = 0;
        for (ItemStack drop : drops) count += drop.getCount();
        double points = BayCost.breakPoints(state.getDestroySpeed(level, pos), count, efficiency, total - efficiency - unbreaking);
        return BayCost.fe(points, JasmConfig.DEMOLITION_FE_PER_POINT.getAsInt());
    }

    private boolean unbreakingPays(ServerLevel level) {
        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        int unbreaking = enchantments.getLevel(registry.getOrThrow(Enchantments.UNBREAKING));
        return BayCost.pays(unbreaking, unbreaking <= 0 ? 0 : level.getRandom().nextInt(unbreaking + 1));
    }

    /** Takes what fits from an item lying in front; the rest stays on the ground. */
    protected void storeEntity(ItemEntity entity) {
        if (entity.getItem().is(JasmTags.DEMOLITION_BLACKLIST_ITEMS)) return;
        ItemStack stack = entity.getItem().copy();
        int stored = insertIntoGridPartly(stack);
        if (stored <= 0) return;
        if (stack.isEmpty()) entity.discard();
        else entity.setItem(stack);
    }

    public ItemEnchantments enchantments() {
        return enchantments;
    }

    public void setEnchantments(ItemEnchantments enchantments) {
        this.enchantments = enchantments;
        setChanged();
    }

    // Players' games get the enchantments too, for the panel.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (!enchantments.isEmpty()) {
            ItemEnchantments.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), enchantments)
                    .ifSuccess(encoded -> tag.put("enchantments", encoded));
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        super.handleUpdateTag(input);
        enchantments = input.read("enchantments", ItemEnchantments.CODEC).orElse(ItemEnchantments.EMPTY);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("enchantments", ItemEnchantments.CODEC, enchantments);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        enchantments = input.read("enchantments", ItemEnchantments.CODEC).orElse(ItemEnchantments.EMPTY);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!enchantments.isEmpty()) components.set(DataComponents.ENCHANTMENTS, enchantments);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        enchantments = components.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("enchantments");
    }

    // --- in players' games ---

    @Override
    protected void cycleStartedHere(Level level) {
        fired = false;
    }

    /**
     * The laser fires half way through the working Bitling's clip and the cracks spread from there to the hit, in step
     * with what the renderer shows.
     */
    @Override
    public void clientTick(Level level) {
        long start = clientCycleStart();
        int ticks = clientCycleTicks();
        long since = start == Long.MIN_VALUE ? Long.MAX_VALUE : level.getGameTime() - start;
        boolean running = ticks > 0 && since >= 0 && since < ticks;
        float clip = -1;
        if (running) {
            boolean pair = BayTiming.pair(ticks);
            clip = BayTiming.clip(BayTiming.progress(since / (float) ticks, pair, true), BayTiming.window(ticks, pair));
        }
        if (clip >= 0.5F && !fired) fire(level);
        if (clip >= 0.5F && !clientScoop()) {
            BlockPos front = front();
            if (cracking != null && !cracking.equals(front)) clearCracks(level);
            cracking = front;
            level.destroyBlockProgress(crackId(), front, (int) Math.min(9, (clip - 0.5F) * 20));
        } else if (cracking != null) {
            clearCracks(level);
        }
    }

    private void fire(Level level) {
        fired = true;
        // Each player can silence every bay's laser in their own game.
        if (JasmClientConfig.bayLaserMuted()) return;
        BlockPos front = front();
        float pitch = clientScoop() ? 0.75F : 0.95F + level.getRandom().nextFloat() * 0.15F;
        level.playLocalSound(front.getX() + 0.5, front.getY() + 0.5, front.getZ() + 0.5, LASER, SoundSource.BLOCKS, 0.6F, pitch, false);
    }

    /** Never a real entity's number, so the cracks can't be mixed up with a player's. */
    private int crackId() {
        return -1 - Math.floorMod(worldPosition.hashCode(), 1 << 20);
    }

    private void clearCracks(Level level) {
        if (cracking != null) level.destroyBlockProgress(crackId(), cracking, -1);
        cracking = null;
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) clearCracks(level);
        super.setRemoved();
    }
}
