package cc.chlchun.kucolorfulname.platform;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Tracks static reset frames until every relevant consumer has observed them. */
final class AnimationDeliveryState<T> {
    private final Map<UUID, PendingReset<T>> pendingResets = new LinkedHashMap<>();
    private long nextResetVersion;

    synchronized void retainReset(
            UUID subjectId,
            T value,
            Set<UUID> currentConsumers
    ) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(currentConsumers, "currentConsumers");
        Set<UUID> awaiting = new HashSet<>(currentConsumers);
        awaiting.add(subjectId);
        pendingResets.put(subjectId,
                new PendingReset<>(++nextResetVersion, value, awaiting));
    }

    synchronized void cancelResetsFor(Set<UUID> dynamicSubjects) {
        Objects.requireNonNull(dynamicSubjects, "dynamicSubjects");
        dynamicSubjects.forEach(pendingResets::remove);
    }

    synchronized void retainOnline(Set<UUID> onlinePlayers) {
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        pendingResets.keySet().removeIf(playerId -> !onlinePlayers.contains(playerId));
    }

    synchronized void registerConsumer(UUID consumerId) {
        Objects.requireNonNull(consumerId, "consumerId");
        pendingResets.values().forEach(reset -> reset.awaiting().add(consumerId));
    }

    synchronized void unregisterConsumer(UUID consumerId) {
        Objects.requireNonNull(consumerId, "consumerId");
        pendingResets.values().removeIf(reset -> {
            reset.awaiting().remove(consumerId);
            return reset.awaiting().isEmpty();
        });
    }

    synchronized void removePlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        pendingResets.remove(playerId);
        unregisterConsumer(playerId);
    }

    synchronized DeliverySnapshot<T> snapshot(Map<UUID, T> currentDynamicNames) {
        Objects.requireNonNull(currentDynamicNames, "currentDynamicNames");
        Map<UUID, T> names = new LinkedHashMap<>(currentDynamicNames);
        Map<UUID, Long> resetVersions = new HashMap<>();
        pendingResets.forEach((playerId, reset) -> {
            names.putIfAbsent(playerId, reset.value());
            resetVersions.put(playerId, reset.version());
        });
        return new DeliverySnapshot<>(names, resetVersions);
    }

    synchronized void acknowledge(UUID consumerId, Map<UUID, Long> resetVersions) {
        Objects.requireNonNull(consumerId, "consumerId");
        Objects.requireNonNull(resetVersions, "resetVersions");
        resetVersions.forEach((subjectId, deliveredVersion) -> {
            PendingReset<T> pending = pendingResets.get(subjectId);
            if (pending == null || pending.version() != deliveredVersion) {
                return;
            }
            pending.awaiting().remove(consumerId);
            if (pending.awaiting().isEmpty()) {
                pendingResets.remove(subjectId, pending);
            }
        });
    }

    synchronized void clear() {
        pendingResets.clear();
    }

    synchronized boolean hasPendingReset(UUID subjectId) {
        return pendingResets.containsKey(Objects.requireNonNull(subjectId, "subjectId"));
    }

    synchronized Set<UUID> pendingSubjects() {
        return Set.copyOf(pendingResets.keySet());
    }

    synchronized int pendingResetCount() {
        return pendingResets.size();
    }

    record DeliverySnapshot<T>(Map<UUID, T> names, Map<UUID, Long> resetVersions) {
        DeliverySnapshot {
            names = Map.copyOf(names);
            resetVersions = Map.copyOf(resetVersions);
        }
    }

    private record PendingReset<T>(long version, T value, Set<UUID> awaiting) {
        private PendingReset {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(awaiting, "awaiting");
        }
    }
}
