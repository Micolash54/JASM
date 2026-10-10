package dev.micolash.jasm.bitling;

import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.config.Tuning;
import dev.micolash.jasm.core.BitlingKind;
import dev.micolash.jasm.core.BitlingStage;
import dev.micolash.jasm.registry.JasmItems;
import dev.micolash.jasm.registry.JasmTags;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A Bitling living free in the world. It roams around where it was found, or around a Data Crystal that drew it over.
 * Hand it a typed chip and it looks it over, nods, and becomes a Basic Bitling item. Feed it a Data Crystal and it eats
 * it and follows you for a while. Trade it a diamond and it leaves you a Block of Amethyst. Knocked out, it leaves
 * a little Crystal Dust.
 */
public class WildBitling extends BitlingBody {
    private static final int GARDEN_RADIUS = 12;
    private static final int VISIT_RADIUS = 8;
    /** How long it stays around a crystal it came to see. */
    private static final int VISIT_TICKS = 3_600;
    private static final int INSPECT_CHIP_TICKS = 40;
    private static final int NOD_TICKS = 20;
    private static final int INSPECT_CRYSTAL_TICKS = 30;
    private static final int EAT_TICKS = 30;
    private static final int SHAKE_TICKS = 20;
    /** Following, it walks to the player while this close, sprints when further off, and stops this near. */
    private static final double FOLLOW_WALK_WITHIN = 8;
    private static final double FOLLOW_STOP_WITHIN = 2.5;
    private static final int FOLLOW_PATH_EVERY = 10;
    /** Crystal Dust left behind when it is knocked out: this many at least, and this many more at most. */
    private static final int DEATH_DUST_LEAST = 1;
    private static final int DEATH_DUST_MOST = 2;

    /** What it is busy with after being handed something. */
    private enum Treat {
        CHIP,
        CRYSTAL,
        DIAMOND
    }

    private @Nullable Vec3 garden;
    private @Nullable BlockPos crystal;
    private int visitTicks;

    private @Nullable UUID followId;
    private @Nullable Player leader;
    private int followTicks;

    private @Nullable Treat treat;
    private @Nullable UUID feeder;
    /** Hurt in the middle of something: it runs once it has finished. */
    private boolean runAfter;
    /** Held when it was last saved, mid-sequence: dropped at its feet on the first tick back. */
    private ItemStack dropOnLoad = ItemStack.EMPTY;

    public WildBitling(EntityType<? extends WildBitling> type, Level level) {
        super(type, level);
        setKindAndStage(BitlingKind.BASIC, BitlingStage.BITLING);
        if (!level.isClientSide() && JasmConfig.SPEC.isLoaded()) {
            int health = JasmConfig.WILD_HEALTH.getAsInt();
            getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
            setHealth(health);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return BitlingBody.createAttributes().add(Attributes.MAX_HEALTH, JasmConfig.WILD_HEALTH.getDefault());
    }

    /** Wild Bitlings turn up on grass in daylight. */
    public static boolean checkSpawnRules(EntityType<WildBitling> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos,
            RandomSource random) {
        return level.getBlockState(pos.below()).is(BlockTags.ANIMALS_SPAWNABLE_ON) && level.getRawBrightness(pos, 0) > 8;
    }

    /** Comes to have a look at a Data Crystal, and stays around it for a while. */
    public void visit(BlockPos crystal) {
        this.crystal = crystal.immutable();
        visitTicks = VISIT_TICKS;
    }

    /** The crystal it is visiting, if any. */
    public @Nullable BlockPos visiting() {
        return visitTicks > 0 ? crystal : null;
    }

    public boolean following(Player player) {
        return followTicks > 0 && player.getUUID().equals(followId);
    }

    void followFor(Player player, int ticks) {
        followId = player.getUUID();
        leader = player;
        followTicks = ticks;
        // A walk with a player takes it away from any crystal it was visiting.
        crystal = null;
        visitTicks = 0;
    }

    private void stopFollowing() {
        followId = null;
        leader = null;
        followTicks = 0;
        garden = position();
        getNavigation().stop();
        setAct(Act.STAND);
        waitTicks = 20;
    }

    private boolean inSequence() {
        Act act = act();
        return act == Act.INSPECT || act == Act.NOD || act == Act.EAT;
    }

    @Override
    protected Vec3 gardenCentre() {
        if (visitTicks > 0 && crystal != null) {
            return Vec3.atBottomCenterOf(crystal);
        }
        return garden != null ? garden : position();
    }

    @Override
    protected int gardenRadius() {
        return visitTicks > 0 && crystal != null ? VISIT_RADIUS : GARDEN_RADIUS;
    }

    // --- staying wild ---

    @Override
    public boolean removeWhenFarAway(double distanceSquared) {
        return false;
    }

    // --- the mind ---

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (!dropOnLoad.isEmpty()) {
            spawnAtLocation(level, dropOnLoad);
            dropOnLoad = ItemStack.EMPTY;
        }
        if (garden == null) {
            garden = position();
        }
        if (inSequence()) {
            sequence(level);
        } else if (followTicks > 0 && !running()) {
            hurtRun = 0;
            follow(level);
        } else {
            roam(level);
        }
        if (visitTicks > 0 && --visitTicks == 0) {
            // Done looking: it makes its home wherever it is now.
            crystal = null;
            garden = position();
        }
    }

    /** One tick of looking something over, nodding or eating. A started sequence always finishes. */
    private void sequence(ServerLevel level) {
        getNavigation().stop();
        if (--actTicks > 0) {
            return;
        }
        switch (act()) {
            case INSPECT -> {
                if (treat == Treat.CRYSTAL) {
                    setAct(Act.EAT);
                    actTicks = EAT_TICKS;
                } else {
                    setAct(Act.NOD);
                    actTicks = NOD_TICKS;
                }
            }
            case NOD -> {
                if (treat == Treat.DIAMOND) {
                    trade(level);
                } else {
                    befriend(level);
                }
            }
            case EAT -> finishEating(level);
            default -> setAct(Act.STAND);
        }
    }

    /** Taken with the chip: it pops into a Basic Bitling item at its feet. */
    private void befriend(ServerLevel level) {
        poof(level);
        spawnAtLocation(level, new ItemStack(JasmItems.bitling(BitlingKind.BASIC, BitlingStage.BITLING)));
        setHeld(ItemStack.EMPTY);
        discard();
    }

    /** Pleased with the diamond: it sets a Block of Amethyst down at its feet and carries on. */
    private void trade(ServerLevel level) {
        setHeld(ItemStack.EMPTY);
        treat = null;
        if (JasmConfig.WILD_DIAMOND_AMETHYST.getAsBoolean()) {
            spawnAtLocation(level, new ItemStack(Items.AMETHYST_BLOCK));
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + getBbHeight() + 0.1, getZ(), 5, 0.2, 0.1, 0.2, 0.0);
        setAct(Act.STAND);
        waitTicks = 20;
        if (runAfter) {
            runAfter = false;
            startHurtRun(level);
        }
    }

    private void finishEating(ServerLevel level) {
        setHeld(ItemStack.EMPTY);
        treat = null;
        setAct(Act.STAND);
        waitTicks = 20;
        level.sendParticles(ParticleTypes.HEART, getX(), getY() + getBbHeight() + 0.1, getZ(), 4, 0.2, 0.1, 0.2, 0.0);
        Player player = feeder == null ? null : level.getPlayerByUUID(feeder);
        if (player == null && leader != null && leader.getUUID().equals(feeder)) {
            player = leader;
        }
        feeder = null;
        if (player != null) {
            followFor(player, Tuning.WILD_FOLLOW_TICKS);
        }
        if (runAfter) {
            runAfter = false;
            startHurtRun(level);
        }
    }

    private @Nullable Player leader(ServerLevel level) {
        if (leader == null && followId != null) {
            leader = level.getPlayerByUUID(followId);
        }
        return leader;
    }

    /** One tick of following its player; ends the follow when it should. */
    private void follow(ServerLevel level) {
        Player player = leader(level);
        if (player == null || player.isRemoved() || !player.isAlive() || player.level() != level
                || distanceTo(player) > JasmConfig.WILD_FOLLOW_RANGE.getAsInt() || --followTicks <= 0) {
            stopFollowing();
            return;
        }
        garden = position();
        if (act() == Act.SHAKE && actTicks > 0) {
            // Shaking its head: stand still till it is done.
            getNavigation().stop();
            if (--actTicks == 0) {
                setAct(Act.STAND);
            }
            return;
        }
        // No other timed act carries on while it follows.
        actTicks = 0;
        double distance = distanceTo(player);
        if (distance <= FOLLOW_STOP_WITHIN) {
            getNavigation().stop();
            setAct(Act.STAND);
            return;
        }
        boolean sprint = distance > FOLLOW_WALK_WITHIN;
        if (tickCount % FOLLOW_PATH_EVERY == 0 || getNavigation().isDone()) {
            getNavigation().moveTo(player, sprint ? SPRINT : WALK);
        }
        setAct(sprint ? Act.SPRINT : Act.WALK);
    }

    /** After a hurt run, a following Bitling goes straight back to its player instead of lying down or looking about. */
    @Override
    protected void afterRun() {
        if (followTicks > 0) {
            setAct(Act.STAND);
            actTicks = 0;
            waitTicks = 0;
        } else {
            super.afterRun();
        }
    }

    // --- being handed things and hurt ---

    private static boolean refused(ItemStack stack) {
        return stack.is(JasmItems.BLANK_CHIP) || stack.is(JasmItems.UNQUENCHED_LOGIC_CHIP) || stack.is(JasmItems.UNQUENCHED_MEMORY_CHIP);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean known = stack.is(JasmTags.TYPED_CHIPS) || stack.is(JasmItems.DATA_CRYSTAL) || stack.is(Items.DIAMOND) || refused(stack);
        if (!(level() instanceof ServerLevel level)) {
            return known || inSequence() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (inSequence()) {
            return InteractionResult.SUCCESS;
        }
        if (stack.is(JasmTags.TYPED_CHIPS)) {
            take(player, stack, Treat.CHIP, INSPECT_CHIP_TICKS);
            return InteractionResult.SUCCESS;
        }
        if (stack.is(Items.DIAMOND) && JasmConfig.WILD_DIAMOND_AMETHYST.getAsBoolean()) {
            take(player, stack, Treat.DIAMOND, INSPECT_CHIP_TICKS);
            return InteractionResult.SUCCESS;
        }
        if (stack.is(JasmItems.DATA_CRYSTAL) && followTicks <= 0) {
            take(player, stack, Treat.CRYSTAL, INSPECT_CRYSTAL_TICKS);
            return InteractionResult.SUCCESS;
        }
        if (known) {
            getNavigation().stop();
            hurtRun = 0;
            walkTicks = 0;
            setAct(Act.SHAKE);
            actTicks = SHAKE_TICKS;
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** Takes one of {@code stack} into its hands and starts looking it over. */
    private void take(Player player, ItemStack stack, Treat what, int inspectTicks) {
        setHeld(stack.copyWithCount(1));
        stack.consume(1, player);
        treat = what;
        feeder = what == Treat.CRYSTAL ? player.getUUID() : null;
        if (what == Treat.CRYSTAL) {
            leader = player;
        }
        getNavigation().stop();
        hurtRun = 0;
        walkTicks = 0;
        runAfter = false;
        setAct(Act.INSPECT);
        actTicks = inspectTicks;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        boolean hurt = super.hurtServer(level, source, damage);
        // A fall doesn't startle it: it would otherwise run off from a player it follows down a drop.
        if (hurt && isAlive() && !source.is(DamageTypeTags.IS_FALL)) {
            if (inSequence()) {
                runAfter = true;
            } else {
                // Startled into a run.
                startHurtRun(level);
            }
        }
        return hurt;
    }

    /** Knocked out, it vanishes in a puff and leaves a little Crystal Dust, but not what it was holding. */
    @Override
    public void die(DamageSource source) {
        setHeld(ItemStack.EMPTY);
        if (level() instanceof ServerLevel level) {
            poof(level);
            spawnAtLocation(level, new ItemStack(JasmItems.CRYSTAL_DUST.get(),
                    DEATH_DUST_LEAST + random.nextInt(DEATH_DUST_MOST - DEATH_DUST_LEAST + 1)));
        }
        discard();
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        return 0;
    }

    // --- saving ---

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.storeNullable("garden", Vec3.CODEC, garden);
        output.storeNullable("crystal", BlockPos.CODEC, crystal);
        output.putInt("visitTicks", visitTicks);
        output.storeNullable("follow", UUIDUtil.CODEC, followTicks > 0 ? followId : null);
        output.putInt("followTicks", followTicks);
        ItemStack held = !dropOnLoad.isEmpty() ? dropOnLoad : held();
        if (!held.isEmpty()) {
            output.store("held", ItemStack.CODEC, held);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        garden = input.read("garden", Vec3.CODEC).orElse(null);
        crystal = input.read("crystal", BlockPos.CODEC).orElse(null);
        visitTicks = input.getIntOr("visitTicks", 0);
        followId = input.read("follow", UUIDUtil.CODEC).orElse(null);
        followTicks = followId == null ? 0 : input.getIntOr("followTicks", 0);
        leader = null;
        // A sequence isn't saved: whatever it was holding goes back on the ground.
        dropOnLoad = input.read("held", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        setHeld(ItemStack.EMPTY);
        treat = null;
        feeder = null;
        if (inSequence()) {
            setAct(Act.STAND);
        }
    }
}
