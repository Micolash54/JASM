package dev.micolash.jasm.deck;

import dev.micolash.jasm.pool.Material;
import dev.micolash.jasm.pool.NetworkPool;
import dev.micolash.jasm.pool.PoolAccess;
import dev.micolash.jasm.wafer.FluidAmounts;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Other mods' materials only live in the network's storage blocks, so this is the pool and the Deck's charge, nothing
 * else. The charge is the same as for fluids: an item's worth moves one share.
 */
public final class DeckMaterialStorage {
    private DeckMaterialStorage() {}

    public static Map<Material, Long> stacks(ServerPlayer player, ItemStack deck, @Nullable BlockPos avoid) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        return pool == null ? Map.of() : pool.materialStacks(avoid);
    }

    /** How much of {@code material}, up to {@code amount}, the pool would take and the charge pays for. Changes nothing. */
    public static long room(ServerPlayer player, ItemStack deck, Material material, long amount, @Nullable BlockPos avoid) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        return pool == null ? 0 : pool.roomMaterial(material, Math.min(amount, DeckStorage.affordableFluid(deck)), avoid);
    }

    /** Puts up to {@code amount} into the pool as far as the charge pays for, and pays. Returns how much went in. */
    public static long deposit(ServerPlayer player, ItemStack deck, Material material, long amount, @Nullable BlockPos avoid) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        amount = Math.min(amount, DeckStorage.affordableFluid(deck));
        if (pool == null || amount <= 0) return 0;
        long moved = pool.insertMaterialNow(material, amount, avoid);
        DeckStorage.pay(deck, FluidAmounts.shares(moved));
        return moved;
    }

    /** Takes up to {@code amount} out of the pool as far as the charge pays for, and pays. Returns how much came out. */
    public static long withdraw(ServerPlayer player, ItemStack deck, Material material, long amount, @Nullable BlockPos avoid) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        amount = Math.min(amount, DeckStorage.affordableFluid(deck));
        if (pool == null || amount <= 0) return 0;
        long taken = pool.extractMaterialNow(material, amount, avoid);
        DeckStorage.pay(deck, FluidAmounts.shares(taken));
        return taken;
    }

    /** Puts back what a move could not finish. No charge, and it does not matter what is left in the Deck. */
    public static long restore(ServerPlayer player, ItemStack deck, Material material, long amount, @Nullable BlockPos avoid) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        return pool == null || amount <= 0 ? 0 : pool.insertMaterialNow(material, amount, avoid);
    }

    /** Takes back what a move put in but the other side never gave up. No charge. */
    public static long takeBack(ServerPlayer player, ItemStack deck, Material material, long amount, @Nullable BlockPos avoid) {
        NetworkPool pool = PoolAccess.forDeck(player, deck);
        return pool == null || amount <= 0 ? 0 : pool.extractMaterialNow(material, amount, avoid);
    }
}
