package dev.micolash.jasm.guide;

import dev.micolash.jasm.Jasm;
import guideme.GuidesCommon;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Opens the JASM guide. The pages and the hold-a-key shortcut come from GuideME. */
public class GuideBookItem extends Item {
    public static final Identifier GUIDE_ID = Jasm.id("guide");

    public GuideBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            GuidesCommon.openGuide(player, GUIDE_ID);
        }
        return InteractionResult.FAIL;
    }
}
