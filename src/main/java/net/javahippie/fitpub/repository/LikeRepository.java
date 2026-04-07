package net.javahippie.fitpub.repository;

import net.javahippie.fitpub.model.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Like entity operations.
 */
@Repository
public interface LikeRepository extends JpaRepository<Like, UUID> {

    /**
     * Find all likes for an activity.
     *
     * @param activityId the activity ID
     * @return list of likes
     */
    List<Like> findByActivityIdOrderByCreatedAtDesc(UUID activityId);

    /**
     * Count likes for an activity.
     *
     * @param activityId the activity ID
     * @return number of likes
     */
    long countByActivityId(UUID activityId);

    /**
     * Find a like by activity and local user.
     *
     * @param activityId the activity ID
     * @param userId the user ID
     * @return the like if exists
     */
    Optional<Like> findByActivityIdAndUserId(UUID activityId, UUID userId);

    /**
     * Find a like by activity and remote actor.
     *
     * @param activityId the activity ID
     * @param remoteActorUri the remote actor URI
     * @return the like if exists
     */
    Optional<Like> findByActivityIdAndRemoteActorUri(UUID activityId, String remoteActorUri);

    /**
     * Find a like by ActivityPub ID.
     *
     * @param activityPubId the ActivityPub Like activity ID
     * @return the like if exists
     */
    Optional<Like> findByActivityPubId(String activityPubId);

    /**
     * Check if a local user has liked an activity.
     *
     * @param activityId the activity ID
     * @param userId the user ID
     * @return true if liked
     */
    boolean existsByActivityIdAndUserId(UUID activityId, UUID userId);

    /**
     * Check if a remote actor has liked an activity.
     *
     * @param activityId the activity ID
     * @param remoteActorUri the remote actor URI
     * @return true if liked
     */
    boolean existsByActivityIdAndRemoteActorUri(UUID activityId, String remoteActorUri);

    /**
     * Delete a like by activity and user.
     *
     * @param activityId the activity ID
     * @param userId the user ID
     */
    void deleteByActivityIdAndUserId(UUID activityId, UUID userId);

    /**
     * Delete a like by activity and remote actor.
     *
     * @param activityId the activity ID
     * @param remoteActorUri the remote actor URI
     */
    void deleteByActivityIdAndRemoteActorUri(UUID activityId, String remoteActorUri);

    /**
     * Delete all likes from a remote actor (when remote account is deleted).
     *
     * @param remoteActorUri the remote actor's URI
     */
    @Modifying
    @Transactional
    void deleteByRemoteActorUri(String remoteActorUri);

    /**
     * Per-emoji reaction counts for a batch of activities. Returns one row per
     * (activityId, emoji) tuple. Used by the timeline / activity loaders to
     * populate {@code reactionCounts} on each activity DTO without N+1 queries.
     *
     * @param activityIds the activity IDs to aggregate
     * @return list of {@code [activityId UUID, emoji String, count Long]} rows
     */
    @Query("""
           SELECT l.activityId, l.emoji, COUNT(l)
           FROM Like l
           WHERE l.activityId IN :activityIds
           GROUP BY l.activityId, l.emoji
           """)
    List<Object[]> countByActivityIdsGroupedByEmoji(@Param("activityIds") Collection<UUID> activityIds);

    /**
     * The current user's own reaction (if any) on each activity in a batch.
     * Used together with {@link #countByActivityIdsGroupedByEmoji} to populate
     * the per-DTO {@code currentUserReaction} field.
     *
     * @param userId the current user ID
     * @param activityIds the activity IDs to look up
     * @return list of {@code [activityId UUID, emoji String]} rows for the user's reactions
     */
    @Query("""
           SELECT l.activityId, l.emoji
           FROM Like l
           WHERE l.userId = :userId AND l.activityId IN :activityIds
           """)
    List<Object[]> findUserReactionsByActivityIds(@Param("userId") UUID userId,
                                                   @Param("activityIds") Collection<UUID> activityIds);
}
