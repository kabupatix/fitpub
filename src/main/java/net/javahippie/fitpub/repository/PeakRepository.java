package net.javahippie.fitpub.repository;

import net.javahippie.fitpub.model.entity.Peak;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PeakRepository extends JpaRepository<Peak, Integer> {

    /**
     * Find all peaks within a given distance (meters) of an activity's simplified track.
     * Uses a two-stage approach:
     * 1. ST_Expand + && for fast GIST index lookup on geometry
     * 2. ST_DWithin on geography for precise meter-based filtering
     */
    @Query(value = """
                   SELECT p.* FROM peaks p
                   JOIN activities a ON a.id = :activityId
                   WHERE a.simplified_track IS NOT NULL
                     AND p.geom && ST_Expand(a.simplified_track, 0.002)
                     AND ST_DWithin(CAST(p.geom AS geography), CAST(a.simplified_track AS geography), :distanceMeters)
                   ORDER BY p.name
                   """,
           nativeQuery = true)
    List<Peak> findPeaksNearActivity(UUID activityId, double distanceMeters);
}
