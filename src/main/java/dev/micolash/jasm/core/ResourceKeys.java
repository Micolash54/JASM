package dev.micolash.jasm.core;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.neoforged.neoforge.transfer.resource.DataComponentHolderResource;

/**
 * Maps keyed by item or fluid that still find a stored item when another mod's data on it hashes badly. Some mods give
 * every copy of their data its own hash (Stellaris' filled Fluid Cells), others change data the item shares after it
 * was stored (Apotheosis renaming its curios), and a plain hash map then never finds the stored copy again. Here the
 * hash leaves out the item's shared defaults and the values of data that may not hash by content; whether two keys
 * are the same is still the full check over every value, so different items never merge.
 */
public final class ResourceKeys {
    private ResourceKeys() {}

    /** A map in insertion order, keyed this way. Otherwise it behaves like a {@link LinkedHashMap}. */
    public static <K, V> Map<K, V> newMap() {
        return new KeyedMap<>();
    }

    /** The hash used for {@code key}: the stable one for items and fluids, the key's own for anything else. */
    public static int hash(Object key) {
        if (key == null) return 0;
        if (!(key instanceof DataComponentHolderResource<?> resource)) return key.hashCode();
        // the item's own defaults are left out: they are the same for every copy, and a mod may change them in place
        int h = System.identityHashCode(resource.value());
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : resource.getComponentsPatch().entrySet()) {
            DataComponentType<?> type = entry.getKey();
            int part = System.identityHashCode(type);
            if (entry.getValue().isEmpty()) {
                part = ~part;
            } else {
                Object value = entry.getValue().get();
                if (CONTENT_HASHED.get(value.getClass()) && !Nested.TYPES.containsKey(type)) {
                    part = part * 31 + value.hashCode();
                }
            }
            h += part * 0x9E3779B9;
        }
        return h;
    }

    // values that hash by what they hold: records, enums, numbers, text and the game's own classes. Another mod's own
    // class may not (that's the Fluid Cell's data), so only its kind counts; copies that differ there share a bucket
    // and are told apart by the full check.
    private static final ClassValue<Boolean> CONTENT_HASHED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (type.isRecord() || Enum.class.isAssignableFrom(type) || Number.class.isAssignableFrom(type)
                    || type == String.class || type == Boolean.class) {
                return true;
            }
            String name = type.getName();
            return name.startsWith("net.minecraft.") || name.startsWith("com.mojang.");
        }
    };

    // the game's kinds that hold other items, whose hash comes from those items and so from any mod's data on them.
    // Loaded only once a real item is hashed.
    private static final class Nested {
        static final Map<DataComponentType<?>, Boolean> TYPES = new IdentityHashMap<>();

        static {
            for (DataComponentType<?> type : new DataComponentType<?>[] {DataComponents.CONTAINER, DataComponents.BUNDLE_CONTENTS,
                    DataComponents.CHARGED_PROJECTILES, DataComponents.USE_REMAINDER}) {
                TYPES.put(type, Boolean.TRUE);
            }
        }
    }

    /** A key with its hash worked out once. */
    private static final class Key {
        final Object value;
        final int hash;

        Key(Object value) {
            this.value = value;
            this.hash = hash(value);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && hash == other.hash && Objects.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class KeyedMap<K, V> extends AbstractMap<K, V> {
        private final LinkedHashMap<Key, V> map = new LinkedHashMap<>();
        private Set<Map.Entry<K, V>> entries;

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean containsKey(Object key) {
            return map.containsKey(new Key(key));
        }

        @Override
        public V get(Object key) {
            return map.get(new Key(key));
        }

        @Override
        public V getOrDefault(Object key, V fallback) {
            return map.getOrDefault(new Key(key), fallback);
        }

        @Override
        public V put(K key, V value) {
            return map.put(new Key(key), value);
        }

        @Override
        public V remove(Object key) {
            return map.remove(new Key(key));
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            if (entries == null) {
                entries = new AbstractSet<>() {
                    @Override
                    public Iterator<Map.Entry<K, V>> iterator() {
                        Iterator<Map.Entry<Key, V>> it = map.entrySet().iterator();
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() {
                                return it.hasNext();
                            }

                            @Override
                            public Map.Entry<K, V> next() {
                                return new View<>(it.next());
                            }

                            @Override
                            public void remove() {
                                it.remove();
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return map.size();
                    }
                };
            }
            return entries;
        }
    }

    /** One entry, backed by the map's own entry, so it acts as a {@link LinkedHashMap} entry would. */
    private record View<K, V>(Map.Entry<Key, V> inner) implements Map.Entry<K, V> {
        @Override
        @SuppressWarnings("unchecked")
        public K getKey() {
            return (K) inner.getKey().value;
        }

        @Override
        public V getValue() {
            return inner.getValue();
        }

        @Override
        public V setValue(V value) {
            return inner.setValue(value);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Map.Entry<?, ?> e && Objects.equals(getKey(), e.getKey()) && Objects.equals(getValue(), e.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
        }
    }
}
