package cc.chlchun.kucolorfulname.util;

import cc.chlchun.kucolorfulname.platform.OnlinePlayerIndex;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerResolver {
    private PlayerResolver() { }

    public static Optional<PlayerTarget> resolve(String input, OnlinePlayerIndex onlinePlayers) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        Optional<UUID> onlineId = onlinePlayers.findId(input);
        if (onlineId.isPresent()) {
            UUID playerId = onlineId.get();
            String playerName = onlinePlayers.entry(playerId)
                    .map(OnlinePlayerIndex.Entry::name)
                    .orElse(input);
            return Optional.of(new PlayerTarget(playerId, playerName));
        }
        try {
            UUID playerId = UUID.fromString(input);
            String label = onlinePlayers.entry(playerId)
                    .map(OnlinePlayerIndex.Entry::name)
                    .orElse(playerId.toString());
            return Optional.of(new PlayerTarget(playerId, label));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public record PlayerTarget(UUID playerId, String label) {
        public PlayerTarget {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(label, "label");
        }
    }
}
