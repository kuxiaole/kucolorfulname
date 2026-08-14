package cc.chlchun.kucolorfulname.service;

import cc.chlchun.kucolorfulname.config.PermissionGroup;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/** Coordinates automatic defaults with manual player choices and configuration reloads. */
final class DefaultStyleController {
    private final AtomicLong revisions = new AtomicLong();
    private final ConcurrentMap<UUID, State> states = new ConcurrentHashMap<>();

    State beginCheck(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return states.computeIfAbsent(playerId,
                ignored -> new State(nextRevision(), null, null, false, false));
    }

    Optional<State> current(UUID playerId, long revision) {
        State state = states.get(playerId);
        return state != null && state.revision() == revision
                ? Optional.of(state)
                : Optional.empty();
    }

    boolean transition(UUID playerId, long revision, UnaryOperator<State> transition) {
        Objects.requireNonNull(transition, "transition");
        AtomicBoolean updated = new AtomicBoolean();
        states.computeIfPresent(playerId, (ignored, current) -> {
            if (current.revision() != revision) {
                return current;
            }
            State replacement = Objects.requireNonNull(transition.apply(current), "replacement");
            if (replacement.revision() != revision) {
                throw new IllegalArgumentException("A state transition cannot change its revision");
            }
            updated.set(true);
            return replacement;
        });
        return updated.get();
    }

    long beginManualSelection(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        AtomicLong selectedRevision = new AtomicLong();
        states.compute(playerId, (ignored, current) -> {
            long revision = nextRevision();
            selectedRevision.set(revision);
            return new State(
                    revision,
                    current == null ? null : current.group(),
                    current == null ? null : current.automaticStyle(),
                    current != null && current.manuallyDisabled(),
                    true
            );
        });
        return selectedRevision.get();
    }

    boolean prepareManualSelection(UUID playerId, long revision, PermissionGroup group) {
        Objects.requireNonNull(group, "group");
        return transition(playerId, revision,
                current -> new State(revision, group, null, false, true));
    }

    void cancelManualSelection(UUID playerId, long revision) {
        transition(playerId, revision, current -> new State(
                revision,
                current.group(),
                current.automaticStyle(),
                current.manuallyDisabled(),
                false
        ));
    }

    void finishManualSelection(UUID playerId, long revision) {
        transition(playerId, revision, current -> new State(
                revision, current.group(), null, false, false));
    }

    long beginManualDeactivation(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        AtomicLong selectedRevision = new AtomicLong();
        states.compute(playerId, (ignored, current) -> {
            long revision = nextRevision();
            selectedRevision.set(revision);
            PermissionGroup group = current == null ? null : current.group();
            return new State(revision, group, null, true, true);
        });
        return selectedRevision.get();
    }

    void finishManualDeactivation(UUID playerId, long revision) {
        transition(playerId, revision, current -> new State(
                revision, current.group(), null, true, false));
    }

    void invalidateDefaultChecks() {
        states.replaceAll((ignored, current) -> current.manualOperationPending()
                ? current
                : current.withRevision(nextRevision()));
    }

    void unload(UUID playerId) {
        states.remove(playerId);
    }

    void clear() {
        states.clear();
    }

    int size() {
        return states.size();
    }

    private long nextRevision() {
        return revisions.incrementAndGet();
    }

    record State(
            long revision,
            PermissionGroup group,
            String automaticStyle,
            boolean manuallyDisabled,
            boolean manualOperationPending
    ) {
        State {
            if (automaticStyle != null && automaticStyle.isBlank()) {
                automaticStyle = null;
            }
        }

        State evaluated(
                PermissionGroup evaluatedGroup,
                String evaluatedAutomaticStyle,
                boolean disabled
        ) {
            return new State(
                    revision,
                    Objects.requireNonNull(evaluatedGroup, "evaluatedGroup"),
                    evaluatedAutomaticStyle,
                    disabled,
                    false
            );
        }

        boolean groupChanged(PermissionGroup evaluatedGroup) {
            Objects.requireNonNull(evaluatedGroup, "evaluatedGroup");
            return group == null || !group.name().equals(evaluatedGroup.name());
        }

        boolean shouldReplaceAutomaticSelection(String activeStyle, String configuredDefaultStyle) {
            // The configured default can change while the effective group name stays
            // the same, so this decision must not depend on groupChanged().
            return automaticStyle != null
                    && automaticStyle.equals(activeStyle)
                    && !automaticStyle.equals(configuredDefaultStyle);
        }

        State withRevision(long updatedRevision) {
            return new State(
                    updatedRevision,
                    group,
                    automaticStyle,
                    manuallyDisabled,
                    manualOperationPending
            );
        }
    }
}
