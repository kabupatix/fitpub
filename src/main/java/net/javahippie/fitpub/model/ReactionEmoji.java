package net.javahippie.fitpub.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The fixed palette of emoji reactions a user may apply to an activity.
 *
 * <p>The set is intentionally small and curated. The same palette is enforced by
 * a CHECK constraint on the {@code likes.emoji} column. Any caller that accepts
 * an emoji from outside (HTTP request body, federation payload) MUST normalise
 * it through {@link #normalise(String)} before persistence so that we never end
 * up with a value the database would reject.
 */
public final class ReactionEmoji {

    /** The default reaction used when a client or federation peer sends none / sends an unknown one. */
    public static final String DEFAULT = "❤️";

    /**
     * The fixed palette of allowed reaction emoji, in display order.
     * The order here drives the UI picker order.
     */
    public static final Set<String> PALETTE = Set.copyOf(new LinkedHashSet<>(java.util.List.of(
        "❤️",
        "🔥",
        "💪",
        "🏔️",
        "🤯",
        "🥲"
    )));

    private ReactionEmoji() {
    }

    /**
     * Returns true if the given string is one of the allowed reaction emojis.
     */
    public static boolean isAllowed(String emoji) {
        return emoji != null && PALETTE.contains(emoji);
    }

    /**
     * Returns the given emoji if it is in the palette, otherwise the {@link #DEFAULT}.
     * Use this for federation receive paths where rejecting unknown values would be
     * unfriendly — we degrade gracefully to a heart instead.
     */
    public static String normalise(String emoji) {
        return isAllowed(emoji) ? emoji : DEFAULT;
    }
}
