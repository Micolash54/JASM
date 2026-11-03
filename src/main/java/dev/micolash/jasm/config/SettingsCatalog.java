package dev.micolash.jasm.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

/**
 * Every setting of a config file, grouped the way the file groups them: one section for each group that holds settings of
 * its own, in the order they are declared. Built once from the spec, so a new setting appears without touching the screen.
 */
public final class SettingsCatalog {
    /** One group of settings. {@code name} is the group's last word, like {@code slots}; the lone top-level setting is filed under its own key. */
    public record Section(String name, List<Setting> settings) {}

    /** The world rules, the same on the server and on every client that joins. */
    public static final SettingsCatalog WORLD = new SettingsCatalog(JasmConfig.SPEC);
    /** Each player's own choices. */
    public static final SettingsCatalog MINE = new SettingsCatalog(JasmClientConfig.SPEC);

    private final ModConfigSpec spec;
    private final List<Section> sections;
    private final Map<String, Setting> byId = new LinkedHashMap<>();

    private SettingsCatalog(ModConfigSpec spec) {
        this.spec = spec;
        List<Section> found = new ArrayList<>();
        collect(spec.getValues(), "", found);
        this.sections = List.copyOf(found);
        for (Section section : sections) {
            for (Setting setting : section.settings()) byId.put(setting.id(), setting);
        }
    }

    private static void collect(UnmodifiableConfig group, String name, List<Section> into) {
        List<Setting> own = new ArrayList<>();
        List<Map.Entry<String, UnmodifiableConfig>> inner = new ArrayList<>();
        for (UnmodifiableConfig.Entry entry : group.entrySet()) {
            if (entry.getValue() instanceof ModConfigSpec.ConfigValue<?> value) {
                Setting setting = Setting.of(value);
                if (setting != null) own.add(setting);
            } else if (entry.getValue() instanceof UnmodifiableConfig nested) {
                inner.add(Map.entry(entry.getKey(), nested));
            }
        }
        if (!own.isEmpty()) into.add(new Section(name.isEmpty() ? own.get(0).key() : name, List.copyOf(own)));
        for (Map.Entry<String, UnmodifiableConfig> nested : inner) collect(nested.getValue(), nested.getKey(), into);
    }

    public ModConfigSpec spec() {
        return spec;
    }

    public List<Section> sections() {
        return sections;
    }

    public @Nullable Setting find(String id) {
        return byId.get(id);
    }

    public Iterable<Setting> all() {
        return byId.values();
    }
}
