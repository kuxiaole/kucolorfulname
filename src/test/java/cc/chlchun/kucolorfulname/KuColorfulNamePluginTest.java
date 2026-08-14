package cc.chlchun.kucolorfulname;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KuColorfulNamePluginTest {
    @Test
    void periodicWorkIsSpreadAcrossConfiguredPeriod() {
        assertEquals(60L, KuColorfulNamePlugin.periodicBatchDelayTicks(10, 600L));
        assertEquals(1L, KuColorfulNamePlugin.periodicBatchDelayTicks(1_000, 600L));
        assertEquals(1L, KuColorfulNamePlugin.periodicBatchDelayTicks(0, 600L));
    }
}
