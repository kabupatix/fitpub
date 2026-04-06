package net.javahippie.fitpub.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.util.ActivityFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for asynchronous post-processing of activities after upload.
 * Coordinates expensive operations (Personal Records, Weather, Heatmap)
 * to avoid blocking the upload response.
 *
 * Federation is NOT triggered here — it is deferred until the user
 * finalizes the activity via the metadata update (PUT) endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityPostProcessingService {

    private final PersonalRecordService personalRecordService;
    private final WeatherService weatherService;
    private final HeatmapGridService heatmapGridService;
    private final FederationService federationService;
    private final ActivityImageService activityImageService;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;

    @Value("${fitpub.base-url}")
    private String baseUrl;

    /**
     * Orchestrates async post-processing operations for an uploaded activity.
     * Called after activity is saved and immediately visible to the user.
     *
     * Errors are logged but don't propagate - each operation fails independently.
     *
     * @param activityId the saved activity ID
     * @param userId the user ID who uploaded the activity
     */
    @Async("taskExecutor")
    public void processActivityAsync(UUID activityId, UUID userId) {
        log.info("Starting async post-processing for activity {} by user {}", activityId, userId);

        updatePersonalRecordsAsync(activityId);
        updateHeatmapAsync(activityId);
        fetchWeatherAsync(activityId);

        log.info("Completed async post-processing for activity {}", activityId);
    }

    /**
     * Check and update personal records for the activity.
     *
     * @param activityId the activity ID to process
     */
    void updatePersonalRecordsAsync(UUID activityId) {
        try {
            log.debug("Async: Checking personal records for activity {}", activityId);

            Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new EntityNotFoundException("Activity not found: " + activityId));

            personalRecordService.checkAndUpdatePersonalRecords(activity);

            log.info("Async: Personal records updated for activity {}", activityId);

        } catch (Exception e) {
            log.error("Async: Failed to update personal records for activity {}: {}",
                activityId, e.getMessage(), e);
            // Don't rethrow - error logged, operation fails independently
        }
    }

    /**
     * Update heatmap grid with activity GPS data.
     *
     * @param activityId the activity ID to process
     */
    void updateHeatmapAsync(UUID activityId) {
        try {
            log.debug("Async: Updating heatmap for activity {}", activityId);

            Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new EntityNotFoundException("Activity not found: " + activityId));

            heatmapGridService.updateHeatmapForActivity(activity);

            log.info("Async: Heatmap updated for activity {}", activityId);

        } catch (Exception e) {
            log.error("Async: Failed to update heatmap for activity {}: {}",
                activityId, e.getMessage(), e);
            // Don't rethrow - error logged, operation fails independently
        }
    }

    /**
     * Fetch weather data for the activity location and time.
     *
     * @param activityId the activity ID to process
     */
    void fetchWeatherAsync(UUID activityId) {
        try {
            log.debug("Async: Fetching weather for activity {}", activityId);

            Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new EntityNotFoundException("Activity not found: " + activityId));

            weatherService.fetchWeatherForActivity(activity);

            log.info("Async: Weather fetched for activity {}", activityId);

        } catch (Exception e) {
            log.error("Async: Failed to fetch weather for activity {}: {}",
                activityId, e.getMessage(), e);
            // Don't rethrow - error logged, operation fails independently
        }
    }

    /**
     * Publish activity to the Fediverse (ActivityPub federation).
     * Generates activity image and sends Create activity to all follower inboxes.
     *
     * Only publishes if activity visibility is PUBLIC or FOLLOWERS.
     * Called from the controller when the user finalizes activity metadata.
     *
     * @param activityId the activity ID to publish
     * @param userId the user ID who owns the activity
     */
    @Async("taskExecutor")
    public void publishToFederationAsync(UUID activityId, UUID userId) {
        try {
            log.debug("Async: Publishing activity {} to Fediverse", activityId);

            Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new EntityNotFoundException("Activity not found: " + activityId));

            User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

            // Only publish if activity is PUBLIC or FOLLOWERS
            if (activity.getVisibility() != Activity.Visibility.PUBLIC &&
                activity.getVisibility() != Activity.Visibility.FOLLOWERS) {
                log.debug("Async: Skipping federation for private activity {}", activityId);
                return;
            }

            String activityUri = baseUrl + "/activities/" + activity.getId();
            String actorUri = baseUrl + "/users/" + user.getUsername();

            // Generate activity image (map with GPS track)
            String imageUrl = null;
            try {
                imageUrl = activityImageService.generateActivityImage(activity);
            } catch (Exception e) {
                log.warn("Async: Failed to generate activity image for {}: {}", activityId, e.getMessage());
                // Continue without image
            }

            // Build ActivityPub Note object
            Map<String, Object> noteObject = new HashMap<>();
            noteObject.put("id", activityUri);
            noteObject.put("type", "Note");
            noteObject.put("attributedTo", actorUri);
            noteObject.put("published", activity.getCreatedAt().toString());
            noteObject.put("content", formatActivityContent(activity));
            noteObject.put("url", baseUrl + "/activities/" + activity.getId());

            // Extract hashtags from user text and add as tags
            List<String> hashtags = extractHashtags(activity);
            if (!hashtags.isEmpty()) {
                List<Map<String, String>> tags = hashtags.stream()
                    .map(ht -> {
                        Map<String, String> tag = new HashMap<>();
                        tag.put("type", "Hashtag");
                        tag.put("href", baseUrl + "/tags/" + ht.toLowerCase());
                        tag.put("name", "#" + ht);
                        return tag;
                    })
                    .toList();
                noteObject.put("tag", tags);
            }

            // Set visibility (to/cc fields)
            if (activity.getVisibility() == Activity.Visibility.PUBLIC) {
                noteObject.put("to", List.of("https://www.w3.org/ns/activitystreams#Public"));
                noteObject.put("cc", List.of(actorUri + "/followers"));
            } else {
                // FOLLOWERS only
                noteObject.put("to", List.of(actorUri + "/followers"));
            }

            // Attach activity image if generated
            if (imageUrl != null) {
                Map<String, Object> imageAttachment = new HashMap<>();
                imageAttachment.put("type", "Image");
                imageAttachment.put("mediaType", "image/png");
                imageAttachment.put("url", imageUrl);
                imageAttachment.put("name", "Activity map showing " + activity.getActivityType() + " route");
                noteObject.put("attachment", List.of(imageAttachment));
            }

            // Send to all follower inboxes
            federationService.sendCreateActivity(
                activityUri,
                noteObject,
                user,
                activity.getVisibility() == Activity.Visibility.PUBLIC
            );

            log.info("Async: Activity {} published to Fediverse", activityId);

        } catch (Exception e) {
            log.error("Async: Failed to publish activity {} to Fediverse: {}",
                activityId, e.getMessage(), e);
            // Don't rethrow - error logged, operation fails independently
        }
    }

    /**
     * Format activity content as HTML for ActivityPub Note.
     * Mastodon and most Fediverse software expect HTML in the content field.
     * Hashtags in user text are converted to proper HTML links.
     */
    private String formatActivityContent(Activity activity) {
        StringBuilder content = new StringBuilder();

        if (activity.getTitle() != null && !activity.getTitle().isEmpty()) {
            content.append("<p><strong>").append(linkifyHashtags(escapeHtml(activity.getTitle()))).append("</strong></p>");
        }

        if (activity.getDescription() != null && !activity.getDescription().isEmpty()) {
            content.append("<p>").append(linkifyHashtags(escapeHtml(activity.getDescription()))).append("</p>");
        }

        String activityEmoji = getActivityEmoji(activity.getActivityType());
        String formattedType = ActivityFormatter.formatActivityType(activity.getActivityType());
        content.append("<p>").append(activityEmoji).append(" ").append(escapeHtml(formattedType)).append("</p>");

        StringBuilder metrics = new StringBuilder();
        if (activity.getTotalDistance() != null) {
            metrics.append("📏 ")
                .append(String.format("%.2f km", activity.getTotalDistance().doubleValue() / 1000.0))
                .append("<br>");
        }
        if (activity.getTotalDurationSeconds() != null) {
            long hours = activity.getTotalDurationSeconds() / 3600;
            long minutes = (activity.getTotalDurationSeconds() % 3600) / 60;
            long seconds = activity.getTotalDurationSeconds() % 60;
            metrics.append("⏱️ ");
            if (hours > 0) {
                metrics.append(hours).append("h ");
            }
            metrics.append(minutes).append("m ").append(seconds).append("s").append("<br>");
        }
        if (activity.getElevationGain() != null) {
            metrics.append("⛰️ ")
                .append(String.format("%.0f m", activity.getElevationGain()))
                .append("<br>");
        }
        if (metrics.length() > 0) {
            content.append("<p>").append(metrics).append("</p>");
        }

        return content.toString();
    }

    private static final java.util.regex.Pattern HASHTAG_PATTERN =
        java.util.regex.Pattern.compile("(?<=^|\\s)#(\\w+)", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Extract all hashtags from user-provided title and description.
     */
    private List<String> extractHashtags(Activity activity) {
        List<String> hashtags = new java.util.ArrayList<>();
        for (String text : List.of(
                activity.getTitle() != null ? activity.getTitle() : "",
                activity.getDescription() != null ? activity.getDescription() : "")) {
            var matcher = HASHTAG_PATTERN.matcher(text);
            while (matcher.find()) {
                String tag = matcher.group(1);
                if (hashtags.stream().noneMatch(t -> t.equalsIgnoreCase(tag))) {
                    hashtags.add(tag);
                }
            }
        }
        return hashtags;
    }

    /**
     * Convert #hashtag occurrences in already-escaped HTML text to ActivityPub hashtag links.
     */
    private String linkifyHashtags(String escapedHtml) {
        return HASHTAG_PATTERN.matcher(escapedHtml).replaceAll(match -> {
            String tag = match.group(1);
            return "<a href=\"" + baseUrl + "/tags/" + tag.toLowerCase()
                + "\" class=\"mention hashtag\" rel=\"tag\">#<span>" + tag + "</span></a>";
        });
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    /**
     * Get an emoji for the activity type.
     *
     * @param type the activity type
     * @return emoji representing the activity type
     */
    private String getActivityEmoji(Activity.ActivityType type) {
        return switch (type) {
            case RUN -> "🏃";
            case RIDE -> "🚴";
            case HIKE -> "🥾";
            case WALK -> "🚶";
            case SWIM -> "🏊";
            case ALPINE_SKI, BACKCOUNTRY_SKI, NORDIC_SKI -> "⛷️";
            case SNOWBOARD -> "🏂";
            case ROWING -> "🚣";
            case KAYAKING, CANOEING -> "🛶";
            case INLINE_SKATING -> "⛸️";
            case ROCK_CLIMBING, MOUNTAINEERING -> "🧗";
            case YOGA -> "🧘";
            case WORKOUT -> "💪";
            default -> "🏋️";
        };
    }
}
