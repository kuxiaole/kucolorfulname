package cc.chlchun.kucolorfulname.gui;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositoryGuiComponentTest {
    @Test
    void inactivePreviewUsesExplicitWhiteWithoutItalics() {
        Component preview = RepositoryGui.playerPreviewName(
                "Alice",
                Optional.empty(),
                Component.text("Alice", NamedTextColor.RED, TextDecoration.ITALIC)
        );

        assertEquals(NamedTextColor.WHITE, preview.color());
        assertEquals(TextDecoration.State.FALSE, preview.decoration(TextDecoration.ITALIC));
    }

    @Test
    void activePreviewRecursivelyRemovesItalics() {
        Component preview = RepositoryGui.playerPreviewName(
                "Alice",
                Optional.of("aurora"),
                Component.text("A", NamedTextColor.AQUA, TextDecoration.ITALIC)
                        .append(Component.text("lice", NamedTextColor.LIGHT_PURPLE,
                                TextDecoration.ITALIC))
        );

        assertEquals(TextDecoration.State.FALSE, preview.decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.FALSE,
                preview.children().getFirst().decoration(TextDecoration.ITALIC));
    }

    @Test
    void menuComponentsRemoveItalicsFromHoverText() {
        Component result = RepositoryGui.withoutItalics(Component.text("Menu")
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Details", NamedTextColor.GRAY, TextDecoration.ITALIC))));
        Component hoverText = (Component) result.hoverEvent().value();

        assertEquals(TextDecoration.State.FALSE,
                hoverText.decoration(TextDecoration.ITALIC));
    }
}
