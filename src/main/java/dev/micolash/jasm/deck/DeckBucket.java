package dev.micolash.jasm.deck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.micolash.jasm.Notices;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.pool.PoolAccess;
import dev.micolash.jasm.registry.JasmComponents;
import dev.micolash.jasm.storage.WaferStore;
import dev.micolash.jasm.wafer.FluidAmounts;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import java.util.Map;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * A Crafting Deck used as a bucket. Sneak + right-click on a fluid source stores a bucket of it; on a block it places
 * a bucket of the picked fluid from storage, by the vanilla bucket's rules. When it can't, nothing moves and the reason
 * waits on the Deck until it is next opened.
 */
public final class DeckBucket {
    /** One vanilla bucket. */
    public static final long BUCKET = FluidAmounts.PER_BUCKET;

    /** Why the last use did nothing. */
    public enum Reason implements StringRepresentable {
        OUT_OF_FLUID("out_of_fluid", "message.jasm.deck.bucket.out_of"),
        NO_CHARGE("no_charge", "message.jasm.deck.no_power"),
        NO_ROOM("no_room", "message.jasm.deck.bucket.no_room"),
        NOT_ALLOWED("not_allowed", "message.jasm.deck.bucket.not_allowed");

        public static final Codec<Reason> CODEC = StringRepresentable.fromEnum(Reason::values);
        private static final IntFunction<Reason> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        public static final StreamCodec<ByteBuf, Reason> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

        private final String name;
        private final String key;

        Reason(String name, String key) {
            this.name = name;
            this.key = key;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** A use that did nothing, kept on the Deck so its screen can say why the next time it opens. */
    public record Miss(Reason reason, FluidResource fluid) {
        public static final Codec<Miss> CODEC = RecordCodecBuilder.create(i -> i.group(
                Reason.CODEC.fieldOf("reason").forGetter(Miss::reason),
                FluidResource.CODEC.optionalFieldOf("fluid", FluidResource.EMPTY).forGetter(Miss::fluid)
        ).apply(i, Miss::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Miss> STREAM_CODEC = StreamCodec.composite(
                Reason.STREAM_CODEC, Miss::reason,
                FluidResource.STREAM_CODEC, Miss::fluid,
                Miss::new);

        public Component message() {
            return Component.translatable(reason.key, fluid.getHoverName());
        }
    }

    private DeckBucket() {}

    /** The fluid this Deck places, or empty. */
    public static FluidResource picked(ItemStack deck) {
        return deck.getOrDefault(JasmComponents.DECK_BUCKET.get(), FluidResource.EMPTY);
    }

    /** Picks {@code fluid}, or unpicks it when it is already the picked one. */
    public static void togglePick(ItemStack deck, FluidResource fluid) {
        if (fluid.equals(picked(deck))) {
            deck.remove(JasmComponents.DECK_BUCKET.get());
        } else {
            deck.set(JasmComponents.DECK_BUCKET.get(), fluid);
        }
    }

    /**
     * Forgets the pick when the Deck holds none of that fluid any more, so its icon stops showing a drop. Does nothing
     * when a linked Deck can't reach its network, which may hold the fluid. Returns whether it forgot.
     */
    public static boolean forgetIfGone(ItemStack deck, ServerPlayer player, DeckStorage.Checked storage) {
        FluidResource key = picked(deck);
        if (key.isEmpty() || !seesNetwork(deck, player) || storage.countFluid(key) > 0) {
            return false;
        }
        deck.remove(JasmComponents.DECK_BUCKET.get());
        return true;
    }

    /** As above, for a Deck nobody has a storage view of: the once-a-second check on Decks a player carries. */
    public static void forgetIfGone(ItemStack deck, ServerPlayer player) {
        if (deck.has(JasmComponents.DECK_BUCKET.get()) && DeckItem.worksIn(deck, player.level())) {
            forgetIfGone(deck, player, DeckStorage.checked(WaferStore.get(player.level().getServer()), deck, player));
        }
    }

    /** As above, for an open Deck screen: the pick must be one of the fluids its list shows, or the player can't undo it. */
    public static void forgetIfUnlisted(ItemStack deck, Map<FluidResource, long[]> listed, @Nullable NetworkPool pool) {
        FluidResource key = picked(deck);
        if (key.isEmpty() || (pool == null && deck.has(JasmComponents.DECK_NETWORK.get()))) {
            return;
        }
        long[] shown = listed.get(key);
        if (shown == null || shown[0] <= 0) {
            deck.remove(JasmComponents.DECK_BUCKET.get());
        }
    }

    private static boolean seesNetwork(ItemStack deck, ServerPlayer player) {
        return !deck.has(JasmComponents.DECK_NETWORK.get()) || PoolAccess.forDeck(player, deck) != null;
    }

    /** The vanilla bucket that holds {@code fluid}, or null when the fluid has none and so can't be placed. */
    public static @Nullable BucketItem bucketFor(FluidResource fluid) {
        return !fluid.isEmpty() && fluid.getFluid().getBucket() instanceof BucketItem bucket && bucket.getContent() == fluid.getFluid() ? bucket : null;
    }

    /** Tells the player why the last use did nothing, if it did, and forgets it. Called right after the Deck opens. */
    public static void tellMiss(ServerPlayer player, ItemStack deck) {
        Miss miss = deck.get(JasmComponents.DECK_BUCKET_MISS.get());
        // Only into the Deck's own screen: never the action bar. If the screen didn't open, the message waits.
        if (miss != null && player.containerMenu instanceof DeckMenu) {
            deck.remove(JasmComponents.DECK_BUCKET_MISS.get());
            Notices.bad(player, miss.message());
        }
    }

    /**
     * Sneak + right-click with a Crafting Deck. Returns false when there is nothing to do here (looking at the sky, or
     * at a block with no fluid picked), so the Deck opens as usual.
     */
    public static boolean use(ServerPlayer player, ItemStack deck) {
        Level level = player.level();
        if (!DeckItem.isCrafting(deck) || !DeckItem.worksIn(deck, level)) {
            return false;
        }
        BlockHitResult source = Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (source.getType() == HitResult.Type.BLOCK && collectable(level, source.getBlockPos())) {
            collect(player, deck, source);
            return true;
        }
        if (picked(deck).isEmpty()) {
            return false;
        }
        BlockHitResult hit = Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        place(player, deck, hit);
        return true;
    }

    /** A fluid source a bucket could take: a source block or a waterlogged one. */
    static boolean collectable(Level level, BlockPos pos) {
        return level.getFluidState(pos).isSource() && level.getBlockState(pos).getBlock() instanceof BucketPickup;
    }

    /** Whether the player may use a bucket on this block, as vanilla asks. */
    private static boolean allowed(ServerPlayer player, ItemStack deck, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Direction direction = hit.getDirection();
        return player.level().mayInteract(player, pos) && player.mayUseItemAt(pos.relative(direction), direction, deck);
    }

    /**
     * Asks claim and protection mods about the block the Deck works on, through the same right-click event a bucket's
     * click fires. A vanilla bucket gets a second check from claim mods that know it by its item; the Deck isn't one,
     * so the click on the exact block is what they can stop.
     */
    private static boolean clickAllowed(ServerPlayer player, ItemStack deck, BlockHitResult hit) {
        InteractionHand hand = player.getItemInHand(InteractionHand.OFF_HAND) == deck ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        PlayerInteractEvent.RightClickBlock event = CommonHooks.onRightClickBlock(player, hand, hit.getBlockPos(), hit);
        return !event.isCanceled() && event.getUseItem() != TriState.FALSE;
    }

    /**
     * Asks the same for the spot the fluid would fill, as for any block a player puts down. Asked before anything
     * changes, so the spot still holds what is there now.
     */
    private static boolean placeAllowed(ServerPlayer player, BlockPos placePos, Direction face) {
        BlockSnapshot spot = BlockSnapshot.create(player.level().dimension(), player.level(), placePos);
        return !EventHooks.onBlockPlace(player, spot, face);
    }

    /** Stores a bucket of the source at {@code hit}. Takes nothing unless storage and charge cover the whole bucket. */
    public static void collect(ServerPlayer player, ItemStack deck, BlockHitResult hit) {
        Level level = player.level();
        BlockPos pos = hit.getBlockPos();
        if (!collectable(level, pos)) {
            return;
        }
        FluidState fluidState = level.getFluidState(pos);
        FluidResource key = FluidResource.of(fluidState.getType());
        if (!allowed(player, deck, hit) || !clickAllowed(player, deck, hit)) {
            miss(deck, Reason.NOT_ALLOWED, key);
            return;
        }
        var storage = DeckStorage.checked(WaferStore.get(level.getServer()), deck, player);
        if (DeckStorage.affordableFluid(deck) < BUCKET) {
            miss(deck, Reason.NO_CHARGE, key);
            return;
        }
        if (storage.roomFluid(key, BUCKET) < BUCKET) {
            miss(deck, Reason.NO_ROOM, key);
            return;
        }
        BlockState state = level.getBlockState(pos);
        BucketPickup pickup = (BucketPickup) state.getBlock();
        if (pickup.pickupBlock(player, level, pos, state).isEmpty()) {
            return;
        }
        // Room and charge were checked this tick, so the whole bucket goes in.
        storage.depositFluid(key, BUCKET);
        pickup.getPickupSound(state).ifPresent(sound -> level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F));
        level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
        deck.remove(JasmComponents.DECK_BUCKET_MISS.get());
    }

    /**
     * Places a bucket of the picked fluid against {@code hit}, as a vanilla bucket would when not sneaking: into a
     * waterloggable block for water, else beside the block. Water in the Nether boils away and is used up, as in
     * vanilla. Nothing is taken when the spot can't hold fluid.
     */
    public static void place(ServerPlayer player, ItemStack deck, BlockHitResult hit) {
        Level level = player.level();
        FluidResource key = picked(deck);
        BucketItem bucket = bucketFor(key);
        if (bucket == null) {
            return;
        }
        if (!allowed(player, deck, hit)) {
            miss(deck, Reason.NOT_ALLOWED, key);
            return;
        }
        Fluid fluid = key.getFluid();
        BlockPos pos = hit.getBlockPos();
        BlockState clicked = level.getBlockState(pos);
        BlockPos placePos = fluid == Fluids.WATER && holdsLiquid(level, pos, clicked, fluid) ? pos : pos.relative(hit.getDirection());
        if (!canHold(level, placePos, fluid) && !canHold(level, pos.relative(hit.getDirection()), fluid)) {
            return;
        }
        // Claim mods get their say before anything moves: on the clicked block, and on the spot the fluid would fill,
        // which can sit across a claim border from it.
        if (!clickAllowed(player, deck, hit) || !placeAllowed(player, placePos, hit.getDirection())) {
            miss(deck, Reason.NOT_ALLOWED, key);
            return;
        }
        var storage = DeckStorage.checked(WaferStore.get(level.getServer()), deck, player);
        if (storage.countFluid(key) < BUCKET) {
            forgetIfGone(deck, player, storage);
            miss(deck, Reason.OUT_OF_FLUID, key);
            return;
        }
        if (DeckStorage.affordableFluid(deck) < BUCKET) {
            miss(deck, Reason.NO_CHARGE, key);
            return;
        }
        int charge = deck.getOrDefault(JasmComponents.ENERGY.get(), 0);
        long taken = storage.withdrawFluid(key, BUCKET, false);
        // The player isn't passed on: vanilla never fills a waterloggable block while its user sneaks, and here they always do.
        if (taken < BUCKET || !bucket.emptyContents(null, level, placePos, hit, new ItemStack(bucket))) {
            if (taken > 0) storage.restoreQuietlyFluid(key, taken);
            deck.set(JasmComponents.ENERGY.get(), charge);
            if (taken < BUCKET) miss(deck, Reason.OUT_OF_FLUID, key);
            return;
        }
        deck.remove(JasmComponents.DECK_BUCKET_MISS.get());
        forgetIfGone(deck, player, storage);
    }

    private static boolean holdsLiquid(Level level, BlockPos pos, BlockState state, Fluid fluid) {
        return state.getBlock() instanceof LiquidBlockContainer container && container.canPlaceLiquid(null, level, pos, state, fluid);
    }

    /** Vanilla's test for a spot a bucket can empty into. */
    private static boolean canHold(Level level, BlockPos pos, Fluid fluid) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced(fluid) || holdsLiquid(level, pos, state, fluid);
    }

    private static void miss(ItemStack deck, Reason reason, FluidResource fluid) {
        deck.set(JasmComponents.DECK_BUCKET_MISS.get(), new Miss(reason, fluid));
    }
}
