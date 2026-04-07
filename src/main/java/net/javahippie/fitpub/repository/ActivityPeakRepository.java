package net.javahippie.fitpub.repository;

import net.javahippie.fitpub.model.entity.ActivityPeak;
import net.javahippie.fitpub.model.entity.Peak;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ActivityPeakRepository extends JpaRepository<ActivityPeak, Integer> {

    List<ActivityPeak> findByActivityId(UUID activityId);

    boolean existsByActivityIdAndPeakId(UUID activityId, Integer peakId);

    /**
     * Find all public activity IDs for a user that include a specific peak,
     * ordered by start time descending.
     */
    @Query("""
           SELECT ap.activityId FROM ActivityPeak ap
           WHERE ap.peak.id = :peakId
             AND ap.activityId IN (
                 SELECT a.id FROM Activity a
                 WHERE a.userId = :userId AND a.visibility = 'PUBLIC'
             )
           """)
    List<UUID> findPublicActivityIdsByUserAndPeak(UUID userId, Integer peakId);

    /**
     * Find all unique peaks visited by a user with visit count and latest activity,
     * ordered by name.
     */
    @Query(value = """
                   SELECT p.id AS peakId, p.name AS peakName, p.wikipedia AS wikipedia,
                          COUNT(ap.id) AS visitCount,
                          CAST(MAX(a.started_at) AS TIMESTAMP) AS latestVisit,
                          CAST((ARRAY_AGG(a.id ORDER BY a.started_at DESC))[1] AS UUID) AS latestActivityId
                   FROM activity_peaks ap
                   JOIN peaks p ON p.id = ap.peak_id
                   JOIN activities a ON a.id = ap.activity_id
                   WHERE a.user_id = :userId
                     AND a.visibility = 'PUBLIC'
                   GROUP BY p.id, p.name, p.wikipedia
                   ORDER BY p.name
                   """,
           nativeQuery = true)
    List<PeakVisitProjection> findPeaksVisitedByUser(UUID userId);

    interface PeakVisitProjection {
        Integer getPeakId();
        String getPeakName();
        String getWikipedia();
        Long getVisitCount();
        UUID getLatestActivityId();
    }

    void deleteByActivityId(UUID activityId);
}
