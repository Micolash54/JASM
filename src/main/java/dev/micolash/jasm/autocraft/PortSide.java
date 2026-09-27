package dev.micolash.jasm.autocraft;

import net.minecraft.util.StringRepresentable;

/** What an Access Port shows on one side: nothing, a link to the network, or a machine, idle or with a job's items in it. */
public enum PortSide implements StringRepresentable {
    NONE("none"),
    LINK("link"),
    MACHINE("machine"),
    BUSY("busy");

    private final String name;

    PortSide(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
