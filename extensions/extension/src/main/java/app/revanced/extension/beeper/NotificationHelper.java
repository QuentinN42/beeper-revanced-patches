package app.revanced.extension.beeper;

import android.app.Notification;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Hooks called from the patched app before notifications are posted or canceled.
 *
 * Repeated messages of the same conversation must alert only once. Android only
 * silences repeats of an existing notification ({@link Notification#FLAG_ONLY_ALERT_ONCE})
 * when the notification is posted again with the same id, but messengers may post
 * every message with a new id. This helper therefore also rewrites the id of every
 * notification to a stable id derived from its conversation, and translates
 * cancels of the currently displayed notification so clearing still works.
 */
public class NotificationHelper {
    private static final int FLAG_ONLY_ALERT_ONCE = 0x00000008;

    /**
     * Deduped ids are pinned to a high positive range so they cannot collide
     * with ids the app itself chooses, such as its foreground service
     * notification.
     */
    private static final int DEDUPED_ID_MARK = 0x40000000;
    private static final int DEDUPED_ID_MASK = 0x3FFFFFFF;

    /**
     * Mappings are only needed while a notification is displayed; clear them
     * when they grow beyond what a reasonable session produces.
     */
    private static final int MAX_MAPPINGS = 1024;

    /** Original id -> conversation key. */
    private static final ConcurrentHashMap<Integer, String> idsToKeys = new ConcurrentHashMap<>();

    /** Conversation key -> original id of the notification currently displayed. */
    private static final ConcurrentHashMap<String, Integer> latestIdsByKeys = new ConcurrentHashMap<>();

    /**
     * NotificationManager.notify(int id, Notification) and the
     * NotificationManagerCompat equivalent.
     *
     * @return the id to pass to notify.
     */
    public static int modifyNotification(int id, Notification notification) {
        return dedupe(id, notification);
    }

    /**
     * NotificationManager.notify(String tag, int id, Notification) and the
     * NotificationManagerCompat equivalent.
     *
     * <p>The tag is deliberately ignored: it is not guaranteed to identify the
     * conversation, while the notification title is.
     *
     * @return the id to pass to notify.
     */
    public static int modifyNotification(String tag, int id, Notification notification) {
        return dedupe(id, notification);
    }

    /**
     * NotificationManager.cancel(int id), cancel(String tag, int id) and the
     * NotificationManagerCompat equivalents.
     *
     * @return the id to pass to cancel.
     */
    public static int translateCanceled(int id) {
        String key = idsToKeys.get(id);
        if (key == null) {
            return id;
        }

        // Only translate cancels of the notification currently displayed for
        // the conversation: cancels of superseded per-message ids pass through
        // unchanged, so repeated cancels cannot clear the shared notification.
        Integer latestId = latestIdsByKeys.get(key);
        return latestId != null && latestId.intValue() == id ? stableId(key) : id;
    }

    /**
     * Fallback for call sites where the id register cannot receive the helper
     * result (registers above 255 cannot be addressed by move-result).
     */
    public static void setAlertOnce(Notification notification) {
        if (notification != null) {
            notification.flags |= FLAG_ONLY_ALERT_ONCE;
        }
    }

    private static int dedupe(int id, Notification notification) {
        if (notification != null) {
            notification.flags |= FLAG_ONLY_ALERT_ONCE;
        }

        String key = conversationKey(notification);
        if (key == null) {
            return id;
        }

        if (idsToKeys.size() > MAX_MAPPINGS || latestIdsByKeys.size() > MAX_MAPPINGS) {
            idsToKeys.clear();
            latestIdsByKeys.clear();
        }

        idsToKeys.put(id, key);
        latestIdsByKeys.put(key, id);

        return stableId(key);
    }

    private static int stableId(String key) {
        return (key.hashCode() & DEDUPED_ID_MASK) | DEDUPED_ID_MARK;
    }

    /**
     * A stable identity for the conversation: the notification category (so
     * calls and messages of the same person do not share a notification) and
     * the title, which messengers set to the conversation name for every
     * message.
     */
    private static String conversationKey(Notification notification) {
        if (notification == null || notification.extras == null) {
            return null;
        }

        CharSequence title = notification.extras.getCharSequence("android.title");
        if (title == null || title.length() == 0) {
            return null;
        }

        String category = notification.category;
        return (category == null ? "" : category) + "|" + title;
    }
}
