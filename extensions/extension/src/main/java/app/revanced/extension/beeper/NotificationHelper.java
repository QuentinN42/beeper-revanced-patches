package app.revanced.extension.beeper;

import android.app.Notification;

/**
 * Mutates notifications before they are posted by NotificationManager.
 *
 * Applied to every notification of the target app: the APK is patched so this helper
 * is invoked right before each <code>notify()</code> call.
 */
public class NotificationHelper {
    /**
     * Bit 0x00000008, see {@link Notification#FLAG_ONLY_ALERT_ONCE}.
     */
    private static final int FLAG_ONLY_ALERT_ONCE = 0x00000008;

    /**
     * Marks the notification as "only alert once": Android plays sound / vibration for the
     * first post only. Subsequent updates posted without the notification being dismissed
     * are silent. The same object is mutated in place, so this must be called as a
     * void static helper right before the notification is handed to the framework.
     */
    public static void modifyNotification(Notification notification) {
        if (notification == null) {
            return;
        }

        notification.flags |= FLAG_ONLY_ALERT_ONCE;
    }
}
