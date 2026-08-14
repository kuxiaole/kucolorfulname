package cc.chlchun.kucolorfulname.hook;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.api.PlaceholderStyleState;
import cc.chlchun.kucolorfulname.api.StyleUnlockSource;
import cc.chlchun.kucolorfulname.util.DurationParser;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;

/** Pure, thread-safe resolver for the PlaceholderAPI cache. */
public final class PlaceholderResolver {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String[] STYLE_PREFIXES = {
            "can_use_", "has_grant_", "permission_", "grant_expires_", "grant_remaining_"
    };

    private PlaceholderResolver() { }

    public static @Nullable String resolve(
            Optional<PlaceholderSnapshot> cached,
            String fallbackName,
            String parameters,
            Instant now
    ) {
        return resolve(cached, fallbackName, parameters, now, null);
    }

    public static @Nullable String resolve(
            Optional<PlaceholderSnapshot> cached,
            String fallbackName,
            String parameters,
            Instant now,
            @Nullable Component renderedNameOverride
    ) {
        Objects.requireNonNull(cached, "cached");
        Objects.requireNonNull(fallbackName, "fallbackName");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(now, "now");
        String parameter = parameters.trim().toLowerCase(Locale.ROOT);
        if (cached.isEmpty()) {
            return unloaded(parameter, fallbackName);
        }

        PlaceholderSnapshot snapshot = cached.get();
        Optional<String> activeStyle = snapshot.activeStyleAt(now);
        Optional<StyleUnlockSource> unlockSource = snapshot.activeUnlockSourceAt(now);
        Optional<NameGrant> activeGrant = snapshot.activeGrantAt(now);
        Component renderedName = renderedNameOverride == null
                ? snapshot.renderedNameAt(now) : renderedNameOverride;
        return switch (parameter) {
            case "name" -> LEGACY.serialize(renderedName);
            case "name_raw" -> PLAIN.serialize(renderedName);
            case "name_mm" -> MINI_MESSAGE.serialize(renderedName);
            case "style" -> activeStyle.orElse("");
            case "selected_style" -> snapshot.selectedStyleKey().orElse("");
            case "status" -> snapshot.statusAt(now).name().toLowerCase(Locale.ROOT);
            case "group" -> snapshot.group();
            case "loaded" -> "true";
            case "expires" -> activeGrant.map(grant -> grant.expiresAt().toString())
                    .orElseGet(() -> includesPermission(unlockSource) ? "permission" : "");
            case "remaining" -> activeGrant.map(grant -> remaining(grant.expiresAt(), now))
                    .orElseGet(() -> includesPermission(unlockSource) ? "permission" : "");
            case "permission_unlocked" -> Boolean.toString(includesPermission(unlockSource));
            case "unlock_source" -> unlockSource
                    .map(value -> value.name().toLowerCase(Locale.ROOT)).orElse("");
            case "owned" -> Integer.toString(snapshot.ownedGrantCountAt(now));
            default -> stylePlaceholder(snapshot, parameter, now);
        };
    }

    private static String unloaded(String parameter, String fallbackName) {
        Component fallback = Component.text(fallbackName);
        return switch (parameter) {
            case "name" -> LEGACY.serialize(fallback);
            case "name_raw" -> fallbackName;
            case "name_mm" -> MINI_MESSAGE.serialize(fallback);
            case "status" -> "loading";
            case "loaded" -> "false";
            case "style", "selected_style", "group", "expires", "remaining", "unlock_source" -> "";
            case "permission_unlocked" -> "false";
            case "owned" -> "0";
            default -> defaultStylePlaceholder(parameter);
        };
    }

    private static @Nullable String stylePlaceholder(
            PlaceholderSnapshot snapshot,
            String parameter,
            Instant now
    ) {
        String key = extractStyleKey(parameter);
        if (key.isEmpty()) {
            return null;
        }
        Optional<PlaceholderStyleState> state = snapshot.style(key);
        if (state.isEmpty()) {
            return defaultStylePlaceholder(parameter);
        }
        PlaceholderStyleState value = state.get();
        if (parameter.equals("can_use_" + key)) {
            return Boolean.toString(value.availableAt(now));
        }
        if (parameter.equals("has_grant_" + key)) {
            return Boolean.toString(value.hasGrantAt(now));
        }
        if (parameter.equals("permission_" + key)) {
            return Boolean.toString(value.permissionUnlocked());
        }
        Optional<Instant> validExpiry = value.grantExpiresAt().filter(expiry -> expiry.isAfter(now));
        if (parameter.equals("grant_expires_" + key)) {
            return validExpiry.map(Instant::toString)
                    .orElseGet(() -> value.permissionUnlocked() ? "permission" : "");
        }
        if (parameter.equals("grant_remaining_" + key)) {
            return validExpiry.map(expiry -> remaining(expiry, now))
                    .orElseGet(() -> value.permissionUnlocked() ? "permission" : "");
        }
        return null;
    }

    private static @Nullable String defaultStylePlaceholder(String parameter) {
        String key = extractStyleKey(parameter);
        if (key.isEmpty()) {
            return null;
        }
        if (parameter.startsWith("can_use_") || parameter.startsWith("has_grant_")
                || parameter.startsWith("permission_")) {
            return "false";
        }
        if (parameter.startsWith("grant_expires_") || parameter.startsWith("grant_remaining_")) {
            return "";
        }
        return null;
    }

    private static String extractStyleKey(String parameter) {
        for (String prefix : STYLE_PREFIXES) {
            if (parameter.startsWith(prefix) && parameter.length() > prefix.length()) {
                return parameter.substring(prefix.length());
            }
        }
        return "";
    }

    private static boolean includesPermission(Optional<StyleUnlockSource> source) {
        return source.map(StyleUnlockSource::includesPermission).orElse(false);
    }

    private static String remaining(Instant expiresAt, Instant now) {
        return DurationParser.format(Duration.between(now, expiresAt));
    }
}
