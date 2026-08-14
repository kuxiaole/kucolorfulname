package cc.chlchun.kucolorfulname.platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Minecraft 26.2 bridge that sends one UPDATE_DISPLAY_NAME packet per viewer and frame. */
final class NmsTabListBridge {
    private final Method getHandle;
    private final Method asVanilla;
    private final Constructor<?> entryConstructor;
    private final Constructor<?> packetConstructor;
    private final Field listName;
    private final Field connection;
    private final Method send;
    private final EnumSet<?> actions;

    private NmsTabListBridge(
            Method getHandle,
            Method asVanilla,
            Constructor<?> entryConstructor,
            Constructor<?> packetConstructor,
            Field listName,
            Field connection,
            Method send,
            EnumSet<?> actions
    ) {
        this.getHandle = getHandle;
        this.asVanilla = asVanilla;
        this.entryConstructor = entryConstructor;
        this.packetConstructor = packetConstructor;
        this.listName = listName;
        this.connection = connection;
        this.send = send;
        this.actions = actions;
    }

    static Optional<NmsTabListBridge> create(String minecraftVersion) {
        if (!"26.2".equals(minecraftVersion)) {
            return Optional.empty();
        }
        try {
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> nmsComponent = Class.forName("net.minecraft.network.chat.Component");
            Class<?> gameProfile = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> gameType = Class.forName("net.minecraft.world.level.GameType");
            Class<?> chatSessionData = Class.forName(
                    "net.minecraft.network.chat.RemoteChatSession$Data");
            Class<?> entry = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
            Class<?> packet = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            Class<?> action = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            Class<?> paperAdventure = Class.forName(
                    "io.papermc.paper.adventure.PaperAdventure");
            Class<?> listener = Class.forName(
                    "net.minecraft.server.network.ServerCommonPacketListenerImpl");
            Class<?> packetInterface = Class.forName("net.minecraft.network.protocol.Packet");

            Method getHandle = craftPlayer.getMethod("getHandle");
            Method asVanilla = paperAdventure.getMethod("asVanilla", Component.class);
            Constructor<?> entryConstructor = entry.getConstructor(
                    UUID.class,
                    gameProfile,
                    boolean.class,
                    int.class,
                    gameType,
                    nmsComponent,
                    boolean.class,
                    int.class,
                    chatSessionData
            );
            Constructor<?> packetConstructor = packet.getConstructor(EnumSet.class, List.class);
            Field listName = serverPlayer.getField("listName");
            Field connection = serverPlayer.getField("connection");
            Method send = listener.getMethod("send", packetInterface);

            @SuppressWarnings({"rawtypes", "unchecked"})
            Object updateDisplayName = Enum.valueOf(
                    (Class<? extends Enum>) action.asSubclass(Enum.class), "UPDATE_DISPLAY_NAME");
            @SuppressWarnings({"rawtypes", "unchecked"})
            EnumSet<?> actions = EnumSet.of((Enum) updateDisplayName);
            return Optional.of(new NmsTabListBridge(
                    getHandle,
                    asVanilla,
                    entryConstructor,
                    packetConstructor,
                    listName,
                    connection,
                    send,
                    actions
            ));
        } catch (ReflectiveOperationException | LinkageError exception) {
            return Optional.empty();
        }
    }

    PreparedBatch prepare(Map<UUID, Component> names) {
        Objects.requireNonNull(names, "names");
        try {
            List<Object> entries = new ArrayList<>(names.size());
            Map<UUID, Object> vanillaNames = new LinkedHashMap<>();
            for (Map.Entry<UUID, Component> name : names.entrySet()) {
                Object vanilla = asVanilla.invoke(null, name.getValue());
                vanillaNames.put(name.getKey(), vanilla);
                entries.add(entryConstructor.newInstance(
                        name.getKey(), null, false, 0, null, vanilla, false, 0, null));
            }
            Object packet = packetConstructor.newInstance(actions.clone(), entries);
            return new PreparedBatch(packet, Map.copyOf(vanillaNames));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot build the Minecraft 26.2 tab-list batch", exception);
        }
    }

    void applyAndSend(Player viewer, Component ownName, PreparedBatch batch) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(batch, "batch");
        try {
            if (ownName != null && !ownName.equals(viewer.displayName())) {
                viewer.displayName(ownName);
            }
            Object handle = getHandle.invoke(viewer);
            Object ownVanillaName = batch.vanillaNames().get(viewer.getUniqueId());
            if (ownVanillaName != null
                    && !ownVanillaName.equals(listName.get(handle))) {
                listName.set(handle, ownVanillaName);
            }
            Object playerConnection = connection.get(handle);
            if (playerConnection != null) {
                send.invoke(playerConnection, batch.packet());
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot send the Minecraft 26.2 tab-list batch", exception);
        }
    }

    record PreparedBatch(Object packet, Map<UUID, Object> vanillaNames) {
        PreparedBatch {
            Objects.requireNonNull(packet, "packet");
            vanillaNames = Map.copyOf(vanillaNames);
        }
    }
}
