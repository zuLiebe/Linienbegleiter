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
import com.example.linienbegleiter.data.recording.RecordingManager
import com.example.linienbegleiter.data.recording.RecordingRepository
import com.example.linienbegleiter.data.recording.RecordingRuntimeState
import com.example.linienbegleiter.data.recording.RecordingStatus
import com.example.linienbegleiter.data.recording.RoomRecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteRecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: RecordingManager
    private var isForeground = false
    private val recordingBinder by lazy { RecordingBinder() }

    inner class RecordingBinder : Binder() {
        val recordingState
            get() = manager.recordingState

        suspend fun addStop(name: String) = manager.addStop(name)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val database = RecordingDatabase.getInstance(this)
        val repository: RecordingRepository = RoomRecordingRepository(database)
        manager = RecordingManager(
            repository = repository,
            locationProvider = AndroidLocationProvider(this),
            scope = serviceScope
        )
        serviceScope.launch {
            manager.recordingState.collect(::updateNotification)
        }
        serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                manager.refreshElapsedDuration()
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000L)
                manager.checkpointDuration()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground(manager.getRecordingState())
        if (!isForeground) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_START -> {
                if (RouteFollowingService.hasSavedFollowing(this)) {
                    updateNotification(
                        manager.getRecordingState().copy(
                            errorMessage = "Сначала остановите следование по маршруту."
                        )
                    )
                    stopService(startId)
                    return START_NOT_STICKY
                }
                val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
                if (recordingId.isNullOrBlank()) {
                    stopService(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch(Dispatchers.IO) {
                    runCatching { manager.startRecording(recordingId) }
                        .onFailure { error ->
                            withContext(Dispatchers.Main) {
                                updateNotification(
                                    manager.getRecordingState().copy(
                                        errorMessage = error.message
                                    )
                                )
                            }
                            stopService(startId)
                        }
                }
            }

            ACTION_PAUSE -> serviceScope.launch(Dispatchers.IO) {
                runCatching {
                    ensureRestored()
                    manager.pauseRecording()
                }.onFailure { publishError(it) }
            }

            ACTION_RESUME -> serviceScope.launch(Dispatchers.IO) {
                runCatching {
                    ensureRestored()
                    manager.resumeRecording()
                }.onFailure { publishError(it) }
            }

            ACTION_ADD_STOP -> serviceScope.launch(Dispatchers.IO) {
                runCatching {
                    ensureRestored()
                    manager.addStop(intent.getStringExtra(EXTRA_STOP_NAME).orEmpty())
                }.onFailure { publishError(it) }
            }

            ACTION_STOP -> serviceScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    ensureRestored()
                    manager.stopRecording()
                }
                if (result.isSuccess) {
                    withContext(Dispatchers.Main) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        isForeground = false
                        stopSelf(startId)
                    }
                } else {
                    publishError(result.exceptionOrNull()!!)
                }
            }

            ACTION_RESTORE, null -> serviceScope.launch(Dispatchers.IO) {
                val restored = manager.getRecordingState().session
                    ?: manager.restoreActiveRecording()
                if (restored == null) {
                    withContext(Dispatchers.Main) { stopService(startId) }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = recordingBinder

    override fun onDestroy() {
        manager.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun ensureRestored() {
        if (manager.getRecordingState().session == null) {
            manager.restoreActiveRecording()
        }
    }

    private suspend fun publishError(error: Throwable) {
        withContext(Dispatchers.Main) {
            updateNotification(
                manager.getRecordingState().copy(
                    errorMessage = error.message ?: "Не удалось обновить запись."
                )
            )
        }
    }

    private fun promoteToForeground(state: RecordingRuntimeState) {
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
            stopSelf()
        }
    }

    private fun updateNotification(state: RecordingRuntimeState) {
        if (!isForeground) return
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: RecordingRuntimeState): Notification {
        val session = state.session
        val title = session?.title?.takeIf(String::isNotBlank)
        val statusText = when {
            state.errorMessage != null -> state.errorMessage
            session?.status == RecordingStatus.PAUSED -> "Пауза"
            session?.status == RecordingStatus.RECORDING -> {
                "Идёт запись · " + formatDuration(state.durationSeconds)
            }
            else -> "Подготовка записи"
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(
                if (title == null) {
                    "Linienbegleiter — запись маршрута"
                } else {
                    "Linienbegleiter — " + title
                }
            )
            .setContentText(statusText)
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis() - state.durationSeconds * 1_000L)
            .setUsesChronometer(session?.status == RecordingStatus.RECORDING)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Запись маршрута",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает состояние текущей GPS-записи."
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

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remainingSeconds = seconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, remainingSeconds)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "route_recording"
        private const val NOTIFICATION_ID = 1407
        private const val EXTRA_RECORDING_ID =
            "com.example.linienbegleiter.extra.RECORDING_ID"
        private const val EXTRA_STOP_NAME =
            "com.example.linienbegleiter.extra.STOP_NAME"
        private const val ACTION_START = "com.example.linienbegleiter.action.START_RECORDING"
        private const val ACTION_RESTORE = "com.example.linienbegleiter.action.RESTORE_RECORDING"
        private const val ACTION_ADD_STOP = "com.example.linienbegleiter.action.ADD_STOP"
        private const val ACTION_PAUSE = "com.example.linienbegleiter.action.PAUSE_RECORDING"
        private const val ACTION_RESUME = "com.example.linienbegleiter.action.RESUME_RECORDING"
        private const val ACTION_STOP = "com.example.linienbegleiter.action.STOP_RECORDING"

        fun start(context: Context, recordingId: String) {
            check(!RouteFollowingService.hasSavedFollowing(context)) {
                "Сначала остановите следование по маршруту."
            }
            val intent = Intent(context, RouteRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RECORDING_ID, recordingId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun restore(context: Context) {
            val intent = Intent(context, RouteRecordingService::class.java).apply {
                action = ACTION_RESTORE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) = sendAction(context, ACTION_PAUSE)

        fun resume(context: Context) = sendAction(context, ACTION_RESUME)

        fun stop(context: Context) = sendAction(context, ACTION_STOP)

        fun addStop(context: Context, name: String) {
            context.startService(
                Intent(context, RouteRecordingService::class.java).apply {
                    action = ACTION_ADD_STOP
                    putExtra(EXTRA_STOP_NAME, name)
                }
            )
        }

        private fun sendAction(context: Context, action: String) {
            context.startService(
                Intent(context, RouteRecordingService::class.java).apply {
                    this.action = action
                }
            )
        }
    }
}
