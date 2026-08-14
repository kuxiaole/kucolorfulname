package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.util.DurationParser;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {
    @Test
    void parsesSupportedUnits() {
        assertEquals(Duration.ofMinutes(30), DurationParser.parse("30m").orElseThrow());
        assertEquals(Duration.ofHours(12), DurationParser.parse("12H").orElseThrow());
        assertEquals(Duration.ofDays(28), DurationParser.parse("4w").orElseThrow());
        assertTrue(DurationParser.parse("permanent").isEmpty());
        assertTrue(DurationParser.parse("0d").isEmpty());
    }
}
