package dev.micolash.jasm.transfer;

import com.mojang.serialization.Codec;
import dev.micolash.jasm.registry.JasmItems;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

public enum TransferPortKind implements StringRepresentable {
    INPUT("input_port", true, false),
    OUTPUT("output_port", false, true),
    INPUT_OUTPUT("input_output_port", true, true),
    STORAGE("storage_port", false, false);
    public static final Codec<TransferPortKind> CODEC = StringRepresentable.fromEnum(TransferPortKind::values);
    private final String id;
    private final boolean imports;
    private final boolean exports;
    TransferPortKind(String id, boolean imports, boolean exports) { this.id = id; this.imports = imports; this.exports = exports; }
    public String id() { return id; }
    public boolean imports() { return imports; }
    public boolean exports() { return exports; }
    @Override
    public String getSerializedName() { return id; }
    public Item item() {
        return switch (this) {
            case INPUT -> JasmItems.INPUT_PORT.get();
            case OUTPUT -> JasmItems.OUTPUT_PORT.get();
            case INPUT_OUTPUT -> JasmItems.INPUT_OUTPUT_PORT.get();
            case STORAGE -> JasmItems.STORAGE_PORT.get();
        };
    }
    public static @Nullable TransferPortKind of(ItemStack stack) {
        for (var kind : values()) if (stack.is(kind.item())) return kind;
        return null;
    }
}
