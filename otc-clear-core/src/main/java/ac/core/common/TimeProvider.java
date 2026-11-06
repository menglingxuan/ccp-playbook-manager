package ac.core.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Time source for the application. Timestamps are stored in UTC and displayed in
 * {@link #DISPLAY_ZONE}.
 */
public final class TimeProvider {

    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Clock clock;

    public TimeProvider(Clock clock) {
        this.clock = clock;
    }

    /** Current time in UTC, truncated to milliseconds for stable storage. */
    public LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
    }

    public Instant now() {
        return clock.instant();
    }

    public Clock clock() {
        return clock;
    }

    /** Formats a stored UTC timestamp for the UI. */
    public static String display(LocalDateTime utcTimestamp) {
        if (utcTimestamp == null) {
            return "";
        }
        return utcTimestamp.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(DISPLAY_ZONE)
                .format(DISPLAY_FORMAT);
    }

    public static LocalDateTime fromInstant(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).withNano(0);
    }
}
