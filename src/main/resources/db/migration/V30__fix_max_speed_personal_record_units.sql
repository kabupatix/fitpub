-- Migration V30: Fix max_speed personal records that were stored in km/h but
-- labelled as m/s.
--
-- PersonalRecordService used to copy ActivityMetrics.maxSpeed (already in km/h
-- per the parser) into the personal_records.value column with unit = "mps".
-- The display layer then multiplied by 3.6 to "convert m/s to km/h", producing
-- values 3.6× the real speed (a 33 km/h ride was shown as 120 km/h).
--
-- The application code is fixed in PersonalRecordService to actually divide by
-- 3.6 before storing. This migration brings existing rows in line with that
-- contract: any MAX_SPEED row currently stored in km/h is divided by 3.6 to
-- become a real m/s value. After this, value × 3.6 always yields the correct
-- km/h display.

UPDATE personal_records
SET value = value / 3.6
WHERE record_type = 'MAX_SPEED' AND unit = 'mps';
