package io.github.chungus_actual.nightdrop

import android.service.notification.NotificationListenerService

/**
 * Exists only so MediaSessionManager.getActiveSessions() will answer: Android hands the active media
 * sessions (title, artist, album art) to an app that holds notification-listener access. Nothing here
 * reads, keeps or acts on a notification.
 */
class MediaListener : NotificationListenerService()
