package cc.chlchun.kucolorfulname.render;

import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.AnimationMode;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class NameRenderer {
    private static final int MAX_CACHED_FRAMES = 4096;
    private final ConcurrentMap<FrameKey, Component> frames = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupTick = new AtomicLong(Long.MIN_VALUE);

    public Component render(String name, NameStyle style) {
        return renderAtTick(name, style, animationTick());
    }

    public Component renderAtTick(String name, NameStyle style, long tick) {
        if (style.type() == cc.chlchun.kucolorfulname.api.StyleType.ANIMATED) {
            FrameKey key = new FrameKey(style, name, tick);
            Component rendered = frames.computeIfAbsent(key, ignored -> animated(name, style, tick));
            cleanOldFrames(tick);
            return rendered;
        }
        Component component = switch (style.type()) {
            case SOLID -> Component.text(name).color(TextColor.color(style.colors().getFirst()));
            case GRADIENT -> gradient(name, style.colors());
            case ANIMATED -> throw new IllegalStateException("Animated style bypassed frame renderer");
        };
        return decorate(component, style);
    }

    private Component animated(String name, NameStyle style, long tick) {
        double phase = Math.floorMod(tick, style.animationPeriodTicks())
                / (double) style.animationPeriodTicks();
        Component component = switch (style.animationMode()) {
            case FLOW -> flowingGradient(name, style.colors(), phase);
            case RAINBOW -> rainbow(name, phase);
            case PULSE -> pulsingGradient(name, style.colors(), phase);
            case FLASH -> flashing(name, style.colors(), phase);
        };
        return decorate(component, style);
    }

    private Component decorate(Component component, NameStyle style) {
        for (TextDecoration decoration : style.decorations()) {
            component = component.decorate(decoration);
        }
        return component;
    }

    private Component flowingGradient(String value, List<Integer> colors, double phase) {
        int[] codePoints = value.codePoints().toArray();
        Component result = Component.empty();
        for (int index = 0; index < codePoints.length; index++) {
            double position = codePoints.length == 0 ? phase
                    : (phase + (double) index / Math.max(1, codePoints.length)) % 1.0;
            result = result.append(coloredCodePoint(codePoints[index], cyclicColor(colors, position)));
        }
        return result;
    }

    private Component rainbow(String value, double phase) {
        int[] codePoints = value.codePoints().toArray();
        Component result = Component.empty();
        for (int index = 0; index < codePoints.length; index++) {
            float hue = (float) ((phase + (double) index / Math.max(1, codePoints.length)) % 1.0);
            int color = Color.HSBtoRGB(hue, 0.82F, 1.0F) & 0xFFFFFF;
            result = result.append(coloredCodePoint(codePoints[index], color));
        }
        return result;
    }

    private Component pulsingGradient(String value, List<Integer> colors, double phase) {
        double brightness = 0.58 + 0.42 * ((Math.sin(phase * Math.PI * 2.0) + 1.0) / 2.0);
        return gradient(value, colors.stream().map(color -> scale(color, brightness)).toList());
    }

    private Component flashing(String value, List<Integer> colors, double phase) {
        int index = Math.min((int) (phase * colors.size()), colors.size() - 1);
        return Component.text(value).color(TextColor.color(colors.get(index)));
    }

    private Component gradient(String value, List<Integer> colors) {
        int[] codePoints = value.codePoints().toArray();
        if (codePoints.length == 0) {
            return Component.empty();
        }
        Component result = Component.empty();
        for (int index = 0; index < codePoints.length; index++) {
            double position = codePoints.length == 1 ? 0 : (double) index / (codePoints.length - 1);
            double scaled = position * (colors.size() - 1);
            int segment = Math.min((int) scaled, colors.size() - 2);
            double localPosition = scaled - segment;
            int color = interpolate(colors.get(segment), colors.get(segment + 1), localPosition);
            result = result.append(Component.text(new String(Character.toChars(codePoints[index])))
                    .color(TextColor.color(color)));
        }
        return result;
    }

    private int interpolate(int first, int second, double ratio) {
        int red = interpolateChannel(first, second, 16, ratio);
        int green = interpolateChannel(first, second, 8, ratio);
        int blue = interpolateChannel(first, second, 0, ratio);
        return (red << 16) | (green << 8) | blue;
    }

    private int interpolateChannel(int first, int second, int shift, double ratio) {
        int start = (first >> shift) & 0xFF;
        int end = (second >> shift) & 0xFF;
        return (int) Math.round(start + (end - start) * ratio);
    }

    private int cyclicColor(List<Integer> colors, double position) {
        double scaled = position * colors.size();
        int first = Math.min((int) scaled, colors.size() - 1);
        int second = (first + 1) % colors.size();
        return interpolate(colors.get(first), colors.get(second), scaled - first);
    }

    private Component coloredCodePoint(int codePoint, int color) {
        return Component.text(new String(Character.toChars(codePoint))).color(TextColor.color(color));
    }

    private int scale(int color, double brightness) {
        int red = (int) Math.round(((color >> 16) & 0xFF) * brightness);
        int green = (int) Math.round(((color >> 8) & 0xFF) * brightness);
        int blue = (int) Math.round((color & 0xFF) * brightness);
        return (red << 16) | (green << 8) | blue;
    }

    private long animationTick() {
        return System.currentTimeMillis() / 50L;
    }

    private void cleanOldFrames(long tick) {
        if (frames.size() <= MAX_CACHED_FRAMES || lastCleanupTick.getAndSet(tick) == tick) {
            return;
        }
        frames.keySet().removeIf(key -> key.tick() < tick - 2L);
        if (frames.size() > MAX_CACHED_FRAMES) {
            frames.clear();
        }
    }

    int cachedFrameCount() {
        return frames.size();
    }

    private record FrameKey(NameStyle style, String value, long tick) { }
}
