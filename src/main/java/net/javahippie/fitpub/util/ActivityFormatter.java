package net.javahippie.fitpub.util;

import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.Activity;

import java.time.*;

/**
 * Utility class for formatting activity-related data for display.
 */
@Slf4j
public class ActivityFormatter {

    /**
     * Formats an activity type to a human-readable string.
     * Converts UPPERCASE_WITH_UNDERSCORES to Title Case.
     *
     * @param activityType the activity type enum
     * @return human-readable activity type (e.g., "Alpine Ski" instead of "ALPINE_SKI")
     */
    public static String formatActivityType(Activity.ActivityType activityType) {
        if (activityType == null) {
            return "Unknown";
        }

        String name = activityType.name();

        // Split by underscore and capitalize each word
        String[] words = name.split("_");
        StringBuilder formatted = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            String word = words[i].toLowerCase();
            // Capitalize first letter
            formatted.append(Character.toUpperCase(word.charAt(0)));
            formatted.append(word.substring(1));

            // Add space between words (except for the last word)
            if (i < words.length - 1) {
                formatted.append(" ");
            }
        }

        return formatted.toString();
    }

    /**
     * Generates a default activity title based on the time of day and activity type.
     * Format: "[Time of Day] [Activity Type]" (e.g., "Morning Run", "Evening Ride")
     *
     * @param startedAt    the activity start time
     * @param timezone     the timezone ID of the activity
     * @param activityType the activity type
     * @return generated title
     */
    public static String generateActivityTitle(LocalDateTime startedAt, String timezone, Activity.ActivityType activityType) {
        if (startedAt == null || activityType == null) {
            return "Activity";
        }

        LocalDateTime startedAtLocal = getUtcDateTimeInZone(startedAt, timezone);
        String timeOfDay = getTimeOfDay(startedAtLocal.toLocalTime());
        String formattedType = formatActivityType(activityType);

        return timeOfDay + " " + formattedType;
    }



    /**
     * Determines the time of day based on the hour.
     *
     * @param time the local time
     * @return time of day description (Morning, Afternoon, Evening, Night)
     */
    private static String getTimeOfDay(LocalTime time) {
        int hour = time.getHour();

        if (hour >= 5 && hour < 12) {
            return "Morning";
        } else if (hour >= 12 && hour < 17) {
            return "Afternoon";
        } else if (hour >= 17 && hour < 21) {
            return "Evening";
        } else {
            return "Night";
        }
    }

    /**
     * Attempts to convert the given LocalDateTime (which is assumed to be UTC) into a LocalDateTime in the given
     * timezone
     *
     * @param utcDateTime The original date and time (UTC)
     * @param timezone A timezone ID
     * @return The original date and time adjusted to the timezone, if the zone ID could be parsed. The original date
     * and time otherwise
     *
     */
    private static LocalDateTime getUtcDateTimeInZone(LocalDateTime utcDateTime, String timezone) {
        try {
            return utcDateTime.atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(ZoneId.of(timezone))
                    .toLocalDateTime();
        } catch (DateTimeException e) {
            log.warn("Invalid time zone ID: {}", timezone);
            return utcDateTime;
        }
    }
}
