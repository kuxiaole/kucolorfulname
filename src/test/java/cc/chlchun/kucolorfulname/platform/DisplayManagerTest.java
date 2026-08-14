package cc.chlchun.kucolorfulname.platform;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayManagerTest {
    @Test
    void identicalComponentsDoNotInvokeNameSetters() {
        Component name = Component.text("Player");
        AtomicReference<Component> displayName = new AtomicReference<>(name);
        AtomicReference<Component> listName = new AtomicReference<>(name);
        AtomicInteger displayWrites = new AtomicInteger();
        AtomicInteger listWrites = new AtomicInteger();
        Player player = player(displayName, listName, displayWrites, listWrites);

        DisplayManager.applyNameIfChanged(player, name, true);

        assertEquals(0, displayWrites.get());
        assertEquals(0, listWrites.get());
    }

    @Test
    void changedComponentsAreWrittenOnceAndTabSettingIsHonored() {
        Component initial = Component.text("Player");
        Component changed = Component.text("Player").color(TextColor.color(0xFF0000));
        AtomicReference<Component> displayName = new AtomicReference<>(initial);
        AtomicReference<Component> listName = new AtomicReference<>(initial);
        AtomicInteger displayWrites = new AtomicInteger();
        AtomicInteger listWrites = new AtomicInteger();
        Player player = player(displayName, listName, displayWrites, listWrites);

        DisplayManager.applyNameIfChanged(player, changed, false);
        DisplayManager.applyNameIfChanged(player, changed, true);
        DisplayManager.applyNameIfChanged(player, changed, true);

        assertEquals(1, displayWrites.get());
        assertEquals(1, listWrites.get());
    }

    @Test
    void disablingTabNamesRestoresThePlainPlayerName() {
        Component colored = Component.text("Player").color(TextColor.color(0xFF0000));
        AtomicReference<Component> displayName = new AtomicReference<>(colored);
        AtomicReference<Component> listName = new AtomicReference<>(colored);
        AtomicInteger displayWrites = new AtomicInteger();
        AtomicInteger listWrites = new AtomicInteger();
        Player player = player(displayName, listName, displayWrites, listWrites);

        DisplayManager.resetTabNameIfChanged(player);
        DisplayManager.resetTabNameIfChanged(player);

        assertEquals(Component.text("Player"), listName.get());
        assertEquals(1, listWrites.get());
    }

    private Player player(
            AtomicReference<Component> displayName,
            AtomicReference<Component> listName,
            AtomicInteger displayWrites,
            AtomicInteger listWrites
    ) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "displayName" -> {
                        if (arguments == null || arguments.length == 0) {
                            yield displayName.get();
                        }
                        displayName.set((Component) arguments[0]);
                        displayWrites.incrementAndGet();
                        yield null;
                    }
                    case "playerListName" -> {
                        if (arguments == null || arguments.length == 0) {
                            yield listName.get();
                        }
                        listName.set((Component) arguments[0]);
                        listWrites.incrementAndGet();
                        yield null;
                    }
                    case "getName" -> "Player";
                    case "toString" -> "DisplayManagerTestPlayer";
                    default -> primitiveDefault(method.getReturnType());
                }
        );
    }

    private Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
