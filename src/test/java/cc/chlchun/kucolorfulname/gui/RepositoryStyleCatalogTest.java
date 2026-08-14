package cc.chlchun.kucolorfulname.gui;

import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.StyleType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositoryStyleCatalogTest {
    private final NameStyle ruby = style("ruby", 0xFF0000);
    private final NameStyle amber = style("amber", 0xFFAA00);
    private final NameStyle mint = style("mint", 0x00FF99);

    @Test
    void playerCatalogContainsOnlyUsableStylesAndMovesActiveStyleFirst() {
        List<NameStyle> styles = RepositoryStyleCatalog.playerStyles(
                List.of(ruby, amber, mint),
                key -> key.equals("ruby") || key.equals("mint"),
                Optional.of("mint")
        );

        assertEquals(List.of("mint", "ruby"), styles.stream().map(NameStyle::key).toList());
    }

    @Test
    void playerCatalogPreservesConfigurationOrderWithoutUsableActiveStyle() {
        List<NameStyle> styles = RepositoryStyleCatalog.playerStyles(
                List.of(ruby, amber, mint),
                key -> !key.equals("amber"),
                Optional.of("amber")
        );

        assertEquals(List.of("ruby", "mint"), styles.stream().map(NameStyle::key).toList());
    }

    @Test
    void lockedPlayerCatalogContainsOnlyUnusableStylesInConfigurationOrder() {
        List<NameStyle> styles = RepositoryStyleCatalog.playerLockedStyles(
                List.of(ruby, amber, mint),
                key -> key.equals("amber")
        );

        assertEquals(List.of("ruby", "mint"), styles.stream().map(NameStyle::key).toList());
    }

    @Test
    void lockedPlayerCatalogIsEmptyWhenEveryStyleIsUnlocked() {
        List<NameStyle> styles = RepositoryStyleCatalog.playerLockedStyles(
                List.of(ruby, amber, mint),
                key -> true
        );

        assertEquals(List.of(), styles);
    }

    @Test
    void lockedPlayerCatalogContainsEveryStyleWhenNoneAreUnlocked() {
        List<NameStyle> styles = RepositoryStyleCatalog.playerLockedStyles(
                List.of(ruby, amber, mint),
                key -> false
        );

        assertEquals(List.of("ruby", "amber", "mint"),
                styles.stream().map(NameStyle::key).toList());
    }

    @Test
    void adminCatalogAlwaysContainsEveryConfiguredStyleInOrder() {
        List<NameStyle> styles = RepositoryStyleCatalog.adminStyles(List.of(ruby, amber, mint));

        assertEquals(List.of("ruby", "amber", "mint"),
                styles.stream().map(NameStyle::key).toList());
    }

    private NameStyle style(String key, int color) {
        return new NameStyle(key, key, StyleType.SOLID, List.of(color), Set.of(), "NAME_TAG");
    }
}
