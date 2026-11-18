package com.example.linienbegleiter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.linienbegleiter.MainActivity
import com.example.linienbegleiter.data.local.RecordingDatabase
import com.example.linienbegleiter.data.location.AndroidLocationProvider
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import com.example.linienbegleiter.data.routes.RouteFollowSource
import com.example.linienbegleiter.data.routes.RouteFollowingManager
import com.example.linienbegleiter.data.routes.RouteFollowingState
import com.example.linienbegleiter.data.routes.RoomEditableRouteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedFollowingRoute(
    val routeId: String,
    val source: RouteFollowSource,
    val directionId: String?
)

class RouteFollowingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: RouteFollowingManager
    private var isForeground = false
    private val followingBinder by lazy { FollowingBinder() }

    inner class FollowingBinder : Binder() {
        val followingState: StateFlow<RouteFollowingState>
            get() = manager.state
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val database = RecordingDatabase.getInstance(this)
        manager = RouteFollowingManager(
            recordingRepository = RoomRecordingRepository(database),
            editableRouteRepository = RoomEditableRouteRepository(database),
            locationProvider = AndroidLocationProvider(this),
            scope = serviceScope
        )
        serviceScope.launch {
            manager.state.collect(::updateNotification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(manager.state.value)
        if (!isForeground) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_START -> {
                val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
                val source = intent.getStringExtra(EXTRA_SOURCE)
                    ?.let { runCatching { RouteFollowSource.valueOf(it) }.getOrNull() }
                val directionId = intent.getStringExtra(EXTRA_DIRECTION_ID)
                if (routeId.isNullOrBlank() || source == null) {
                    failAndStop(startId, "Не удалось открыть маршрут для следования.")
                } else {
                    beginFollowing(routeId, source, directionId, startId)
                }
            }

            ACTION_STOP -> serviceScope.launch(Dispatchers.IO) {
                manager.stopFollowing()
                clearSavedRoute()
                withContext(Dispatchers.Main) { stopService(startId) }
            }

            ACTION_RESTORE, null -> {
                val savedRoute = readSavedRoute()
                if (savedRoute == null) {
                    stopService(startId)
                } else {
                    beginFollowing(
                        savedRoute.routeId,
                        savedRoute.source,
                        savedRoute.directionId,
                        startId
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = followingBinder

    override fun onDestroy() {
        manager.stopFollowing()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun beginFollowing(
        routeId: String,
        source: RouteFollowSource,
        directionId: String?,
        startId: Int
    ) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val activeRecording = RoomRecordingRepository(
                    RecordingDatabase.getInstance(this@RouteFollowingService)
                ).getActiveSession()
                check(activeRecording == null) {
                    "Сначала завершите или приостановите GPS-запись."
                }
                manager.startFollowing(routeId, source, directionId)
            }
                .onFailure { error ->
                    clearSavedRoute()
                    manager.reportError(
                        error.message ?: "Не удалось начать следование по маршруту."
                    )
                    withContext(Dispatchers.Main) { stopService(startId) }
                }
        }
    }

    private fun failAndStop(startId: Int, message: String) {
        serviceScope.launch(Dispatchers.IO) {
            clearSavedRoute()
            manager.reportError(message)
            withContext(Dispatchers.Main) { stopService(startId) }
        }
    }

    private fun promoteToForeground(state: RouteFollowingState) {
        if (isForeground) {
            updateNotification(state)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(state),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(state))
            }
            isForeground = true
        } catch (_: SecurityException) {
            manager.reportError(
                "Для следования нужно разрешить доступ к геопозиции."
            )
            stopSelf()
        }
    }

    private fun updateNotification(state: RouteFollowingState) {
        if (!isForeground) return
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: RouteFollowingState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val topSuggestion = state.suggestedNextRoutes.firstOrNull()
        val contentText = when {
            state.errorMessage != null -> state.errorMessage
            state.isArrivedAtDestination -> {
                if (topSuggestion != null) {
                    "Вы прибыли на конечную. Следующий рейс: " + topSuggestion.title
                } else {
                    "Вы прибыли на конечную остановку маршрута."
                }
            }
            !state.isActive -> "Подготовка следования"
            state.locationSample == null -> "Ожидание сигнала GPS"
            else -> state.progress?.let {
                "До следующей остановки: " +
                    (it.distanceToNextStopMeters?.let(::formatDistance) ?: "—")
            } ?: "Следование активно"
        }
        val titleText = when {
            state.isArrivedAtDestination -> "🎉 Прибытие на конечную остановку"
            state.title.isNotBlank() -> "Linienbegleiter — " + state.title
            else -> "Linienbegleiter — следование по маршруту"
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (state.isArrivedAtDestination && topSuggestion != null) {
            val startNextIntent = Intent(this, RouteFollowingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROUTE_ID, topSuggestion.routeId)
                putExtra(EXTRA_SOURCE, topSuggestion.source.name)
                putExtra(EXTRA_DIRECTION_ID, topSuggestion.directionId)
            }
            val startNextPendingIntent = PendingIntent.getService(
                this,
                2,
                startNextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_play,
                "▶ Старт: " + topSuggestion.title,
                startNextPendingIntent
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Следование по маршруту",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает состояние следования по выбранному маршруту."
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun stopService(startId: Int) {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf(startId)
    }

    private fun clearSavedRoute() {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun readSavedRoute(): SavedFollowingRoute? = savedFollowingRoute(this)

    private fun formatDistance(meters: Double): String =
        if (meters < 1_000.0) {
            "${meters.toInt()} м"
        } else {
            String.format(java.util.Locale.getDefault(), "%.1f км", meters / 1_000.0)
        }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "route_following"
        private const val NOTIFICATION_ID = 1408
        private const val PREFERENCES_NAME = "route_following"
        private const val KEY_ROUTE_ID = "route_id"
        private const val KEY_SOURCE = "source"
        private const val KEY_DIRECTION_ID = "direction_id"
        private const val EXTRA_DIRECTION_ID =
            "com.example.linienbegleiter.extra.FOLLOW_DIRECTION_ID"
        private const val EXTRA_ROUTE_ID =
            "com.example.linienbegleiter.extra.FOLLOW_ROUTE_ID"
        private const val EXTRA_SOURCE =
            "com.example.linienbegleiter.extra.FOLLOW_SOURCE"
        private const val ACTION_START = "com.example.linienbegleiter.action.START_FOLLOWING"
        private const val ACTION_RESTORE = "com.example.linienbegleiter.action.RESTORE_FOLLOWING"
        private const val ACTION_STOP = "com.example.linienbegleiter.action.STOP_FOLLOWING"

        fun start(
            context: Context,
            routeId: String,
            source: RouteFollowSource,
            directionId: String? = null
        ) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ROUTE_ID, routeId)
                .putString(KEY_SOURCE, source.name)
                .putString(KEY_DIRECTION_ID, directionId)
                .apply()
            val intent = Intent(context, RouteFollowingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROUTE_ID, routeId)
                putExtra(EXTRA_SOURCE, source.name)
                putExtra(EXTRA_DIRECTION_ID, directionId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun restore(context: Context) {
            val intent = Intent(context, RouteFollowingService::class.java).apply {
                action = ACTION_RESTORE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun savedFollowingRoute(context: Context): SavedFollowingRoute? {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val routeId = preferences.getString(KEY_ROUTE_ID, null) ?: return null
            val source = preferences.getString(KEY_SOURCE, null)
                ?.let { runCatching { RouteFollowSource.valueOf(it) }.getOrNull() }
                ?: return null
            return SavedFollowingRoute(
                routeId = routeId,
                source = source,
                directionId = preferences.getString(KEY_DIRECTION_ID, null)
            )
        }

        fun savedFollowingRouteId(context: Context): String? =
            savedFollowingRoute(context)?.routeId

        fun hasSavedFollowing(context: Context): Boolean = savedFollowingRoute(context) != null

        fun stop(context: Context) {
            context.startService(
                Intent(context, RouteFollowingService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }
}
