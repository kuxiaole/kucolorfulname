package cc.chlchun.kucolorfulname.platform;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationDeliveryStateTest {
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void retainsResetUntilEveryConsumerAcknowledgesIt() {
        AnimationDeliveryState<String> state = new AnimationDeliveryState<>();
        state.registerConsumer(SUBJECT);
        state.registerConsumer(VIEWER);
        state.retainReset(SUBJECT, "plain", Set.of(SUBJECT, VIEWER));

        var delivery = state.snapshot(Map.of());
        state.acknowledge(SUBJECT, delivery.resetVersions());

        assertEquals(Map.of(SUBJECT, "plain"), state.snapshot(Map.of()).names());

        state.acknowledge(VIEWER, delivery.resetVersions());

        assertEquals(Map.of(), state.snapshot(Map.of()).names());
    }

    @Test
    void lateConsumerMustAlsoObservePendingReset() {
        AnimationDeliveryState<String> state = new AnimationDeliveryState<>();
        state.registerConsumer(SUBJECT);
        state.retainReset(SUBJECT, "plain", Set.of(SUBJECT));
        var firstDelivery = state.snapshot(Map.of());

        state.registerConsumer(VIEWER);
        state.acknowledge(SUBJECT, firstDelivery.resetVersions());

        var lateDelivery = state.snapshot(Map.of());
        assertEquals(Map.of(SUBJECT, "plain"), lateDelivery.names());

        state.acknowledge(VIEWER, lateDelivery.resetVersions());
        assertEquals(0, state.pendingResetCount());
    }

    @Test
    void staleAcknowledgementCannotRemoveNewerReset() {
        AnimationDeliveryState<String> state = new AnimationDeliveryState<>();
        state.registerConsumer(SUBJECT);
        state.retainReset(SUBJECT, "first", Set.of(SUBJECT));
        var firstDelivery = state.snapshot(Map.of());

        state.retainReset(SUBJECT, "second", Set.of(SUBJECT));
        state.acknowledge(SUBJECT, firstDelivery.resetVersions());

        var secondDelivery = state.snapshot(Map.of());
        assertEquals(Map.of(SUBJECT, "second"), secondDelivery.names());

        state.acknowledge(SUBJECT, secondDelivery.resetVersions());
        assertEquals(0, state.pendingResetCount());
    }

    @Test
    void replacingPendingResetPublishesOnlyTheNewestStaticName() {
        AnimationDeliveryState<String> state = new AnimationDeliveryState<>();
        state.registerConsumer(SUBJECT);
        state.retainReset(SUBJECT, "red", Set.of(SUBJECT));

        state.retainReset(SUBJECT, "blue", Set.of(SUBJECT));

        assertEquals(Map.of(SUBJECT, "blue"), state.snapshot(Map.of()).names());
        assertEquals(true, state.hasPendingReset(SUBJECT));
    }

    @Test
    void dynamicStateCancelsAnOlderStaticReset() {
        AnimationDeliveryState<String> state = new AnimationDeliveryState<>();
        state.retainReset(SUBJECT, "plain", Set.of(SUBJECT));

        state.cancelResetsFor(Set.of(SUBJECT));

        assertEquals(Map.of(SUBJECT, "animated"),
                state.snapshot(Map.of(SUBJECT, "animated")).names());
        assertEquals(0, state.pendingResetCount());
    }

    @Test
    void retiredConsumerNoLongerBlocksResetCleanup() {
        AnimationDeliveryState<String> state = new AnimationDeliveryState<>();
        state.registerConsumer(SUBJECT);
        state.registerConsumer(VIEWER);
        state.retainReset(SUBJECT, "plain", Set.of(SUBJECT, VIEWER));
        var delivery = state.snapshot(Map.of());

        state.unregisterConsumer(VIEWER);
        state.acknowledge(SUBJECT, delivery.resetVersions());

        assertEquals(0, state.pendingResetCount());
    }

    @Test
    void offlineSubjectDoesNotLeaveAResetBehind() {
        AnimationDeliveryState<String> state = new AnimationDeliveryState<>();
        state.retainReset(SUBJECT, "plain", Set.of(SUBJECT));

        state.retainOnline(Set.of());

        assertEquals(0, state.pendingResetCount());
    }

    @Test
    void snapshotCombinesCurrentAnimationsAndPendingResets() {
        AnimationDeliveryState<String> state = new AnimationDeliveryState<>();
        state.retainReset(SUBJECT, "plain", Set.of(SUBJECT));

        var snapshot = state.snapshot(Map.of(VIEWER, "animated"));

        assertEquals(Map.of(SUBJECT, "plain", VIEWER, "animated"), snapshot.names());
        assertEquals(Set.of(SUBJECT), snapshot.resetVersions().keySet());
    }
}
