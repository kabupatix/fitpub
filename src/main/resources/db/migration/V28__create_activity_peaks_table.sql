CREATE TABLE activity_peaks (
    id          SERIAL PRIMARY KEY,
    activity_id UUID NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    peak_id     INTEGER NOT NULL REFERENCES peaks(id) ON DELETE CASCADE,
    UNIQUE (activity_id, peak_id)
);

CREATE INDEX idx_activity_peaks_activity ON activity_peaks (activity_id);
CREATE INDEX idx_activity_peaks_peak ON activity_peaks (peak_id);
