package cc.chlchun.kucolorfulname.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern VALUE = Pattern.compile("([1-9][0-9]*)([smhdw])", Pattern.CASE_INSENSITIVE);

    private DurationParser() { }

    public static Optional<Duration> parse(String input) {
        Matcher matcher = VALUE.matcher(input.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return Optional.empty();
        long amount;
        try { amount = Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException exception) { return Optional.empty(); }
        try {
            return Optional.of(switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                case "w" -> Duration.ofDays(Math.multiplyExact(amount, 7));
                default -> throw new IllegalStateException();
            });
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        if (days > 0) return days + "天 " + hours + "小时";
        if (hours > 0) return hours + "小时 " + minutes + "分钟";
        if (minutes > 0) return minutes + "分钟";
        return seconds + "秒";
    }
}
