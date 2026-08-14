package cc.chlchun.kucolorfulname.service;

import cc.chlchun.kucolorfulname.config.PermissionGroup;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStyleControllerTest {
    private static final PermissionGroup VIP = new PermissionGroup(
            "vip", "example.vip", 100, Set.of("ruby"), "ruby");
    private static final PermissionGroup RECONFIGURED_VIP = new PermissionGroup(
            "vip", "example.vip", 100, Set.of("ruby", "aurora"), "aurora");

    @Test
    void manualSelectionInvalidatesAnOlderAutomaticCheck() {
        DefaultStyleController controller = new DefaultStyleController();
        UUID playerId = UUID.randomUUID();
        long automaticRevision = controller.beginCheck(playerId).revision();

        long manualRevision = controller.beginManualSelection(playerId);

        assertNotEquals(automaticRevision, manualRevision);
        assertTrue(controller.current(playerId, automaticRevision).isEmpty());
        assertFalse(controller.transition(playerId, automaticRevision,
                state -> state.evaluated(VIP, "ruby", false)));
        assertTrue(controller.current(playerId, manualRevision)
                .orElseThrow().manualOperationPending());

        assertTrue(controller.prepareManualSelection(playerId, manualRevision, VIP));
        controller.finishManualSelection(playerId, manualRevision);
        DefaultStyleController.State completed = controller.current(playerId, manualRevision)
                .orElseThrow();
        assertFalse(completed.manualOperationPending());
        assertFalse(completed.manuallyDisabled());
    }

    @Test
    void manualOffSurvivesOrdinaryRefreshesUntilTheGroupChanges() {
        DefaultStyleController controller = new DefaultStyleController();
        UUID playerId = UUID.randomUUID();
        long revision = controller.beginCheck(playerId).revision();
        assertTrue(controller.transition(playerId, revision,
                state -> state.evaluated(VIP, "ruby", false)));

        long offRevision = controller.beginManualDeactivation(playerId);
        controller.finishManualDeactivation(playerId, offRevision);

        DefaultStyleController.State disabled = controller.current(playerId, offRevision)
                .orElseThrow();
        assertEquals(VIP, disabled.group());
        assertTrue(disabled.manuallyDisabled());
        assertFalse(disabled.manualOperationPending());

        controller.invalidateDefaultChecks();
        DefaultStyleController.State refreshed = controller.beginCheck(playerId);
        assertNotEquals(offRevision, refreshed.revision());
        assertEquals(VIP, refreshed.group());
        assertTrue(refreshed.manuallyDisabled());
        assertFalse(refreshed.groupChanged(VIP));
    }

    @Test
    void manualOffInvalidatesAQueuedAutomaticWrite() {
        DefaultStyleController controller = new DefaultStyleController();
        UUID playerId = UUID.randomUUID();
        long automaticRevision = controller.beginCheck(playerId).revision();

        long offRevision = controller.beginManualDeactivation(playerId);

        assertNotEquals(automaticRevision, offRevision);
        assertTrue(controller.current(playerId, automaticRevision).isEmpty());
        assertFalse(controller.transition(playerId, automaticRevision,
                state -> state.evaluated(VIP, "ruby", false)));

        controller.finishManualDeactivation(playerId, offRevision);
        assertTrue(controller.current(playerId, offRevision)
                .orElseThrow().manuallyDisabled());
    }

    @Test
    void changedDefaultReplacesAnAutomaticSelectionWithoutChangingGroupName() {
        DefaultStyleController controller = new DefaultStyleController();
        UUID playerId = UUID.randomUUID();
        long revision = controller.beginCheck(playerId).revision();
        assertTrue(controller.transition(playerId, revision,
                state -> state.evaluated(VIP, "ruby", false)));

        DefaultStyleController.State state = controller.current(playerId, revision).orElseThrow();
        assertFalse(state.groupChanged(RECONFIGURED_VIP));
        assertTrue(state.shouldReplaceAutomaticSelection(
                "ruby", RECONFIGURED_VIP.defaultStyle()));
        assertFalse(state.shouldReplaceAutomaticSelection(
                "ruby", VIP.defaultStyle()));
    }

    @Test
    void reloadAndUnloadInvalidatePendingAutomaticChecks() {
        DefaultStyleController controller = new DefaultStyleController();
        UUID playerId = UUID.randomUUID();
        long beforeReload = controller.beginCheck(playerId).revision();

        controller.invalidateDefaultChecks();
        long afterReload = controller.beginCheck(playerId).revision();
        assertNotEquals(beforeReload, afterReload);
        assertTrue(controller.current(playerId, beforeReload).isEmpty());

        controller.unload(playerId);
        assertEquals(0, controller.size());
        long afterJoin = controller.beginCheck(playerId).revision();
        assertNotEquals(afterReload, afterJoin);
    }

    @Test
    void reloadDoesNotStrandAManualOperation() {
        DefaultStyleController controller = new DefaultStyleController();
        UUID playerId = UUID.randomUUID();
        long manualRevision = controller.beginManualSelection(playerId);

        controller.invalidateDefaultChecks();
        assertTrue(controller.prepareManualSelection(playerId, manualRevision, VIP));
        controller.finishManualSelection(playerId, manualRevision);

        DefaultStyleController.State state = controller.current(playerId, manualRevision)
                .orElseThrow();
        assertFalse(state.manualOperationPending());
    }
}
