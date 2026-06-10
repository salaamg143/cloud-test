package silli.rec.app.service

import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import silli.rec.app.R
import silli.rec.app.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class AudioRecordingService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "silli_rec_channel"
        const val ACTION_TOGGLE_RECORDING = "silli.rec.app.TOGGLE_RECORDING"
        const val ACTION_STOP_RECORDING = "silli.rec.app.STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "silli.rec.app.PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "silli.rec.app.RESUME_RECORDING"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_ACTION = "action"

        private var instance: AudioRecordingService? = null
        fun getInstance(): AudioRecordingService? = instance
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recordingListeners = mutableListOf<RecordingStateListener>()
    private val timerListeners = mutableListOf<TimerListener>()

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private var isRecording = false
    private var isPaused = false
    private var recordingStartTime = 0L
    private var pausedDuration = 0L
    private var lastPauseTime = 0L

    private var currentSource = AudioSource.MIC
    private var currentFormat = AudioFormat.M4A

    private var outputFile: File? = null
    private var recordingDirPath = ""

    enum class AudioSource {
        MIC, INTERNAL, BOTH
    }

    enum class AudioFormat {
        M4A, WAV
    }

    interface RecordingStateListener {
        fun onRecordingStarted()
        fun onRecordingPaused()
        fun onRecordingResumed()
        fun onRecordingStopped(filePath: String)
        fun onRecordingError(error: String)
    }

    interface TimerListener {
        fun onTimerTick(elapsedMs: Long)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        recordingDirPath = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_TOGGLE_RECORDING -> {
                    if (isRecording) {
                        stopRecording()
                    } else {
                        currentSource = AudioSource.valueOf(it.getStringExtra(EXTRA_SOURCE) ?: "MIC")
                        currentFormat = AudioFormat.valueOf(it.getStringExtra(EXTRA_FORMAT) ?: "M4A")
                        startRecording()
                    }
                }
                ACTION_STOP_RECORDING -> stopRecording()
                ACTION_PAUSE_RECORDING -> pauseRecording()
                ACTION_RESUME_RECORDING -> resumeRecording()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SilliRec Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording status and controls"
                setShowBadge(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startRecording() {
        if (isRecording) return

        isRecording = true
        isPaused = false
        recordingStartTime = System.currentTimeMillis()
        pausedDuration = 0L

        outputFile = createOutputFile()
        if (outputFile == null) {
            notifyError("Failed to create output file")
            return
        }

        showForegroundNotification()
        notifyRecordingStarted()
        startTimerTick()

        recordingThread = thread(start = true) {
            try {
                when (currentSource) {
                    AudioSource.MIC -> recordFromMicrophone()
                    AudioSource.INTERNAL -> recordInternalAudio()
                    AudioSource.BOTH -> recordBothAudioSources()
                }
            } catch (e: Exception) {
                notifyError("Recording error: ${e.message}")
            } finally {
                cleanup()
            }
        }
    }

    private fun recordFromMicrophone() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            44100,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            44100,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        audioRecord?.startRecording()

        val buffer = ByteArray(minBufferSize)
        val outputStream = outputFile?.outputStream()

        try {
            while (isRecording) {
                if (!isPaused) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        outputStream?.write(buffer, 0, bytesRead)
                    }
                } else {
                    Thread.sleep(100)
                }
            }
        } finally {
            outputStream?.close()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun recordInternalAudio() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            notifyError("Internal audio recording requires Android 10+")
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // In production, this would be obtained from Intent in MainActivity
        // For now, we'll handle internal audio through AudioRecord with REMOTE_SUBMIX

        val minBufferSize = AudioRecord.getMinBufferSize(
            44100,
            android.media.AudioFormat.CHANNEL_IN_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.REMOTE_SUBMIX,
            44100,
            android.media.AudioFormat.CHANNEL_IN_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        audioRecord?.startRecording()

        val buffer = ByteArray(minBufferSize)
        val outputStream = outputFile?.outputStream()

        try {
            while (isRecording) {
                if (!isPaused) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        outputStream?.write(buffer, 0, bytesRead)
                    }
                } else {
                    Thread.sleep(100)
                }
            }
        } finally {
            outputStream?.close()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun recordBothAudioSources() {
        // Record both microphone and internal audio
        val minBufferSize = AudioRecord.getMinBufferSize(
            44100,
            android.media.AudioFormat.CHANNEL_IN_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            44100,
            android.media.AudioFormat.CHANNEL_IN_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        audioRecord?.startRecording()

        val buffer = ByteArray(minBufferSize)
        val outputStream = outputFile?.outputStream()

        try {
            while (isRecording) {
                if (!isPaused) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        outputStream?.write(buffer, 0, bytesRead)
                    }
                } else {
                    Thread.sleep(100)
                }
            }
        } finally {
            outputStream?.close()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        isPaused = true
        lastPauseTime = System.currentTimeMillis()
        notifyRecordingPaused()
        updateNotification()
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        pausedDuration += System.currentTimeMillis() - lastPauseTime
        isPaused = false
        notifyRecordingResumed()
        updateNotification()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        isPaused = false

        recordingThread?.join(5000)
        recordingThread = null

        val filePath = outputFile?.absolutePath ?: ""
        notifyRecordingStopped(filePath)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createOutputFile(): File? {
        return try {
            val dir = File(recordingDirPath)
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val extension = if (currentFormat == AudioFormat.M4A) "m4a" else "wav"
            File(dir, "recording_$timestamp.$extension")
        } catch (e: Exception) {
            null
        }
    }

    private fun getElapsedTime(): Long {
        if (!isRecording) return 0
        val elapsed = System.currentTimeMillis() - recordingStartTime - pausedDuration
        return if (isPaused) elapsed - (System.currentTimeMillis() - lastPauseTime) else elapsed
    }

    private fun startTimerTick() {
        handler.post(object : Runnable {
            override fun run() {
                if (isRecording) {
                    notifyTimerTick(getElapsedTime())
                    updateNotification()
                    handler.postDelayed(this, 100)
                }
            }
        })
    }

    private fun showForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): android.app.Notification {
        val elapsedMs = getElapsedTime()
        val hours = elapsedMs / 3600000
        val minutes = (elapsedMs % 3600000) / 60000
        val seconds = (elapsedMs % 60000) / 1000
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AudioRecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val pauseIntent = if (isPaused) {
            PendingIntent.getService(
                this,
                1,
                Intent(this, AudioRecordingService::class.java).apply {
                    action = ACTION_RESUME_RECORDING
                },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
        } else {
            PendingIntent.getService(
                this,
                1,
                Intent(this, AudioRecordingService::class.java).apply {
                    action = ACTION_PAUSE_RECORDING
                },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SilliRec")
            .setContentText("Recording: $timeString")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .addAction(0, if (isPaused) "Resume" else "Pause", pauseIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun cleanup() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun notifyRecordingStarted() {
        handler.post {
            recordingListeners.forEach { it.onRecordingStarted() }
            broadcastRecordingState("started")
        }
    }

    private fun notifyRecordingPaused() {
        handler.post {
            recordingListeners.forEach { it.onRecordingPaused() }
            broadcastRecordingState("paused")
        }
    }

    private fun notifyRecordingResumed() {
        handler.post {
            recordingListeners.forEach { it.onRecordingResumed() }
            broadcastRecordingState("resumed")
        }
    }

    private fun notifyRecordingStopped(filePath: String) {
        handler.post {
            recordingListeners.forEach { it.onRecordingStopped(filePath) }
            broadcastRecordingState("stopped")
        }
    }

    private fun notifyError(error: String) {
        handler.post {
            recordingListeners.forEach { it.onRecordingError(error) }
            isRecording = false
        }
    }

    private fun notifyTimerTick(elapsedMs: Long) {
        timerListeners.forEach { it.onTimerTick(elapsedMs) }
    }

    private fun broadcastRecordingState(state: String) {
        sendBroadcast(Intent("silli.rec.app.RECORDING_STATE_CHANGED").apply {
            putExtra("state", state)
            putExtra("isRecording", isRecording)
            putExtra("isPaused", isPaused)
        })
    }

    fun registerRecordingListener(listener: RecordingStateListener) {
        if (!recordingListeners.contains(listener)) {
            recordingListeners.add(listener)
        }
    }

    fun unregisterRecordingListener(listener: RecordingStateListener) {
        recordingListeners.remove(listener)
    }

    fun registerTimerListener(listener: TimerListener) {
        if (!timerListeners.contains(listener)) {
            timerListeners.add(listener)
        }
    }

    fun unregisterTimerListener(listener: TimerListener) {
        timerListeners.remove(listener)
    }

    fun isCurrentlyRecording(): Boolean = isRecording
    fun isCurrentlyPaused(): Boolean = isPaused
    fun getRecordingDuration(): Long = getElapsedTime()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        instance = null
    }
}
