package dev.micolash.jasm.config;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * One setting of a config file, as the settings screen and the server's save both see it: what kind of value it holds,
 * what it may be, and how a value is written as text and read back. Out-of-range text never becomes a value.
 */
public final class Setting {
    /** The kinds of control a setting gets. */
    public enum Kind { SWITCH, WHOLE, DECIMAL, CHOICE, WHOLE_LIST, DECIMAL_LIST }

    /** Most entries a list may have: a Network Brain tower of 64 floors, plus the lone brain. */
    public static final int MAX_LIST = 65;

    private final ModConfigSpec.ConfigValue<?> value;
    private final ModConfigSpec.ValueSpec spec;
    private final Kind kind;
    private final List<String> path;

    private Setting(ModConfigSpec.ConfigValue<?> value, Kind kind) {
        this.value = value;
        this.spec = value.getSpec();
        this.kind = kind;
        this.path = value.getPath();
    }

    /** The setting for this config value, or null for one the screen doesn't offer (lists of words). */
    static @Nullable Setting of(ModConfigSpec.ConfigValue<?> value) {
        Object def = value.getDefault();
        Kind kind;
        if (def instanceof Boolean) {
            kind = Kind.SWITCH;
        } else if (def instanceof Integer) {
            kind = Kind.WHOLE;
        } else if (def instanceof Double) {
            kind = Kind.DECIMAL;
        } else if (def instanceof Enum<?>) {
            kind = Kind.CHOICE;
        } else if (def instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Integer) {
            kind = Kind.WHOLE_LIST;
        } else if (def instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number) {
            kind = Kind.DECIMAL_LIST;
        } else {
            return null;
        }
        return new Setting(value, kind);
    }

    public Kind kind() {
        return kind;
    }

    public ModConfigSpec.ConfigValue<?> value() {
        return value;
    }

    /** The key in the file, like {@code starter}. Names and descriptions in the language file are filed under it. */
    public String key() {
        return path.get(path.size() - 1);
    }

    /** The whole path, like {@code deck.slots.starter}: what the screen and the server call this setting. */
    public String id() {
        return String.join(".", path);
    }

    /** True when a change reaches the game only after the world reloads (a server restart on a server). */
    public boolean needsRestart() {
        return spec.restartType() != ModConfigSpec.RestartType.NONE;
    }

    public boolean isList() {
        return kind == Kind.WHOLE_LIST || kind == Kind.DECIMAL_LIST;
    }

    public Object defaultValue() {
        return normalise(value.getDefault());
    }

    /** What the file holds now, not what the game kept from when the world loaded. Needs the config to be loaded. */
    public Object current() {
        return normalise(value.getRaw());
    }

    /** The lowest and highest a whole or decimal setting may be; null for the other kinds. */
    public @Nullable Number min() {
        return spec.getRange() instanceof ModConfigSpec.Range<?> range && range.getMin() instanceof Number n ? n : null;
    }

    public @Nullable Number max() {
        return spec.getRange() instanceof ModConfigSpec.Range<?> range && range.getMax() instanceof Number n ? n : null;
    }

    /** The choices of a choice setting, in the order they are declared. */
    public List<Enum<?>> choices() {
        if (kind != Kind.CHOICE) return List.of();
        Enum<?>[] all = ((Enum<?>) value.getDefault()).getDeclaringClass().getEnumConstants();
        return List.of(all);
    }

    /** Lists keep whole numbers whole and everything else as decimals, so the file always reads the same way. */
    private Object normalise(Object raw) {
        if (raw instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object o : list) {
                copy.add(kind == Kind.WHOLE_LIST ? (Object) ((Number) o).intValue() : (Object) ((Number) o).doubleValue());
            }
            return List.copyOf(copy);
        }
        return raw;
    }

    /** Whether this value may be saved: the right type, inside the range, and a list with at least one entry. */
    public boolean accepts(@Nullable Object candidate) {
        if (candidate == null) return false;
        if (!isList()) {
            return switch (kind) {
                case SWITCH -> candidate instanceof Boolean && spec.test(candidate);
                case WHOLE -> candidate instanceof Integer && spec.test(candidate);
                case DECIMAL -> candidate instanceof Double d && Double.isFinite(d) && spec.test(candidate);
                default -> candidate instanceof Enum<?> e && choices().contains(e) && spec.test(candidate);
            };
        }
        if (!(candidate instanceof List<?> list) || list.isEmpty() || list.size() > MAX_LIST) return false;
        for (Object o : list) {
            if (kind == Kind.WHOLE_LIST ? !(o instanceof Integer) : !(o instanceof Double d && Double.isFinite(d))) return false;
            if (spec instanceof ModConfigSpec.ListValueSpec listSpec && !listSpec.testElement(o)) return false;
        }
        return spec.test(list);
    }

    /** One value as the text that goes into a box, or over the network. */
    public String format(Object v) {
        if (v instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            for (Object o : list) out.append(out.isEmpty() ? "" : ",").append(format(o));
            return out.toString();
        }
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof Double d) {
            // 2.0 reads as 2.0, but 0.1 + 0.2 style noise never reaches a box
            return d == Math.rint(d) && Math.abs(d) < 1e9 ? Double.toString(d) : Double.toString(Math.round(d * 1e6) / 1e6);
        }
        return String.valueOf(v);
    }

    /** The value this text stands for, or null when it isn't one this setting accepts. */
    public @Nullable Object parse(String text) {
        Object parsed;
        try {
            parsed = switch (kind) {
                case SWITCH -> text.strip().equalsIgnoreCase("true") ? Boolean.TRUE : text.strip().equalsIgnoreCase("false") ? Boolean.FALSE : null;
                case WHOLE -> Integer.valueOf(text.strip());
                case DECIMAL -> Double.valueOf(text.strip());
                case CHOICE -> choice(text.strip());
                case WHOLE_LIST, DECIMAL_LIST -> parseList(text);
            };
        } catch (NumberFormatException e) {
            return null;
        }
        return accepts(parsed) ? parsed : null;
    }

    private @Nullable Enum<?> choice(String name) {
        for (Enum<?> e : choices()) {
            if (e.name().equalsIgnoreCase(name)) return e;
        }
        return null;
    }

    private @Nullable List<Object> parseList(String text) {
        if (text.isBlank()) return null;
        List<Object> out = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            out.add(kind == Kind.WHOLE_LIST ? (Object) Integer.valueOf(part.strip()) : (Object) Double.valueOf(part.strip()));
        }
        return out;
    }

    /** Writes the value into the loaded config. It is on disk only after {@link ModConfigSpec#save()}. */
    @SuppressWarnings("unchecked")
    public void write(Object v) {
        ((ModConfigSpec.ConfigValue<Object>) value).set(v);
    }
}
