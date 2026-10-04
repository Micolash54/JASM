package dev.micolash.jasm.bay;

import dev.micolash.jasm.registry.JasmBlocks;
import dev.micolash.jasm.registry.JasmMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;

public enum BayKind {
    DEPLOYMENT,
    DEMOLITION;

    public Block block() {
        return this == DEPLOYMENT ? JasmBlocks.DEPLOYMENT_BAY.get() : JasmBlocks.DEMOLITION_BAY.get();
    }

    public MenuType<BayMenu> menu() {
        return this == DEPLOYMENT ? JasmMenus.DEPLOYMENT_BAY.get() : JasmMenus.DEMOLITION_BAY.get();
    }

    /** Whether players, ports and hoppers may put things into the grid and tank. */
    public boolean takesIn() {
        return this == DEPLOYMENT;
    }
}
