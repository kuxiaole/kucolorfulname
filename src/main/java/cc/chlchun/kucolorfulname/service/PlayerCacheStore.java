package cc.chlchun.kucolorfulname.service;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

final class PlayerCacheStore {
    private static final State EMPTY = new State(List.of(), null, null, false);
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    Session open(UUID playerId) {
        return sessions.computeIfAbsent(playerId, ignored -> new Session(playerId));
    }

    Optional<Session> current(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    boolean isCurrent(Session session) {
        return sessions.get(session.playerId()) == session;
    }

    boolean update(Session session, UnaryOperator<State> update) {
        if (!isCurrent(session)) {
            return false;
        }
        while (isCurrent(session)) {
            State existing = session.state().get();
            State replacement = update.apply(existing);
            if (session.state().compareAndSet(existing, replacement)) {
                return true;
            }
        }
        return false;
    }

    boolean replaceDataIfChanged(
            Session session,
            List<NameGrant> updatedGrants,
            String updatedActiveStyleKey
    ) {
        if (!isCurrent(session)) {
            return false;
        }
        while (isCurrent(session)) {
            State existing = session.state().get();
            if (existing.loaded()
                    && existing.grants().equals(updatedGrants)
                    && java.util.Objects.equals(
                            existing.activeStyleKey(), updatedActiveStyleKey)) {
                return false;
            }
            if (session.state().compareAndSet(
                    existing, existing.withData(updatedGrants, updatedActiveStyleKey))) {
                return true;
            }
        }
        return false;
    }

    boolean publishPlaceholder(Session session, State source, PlaceholderSnapshot snapshot) {
        if (!isCurrent(session)) {
            return false;
        }
        State replacement = new State(
                source.grants(), source.activeStyleKey(), snapshot, source.loaded());
        return session.state().compareAndSet(source, replacement);
    }

    State state(UUID playerId) {
        Session session = sessions.get(playerId);
        return session == null ? EMPTY : session.state().get();
    }

    void unload(UUID playerId) {
        sessions.remove(playerId);
    }

    void clear() {
        sessions.clear();
    }

    List<Session> sessions() {
        return List.copyOf(sessions.values());
    }

    int sessionCount() {
        return sessions.size();
    }

    static final class Session {
        private final UUID playerId;
        private final AtomicReference<State> state = new AtomicReference<>(EMPTY);

        private Session(UUID playerId) {
            this.playerId = playerId;
        }

        UUID playerId() {
            return playerId;
        }

        AtomicReference<State> state() {
            return state;
        }
    }

    record State(
            List<NameGrant> grants,
            String activeStyleKey,
            PlaceholderSnapshot placeholder,
            boolean loaded
    ) {
        State {
            grants = List.copyOf(grants);
        }

        State withData(List<NameGrant> updatedGrants, String updatedActiveStyleKey) {
            return new State(updatedGrants, updatedActiveStyleKey, null, true);
        }

        State withGrants(List<NameGrant> updatedGrants) {
            return new State(updatedGrants, activeStyleKey, null, loaded);
        }

        State withActiveStyle(String updatedActiveStyleKey) {
            return new State(grants, updatedActiveStyleKey, null, loaded);
        }

    }
}
