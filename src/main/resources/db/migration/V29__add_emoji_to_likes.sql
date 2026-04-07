-- Migration V29: Convert likes into emoji reactions
--
-- Adds an `emoji` column to the likes table so a "like" can be one of a fixed
-- palette of emoji reactions instead of just a heart. Existing rows are
-- backfilled to ❤️ to preserve current behaviour, then the column is made NOT
-- NULL with a default and a CHECK constraint pinning it to the supported
-- palette. The unique index on (activity_id, user_id) is unchanged: a user
-- still gets exactly one reaction per activity, and switching reactions is an
-- UPDATE rather than INSERT+DELETE.

ALTER TABLE likes ADD COLUMN emoji VARCHAR(16);

UPDATE likes SET emoji = '❤️' WHERE emoji IS NULL;

ALTER TABLE likes ALTER COLUMN emoji SET NOT NULL;
ALTER TABLE likes ALTER COLUMN emoji SET DEFAULT '❤️';

ALTER TABLE likes ADD CONSTRAINT chk_likes_emoji_palette
    CHECK (emoji IN ('❤️', '🔥', '💪', '🏔️', '🤯', '🥲'));

COMMENT ON COLUMN likes.emoji IS
    'Emoji reaction (one of the fixed palette). NULL is not allowed; existing rows backfilled to ❤️.';

-- Notifications also carry the reaction emoji so the recipient sees which
-- reaction was applied without an extra lookup. Existing ACTIVITY_LIKED rows
-- are backfilled to ❤️ for the same reason.
ALTER TABLE notifications ADD COLUMN reaction_emoji VARCHAR(16);
UPDATE notifications SET reaction_emoji = '❤️' WHERE type = 'ACTIVITY_LIKED' AND reaction_emoji IS NULL;
COMMENT ON COLUMN notifications.reaction_emoji IS
    'For ACTIVITY_LIKED notifications: the emoji used in the reaction. Null for other notification types.';
