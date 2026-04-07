package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import net.javahippie.fitpub.model.dto.ActivityDTO;
import net.javahippie.fitpub.model.dto.TimelineActivityDTO;
import net.javahippie.fitpub.repository.LikeRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Populates per-emoji reaction counts and the current user's reaction onto a batch
 * of activity DTOs in two queries (one for the aggregate counts, one for the
 * current user's reactions). Used by the timeline pagers and the single-activity
 * loader so the same shape is presented everywhere without N+1 queries.
 *
 * <p>Activities with no reactions get an empty map (never null) so the frontend
 * can iterate without null checks.
 */
@Service
@RequiredArgsConstructor
public class ReactionEnricher {

    private final LikeRepository likeRepository;

    /**
     * Populates {@code reactionCounts} and {@code currentUserReaction} on every
     * DTO in the given list. Safe to call with an empty list.
     *
     * @param activities the timeline DTOs to enrich (mutated in place)
     * @param currentUserId the viewing user, or null for unauthenticated requests
     */
    public void enrichTimeline(List<TimelineActivityDTO> activities, UUID currentUserId) {
        if (activities == null || activities.isEmpty()) {
            return;
        }
        List<UUID> activityIds = activities.stream().map(TimelineActivityDTO::getId).toList();
        Map<UUID, Map<String, Long>> countsByActivity = loadCounts(activityIds);
        Map<UUID, String> userReactions = loadUserReactions(activityIds, currentUserId);

        for (TimelineActivityDTO dto : activities) {
            dto.setReactionCounts(countsByActivity.getOrDefault(dto.getId(), Map.of()));
            dto.setCurrentUserReaction(userReactions.get(dto.getId()));
        }
    }

    /**
     * Same as {@link #enrichTimeline} but for {@link ActivityDTO}, used by the
     * activity listing endpoints (my activities, user public activities, peak filter).
     */
    public void enrichActivities(List<ActivityDTO> activities, UUID currentUserId) {
        if (activities == null || activities.isEmpty()) {
            return;
        }
        List<UUID> activityIds = activities.stream().map(ActivityDTO::getId).toList();
        Map<UUID, Map<String, Long>> countsByActivity = loadCounts(activityIds);
        Map<UUID, String> userReactions = loadUserReactions(activityIds, currentUserId);

        for (ActivityDTO dto : activities) {
            dto.setReactionCounts(countsByActivity.getOrDefault(dto.getId(), Map.of()));
            dto.setCurrentUserReaction(userReactions.get(dto.getId()));
        }
    }

    /**
     * Populates {@code reactionCounts} and {@code currentUserReaction} on a single
     * activity DTO. Cheaper variant of {@link #enrichTimeline} for the
     * single-activity endpoints.
     */
    public void enrichSingle(ActivityDTO activity, UUID currentUserId) {
        if (activity == null || activity.getId() == null) {
            return;
        }
        List<UUID> activityIds = List.of(activity.getId());
        Map<UUID, Map<String, Long>> countsByActivity = loadCounts(activityIds);
        Map<UUID, String> userReactions = loadUserReactions(activityIds, currentUserId);

        activity.setReactionCounts(countsByActivity.getOrDefault(activity.getId(), Map.of()));
        activity.setCurrentUserReaction(userReactions.get(activity.getId()));
    }

    private Map<UUID, Map<String, Long>> loadCounts(Collection<UUID> activityIds) {
        Map<UUID, Map<String, Long>> result = new HashMap<>();
        for (Object[] row : likeRepository.countByActivityIdsGroupedByEmoji(activityIds)) {
            UUID activityId = (UUID) row[0];
            String emoji = (String) row[1];
            Long count = (Long) row[2];
            result.computeIfAbsent(activityId, k -> new java.util.LinkedHashMap<>()).put(emoji, count);
        }
        return result;
    }

    private Map<UUID, String> loadUserReactions(Collection<UUID> activityIds, UUID currentUserId) {
        if (currentUserId == null) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        for (Object[] row : likeRepository.findUserReactionsByActivityIds(currentUserId, activityIds)) {
            result.put((UUID) row[0], (String) row[1]);
        }
        return result;
    }
}
