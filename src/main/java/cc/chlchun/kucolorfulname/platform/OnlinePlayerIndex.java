package cc.chlchun.kucolorfulname.platform;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.entity.Player;

/**
 * A thread-safe index of immutable online-player identity snapshots.
 *
 * <p>The index deliberately does not retain {@link Player} instances. Join and
 * quit listeners should update it from the player's owning context.</p>
 */
public final class OnlinePlayerIndex {
    private final ConcurrentMap<UUID, Entry> entriesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> idsByName = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();

    public void add(Player player) {
        Objects.requireNonNull(player, "player");
        add(player.getUniqueId(), player.getName());
    }

    public void add(UUID playerId, String playerName) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Entry replacement = new Entry(playerId, playerName);
        String normalizedName = normalize(playerName);

        synchronized (mutationLock) {
            Entry previous = entriesById.put(playerId, replacement);
            if (previous != null) {
                idsByName.remove(normalize(previous.name()), playerId);
            }
            idsByName.put(normalizedName, playerId);
        }
    }

    public void remove(Player player) {
        Objects.requireNonNull(player, "player");
        remove(player.getUniqueId());
    }

    public boolean remove(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (mutationLock) {
            Entry removed = entriesById.remove(playerId);
            if (removed == null) {
                return false;
            }
            idsByName.remove(normalize(removed.name()), playerId);
            return true;
        }
    }

    public boolean contains(UUID playerId) {
        return entriesById.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    public Optional<Entry> entry(UUID playerId) {
        return Optional.ofNullable(entriesById.get(Objects.requireNonNull(playerId, "playerId")));
    }

    public Optional<UUID> findId(String playerName) {
        Objects.requireNonNull(playerName, "playerName");
        return Optional.ofNullable(idsByName.get(normalize(playerName)));
    }

    public Set<UUID> snapshotIds() {
        return Set.copyOf(entriesById.keySet());
    }

    public List<Entry> snapshot() {
        return List.copyOf(entriesById.values());
    }

    public List<String> snapshotNames() {
        return entriesById.values().stream()
                .map(Entry::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public int size() {
        return entriesById.size();
    }

    public boolean isEmpty() {
        return entriesById.isEmpty();
    }

    public void clear() {
        synchronized (mutationLock) {
            entriesById.clear();
            idsByName.clear();
        }
    }

    private static String normalize(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }

    public record Entry(UUID playerId, String name) {
        public Entry {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(name, "name");
        }
    }
}
