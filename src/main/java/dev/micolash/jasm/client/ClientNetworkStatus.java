package dev.micolash.jasm.client;

import dev.micolash.jasm.network.NetworkStatusSync;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Whether the open machine screen's network is full, as the server last said. */
public final class ClientNetworkStatus {
    private static final Map<Integer, NetworkStatusSync.Full> LAST = new HashMap<>();

    private ClientNetworkStatus() {}

    public static void receive(NetworkStatusSync.Full payload) {
        if (!LAST.containsKey(payload.containerId())) {
            // A new screen: the old one's news no longer applies.
            LAST.clear();
        }
        LAST.put(payload.containerId(), payload);
    }

    /** The network's numbers while the screen with this id is on a full network, otherwise null. */
    public static NetworkStatusSync.@Nullable Full full(int containerId) {
        NetworkStatusSync.Full last = LAST.get(containerId);
        return last != null && last.full() ? last : null;
    }
}
