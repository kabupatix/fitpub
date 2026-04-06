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
     * Uses ST_DWithin on geography type for accurate meter-based distance.
     */
    @Query(value = """
                   SELECT p.* FROM peaks p
                   JOIN activities a ON a.id = :activityId
                   WHERE a.simplified_track IS NOT NULL
                     AND ST_DWithin(CAST(p.geom AS geography), CAST(a.simplified_track AS geography), :distanceMeters)
                   ORDER BY p.name
                   """,
           nativeQuery = true)
    List<Peak> findPeaksNearActivity(UUID activityId, double distanceMeters);
}
