package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.ActivityPeak;
import net.javahippie.fitpub.model.entity.Peak;
import net.javahippie.fitpub.repository.ActivityPeakRepository;
import net.javahippie.fitpub.repository.ActivityRepository;
import net.javahippie.fitpub.repository.PeakRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class PeakDetectionService {

    private static final double PEAK_PROXIMITY_METERS = 100.0;

    private final PeakRepository peakRepository;
    private final ActivityPeakRepository activityPeakRepository;
    private final ActivityRepository activityRepository;

    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);

    /**
     * Detect peaks along an activity's route and save the associations.
     *
     * @param activity the activity to detect peaks for
     * @return the list of peaks found near the route
     */
    @Transactional
    public List<Peak> detectPeaksForActivity(Activity activity) {
        if (Boolean.TRUE.equals(activity.getIndoor())) {
            log.debug("Activity {} is indoor/virtual, skipping peak detection", activity.getId());
            return List.of();
        }

        if (activity.getSimplifiedTrack() == null) {
            log.debug("Activity {} has no GPS track, skipping peak detection", activity.getId());
            return List.of();
        }

        List<Peak> nearbyPeaks = peakRepository.findPeaksNearActivity(
            activity.getId(), PEAK_PROXIMITY_METERS
        );

        if (nearbyPeaks.isEmpty()) {
            log.debug("No peaks found near activity {}", activity.getId());
            return List.of();
        }

        for (Peak peak : nearbyPeaks) {
            if (!activityPeakRepository.existsByActivityIdAndPeakId(activity.getId(), peak.getId())) {
                ActivityPeak activityPeak = ActivityPeak.builder()
                    .activityId(activity.getId())
                    .peak(peak)
                    .build();
                activityPeakRepository.save(activityPeak);
            }
        }

        log.info("Detected {} peaks for activity {}", nearbyPeaks.size(), activity.getId());
        return nearbyPeaks;
    }

    /**
     * Run peak detection retroactively on all activities.
     * Safe to call multiple times — skips activities that already have peaks detected.
     *
     * @return true if backfill was started, false if already running
     */
    @Async("taskExecutor")
    public void backfillAllActivities() {
        if (!backfillRunning.compareAndSet(false, true)) {
            log.warn("Peak backfill already running, skipping");
            return;
        }

        try {
            log.info("Starting retroactive peak detection for all activities");

            int page = 0;
            int pageSize = 100;
            int totalProcessed = 0;
            int totalPeaksFound = 0;

            Page<Activity> activityPage;
            do {
                activityPage = activityRepository.findAll(PageRequest.of(page, pageSize));
                for (Activity activity : activityPage.getContent()) {
                    try {
                        List<Peak> peaks = detectPeaksForActivity(activity);
                        totalPeaksFound += peaks.size();
                        totalProcessed++;
                    } catch (Exception e) {
                        log.warn("Failed peak detection for activity {}: {}", activity.getId(), e.getMessage());
                    }
                }
                page++;
                log.info("Peak backfill progress: processed {} / {} activities, found {} peaks so far",
                    totalProcessed, activityPage.getTotalElements(), totalPeaksFound);
            } while (activityPage.hasNext());

            log.info("Peak backfill complete: processed {} activities, found {} total peak associations",
                totalProcessed, totalPeaksFound);
        } finally {
            backfillRunning.set(false);
        }
    }

    public boolean isBackfillRunning() {
        return backfillRunning.get();
    }
}
