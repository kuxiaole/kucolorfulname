package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.AnimationMode;
import cc.chlchun.kucolorfulname.api.StyleType;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NameRendererTest {
    private final NameRenderer renderer = new NameRenderer();

    @Test
    void rendersSolidStyle() {
        NameStyle style = new NameStyle("ruby", "Ruby", StyleType.SOLID,
                List.of(0xFF0000), Set.of(), "RED_DYE");
        Component rendered = renderer.render("Alex", style);

        assertEquals("Alex", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals(TextColor.color(0xFF0000), rendered.color());
    }

    @Test
    void rendersGradientAcrossCodePoints() {
        NameStyle style = new NameStyle("aurora", "Aurora", StyleType.GRADIENT,
                List.of(0x000000, 0xFFFFFF), Set.of(), "NAME_TAG");
        Component rendered = renderer.render("AB", style);

        assertEquals("AB", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals(TextColor.color(0x000000), rendered.children().get(0).color());
        assertEquals(TextColor.color(0xFFFFFF), rendered.children().get(1).color());
    }

    @Test
    void rendersAnimatedFlowFromTheRequestedSharedTick() {
        NameStyle style = new NameStyle(
                "wave", "Wave", StyleType.ANIMATED,
                List.of(0x00F5FF, 0xFF00E6), Set.of(), "ECHO_SHARD", List.of(),
                AnimationMode.FLOW, 40);

        Component first = renderer.renderAtTick("Alex", style, 0);
        Component sameFrame = renderer.renderAtTick("Alex", style, 0);
        Component later = renderer.renderAtTick("Alex", style, 10);

        assertEquals(first, sameFrame);
        assertEquals("Alex", PlainTextComponentSerializer.plainText().serialize(first));
        assertEquals(4, first.children().size());
        assertNotEquals(first, later);
    }

    @Test
    void rendersEveryAnimationModeDeterministicallyAndAdvancesItsFrame() {
        for (AnimationMode mode : AnimationMode.values()) {
            NameStyle style = new NameStyle(
                    "animated_" + mode.name().toLowerCase(), mode.name(), StyleType.ANIMATED,
                    List.of(0x00F5FF, 0xFF00E6, 0x7A00FF), Set.of(), "ECHO_SHARD",
                    List.of(), mode, 40);

            Component first = renderer.renderAtTick("Alex", style, 0);
            Component sameFrame = renderer.renderAtTick("Alex", style, 0);
            Component later = renderer.renderAtTick("Alex", style,
                    mode == AnimationMode.FLASH ? 20 : 10);

            assertEquals(first, sameFrame, mode + " must be deterministic within one tick");
            assertEquals("Alex", PlainTextComponentSerializer.plainText().serialize(first));
            assertNotEquals(first, later, mode + " must advance during its cycle");
        }
    }
}
