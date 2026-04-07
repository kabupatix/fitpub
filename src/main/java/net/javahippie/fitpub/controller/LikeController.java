package net.javahippie.fitpub.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.ReactionEmoji;
import net.javahippie.fitpub.model.dto.LikeDTO;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.Like;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.LikeRepository;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.service.FederationService;
import net.javahippie.fitpub.service.NotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for like operations.
 */
@RestController
@RequestMapping("/api/activities/{activityId}/likes")
@RequiredArgsConstructor
@Slf4j
public class LikeController {

    private final LikeRepository likeRepository;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final FederationService federationService;
    private final NotificationService notificationService;

    @Value("${fitpub.base-url}")
    private String baseUrl;

    /**
     * Helper method to get user from authenticated UserDetails.
     */
    private User getUser(UserDetails userDetails) {
        return userRepository.findByUsername(userDetails.getUsername())
            .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /**
     * Get all likes for an activity.
     *
     * @param activityId the activity ID
     * @return list of likes
     */
    @GetMapping
    public ResponseEntity<List<LikeDTO>> getLikes(@PathVariable UUID activityId) {
        // Check if activity exists
        if (!activityRepository.existsById(activityId)) {
            return ResponseEntity.notFound().build();
        }

        List<Like> likes = likeRepository.findByActivityIdOrderByCreatedAtDesc(activityId);
        List<LikeDTO> likeDTOs = likes.stream()
            .map(like -> LikeDTO.fromEntity(like, baseUrl))
            .collect(Collectors.toList());

        return ResponseEntity.ok(likeDTOs);
    }

    /**
     * Request body for {@link #reactToActivity}. Both fields are optional; an empty body
     * is treated as a heart reaction for backwards compatibility with the old "like" UX.
     */
    public record ReactionRequest(String emoji) {}

    /**
     * React to an activity with an emoji from the fixed palette.
     *
     * <p>This endpoint has UPSERT semantics: if the user has not yet reacted, a new row
     * is created (HTTP 201); if they already reacted with a different emoji, the existing
     * row is updated to the new emoji (HTTP 200); if they re-submit the same emoji, the
     * existing row is returned unchanged (HTTP 200).
     *
     * <p>Validation: the emoji must be in {@link ReactionEmoji#PALETTE}. A missing or null
     * emoji defaults to {@link ReactionEmoji#DEFAULT}; an unknown emoji is rejected with 400.
     *
     * @param activityId the activity to react to
     * @param request the reaction request body (optional)
     * @param userDetails the authenticated user
     * @return the created or updated reaction
     */
    @PostMapping
    @Transactional
    public ResponseEntity<LikeDTO> reactToActivity(
        @PathVariable UUID activityId,
        @RequestBody(required = false) ReactionRequest request,
        @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = getUser(userDetails);

        // Check if activity exists
        Activity activity = activityRepository.findById(activityId)
            .orElse(null);
        if (activity == null) {
            return ResponseEntity.notFound().build();
        }

        // Resolve and validate the emoji. A missing body / null field defaults to ❤️
        // (backwards compat with the original heart-only "like" client). An explicit
        // value that isn't in the palette is rejected — we don't silently downgrade
        // requests from local clients the way we do for federation receive paths.
        String requestedEmoji = (request == null) ? null : request.emoji();
        String emoji;
        if (requestedEmoji == null || requestedEmoji.isBlank()) {
            emoji = ReactionEmoji.DEFAULT;
        } else if (ReactionEmoji.isAllowed(requestedEmoji)) {
            emoji = requestedEmoji;
        } else {
            log.warn("User {} attempted to react with unsupported emoji {} on activity {}",
                user.getUsername(), requestedEmoji, activityId);
            return ResponseEntity.badRequest().build();
        }

        // UPSERT: update the existing row if present, otherwise create a new one.
        Optional<Like> existing = likeRepository.findByActivityIdAndUserId(activityId, user.getId());
        Like saved;
        boolean created;
        if (existing.isPresent()) {
            Like like = existing.get();
            boolean changed = !emoji.equals(like.getEmoji());
            like.setEmoji(emoji);
            // Refresh display name and avatar in case the user changed them since
            // their last reaction.
            like.setDisplayName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
            like.setAvatarUrl(user.getAvatarUrl());
            saved = likeRepository.save(like);
            created = false;
            if (changed) {
                log.info("User {} switched reaction on activity {} to {}", user.getUsername(), activityId, emoji);
            }
        } else {
            Like like = Like.builder()
                .activityId(activityId)
                .userId(user.getId())
                .emoji(emoji)
                .displayName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername())
                .avatarUrl(user.getAvatarUrl())
                .build();
            saved = likeRepository.save(like);
            created = true;
            log.info("User {} reacted to activity {} with {}", user.getUsername(), activityId, emoji);
        }

        // Create notification for activity owner — only on the initial reaction so
        // that switching emojis doesn't spam them. The notification carries the
        // current emoji so the recipient sees what was actually used.
        if (created) {
            String likerActorUri = user.getActorUri(baseUrl);
            notificationService.createActivityLikedNotification(activity, likerActorUri, emoji);
        }

        // Send ActivityPub Like activity to followers if activity is public.
        // Federation always sends a fresh Like + content; downstream Pleroma/Akkoma will
        // overwrite the previous reaction, vanilla Mastodon will just show another like.
        if (activity.getVisibility() == Activity.Visibility.PUBLIC) {
            String activityUri = baseUrl + "/activities/" + activityId;
            federationService.sendLikeActivity(activityUri, user, emoji);
        }

        HttpStatus status = created ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(LikeDTO.fromEntity(saved, baseUrl));
    }

    /**
     * Unlike an activity.
     *
     * @param activityId the activity ID
     * @param userDetails the authenticated user
     * @return no content
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> unlikeActivity(
        @PathVariable UUID activityId,
        @AuthenticationPrincipal UserDetails userDetails
    ) {
        User user = getUser(userDetails);

        // Check if like exists
        if (!likeRepository.existsByActivityIdAndUserId(activityId, user.getId())) {
            return ResponseEntity.notFound().build();
        }

        // Get activity for visibility check
        Activity activity = activityRepository.findById(activityId).orElse(null);

        likeRepository.deleteByActivityIdAndUserId(activityId, user.getId());

        log.info("User {} unliked activity {}", user.getUsername(), activityId);

        // Send ActivityPub Undo Like activity to followers if activity is public
        if (activity != null && activity.getVisibility() == Activity.Visibility.PUBLIC) {
            String activityUri = baseUrl + "/activities/" + activityId;
            String likeId = baseUrl + "/activities/like/" + UUID.randomUUID();
            String actorUri = baseUrl + "/users/" + user.getUsername();

            Map<String, Object> likeActivity = new HashMap<>();
            likeActivity.put("type", "Like");
            likeActivity.put("id", likeId);
            likeActivity.put("actor", actorUri);
            likeActivity.put("object", activityUri);

            federationService.sendUndoActivity(likeId, likeActivity, user);
        }

        return ResponseEntity.noContent().build();
    }
}
