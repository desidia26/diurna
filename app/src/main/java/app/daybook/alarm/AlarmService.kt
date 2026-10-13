package app.daybook.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.daybook.R
import app.daybook.domain.AlarmRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Rings until told to stop. Only [AlarmActivity] sends [ACTION_STOP], after a porch-code
 * scan (or the stop button on a test alarm). Gives up after [MAX_RING_MS] so a forgotten
 * trip doesn't mean a phone ringing all day.
 */
class AlarmService : Service() {
    data class Ring(val date: LocalDate?, val test: Boolean)

    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_TEST = "test"
        const val ACTION_STOP = "app.daybook.STOP_ALARM"
        private const val CHANNEL = "alarm"
        private const val NOTIFICATION_ID = 1
        private val MAX_RING_MS = AlarmRules.RING_MINUTES * 60_000L

        private val _ringing = MutableStateFlow<Ring?>(null)
        val ringing: StateFlow<Ring?> = _ringing
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ring = Ring(
            date = intent?.getStringExtra(EXTRA_DATE)?.let(LocalDate::parse),
            test = intent?.getBooleanExtra(EXTRA_TEST, false) ?: false,
        )
        _ringing.value = ring
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (player == null) startSoundAndVibration()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, MAX_RING_MS)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the service plays the sound itself
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_day)
            .setContentTitle("Time to get up")
            .setContentText("Scan your porch code to stop the alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
    }

    private fun startSoundAndVibration() {
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val floor = (max * 0.8).toInt()
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < floor) {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0)
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@AlarmService, uri)
            isLooping = true
            prepare()
            start()
        }
        vibrator = getSystemService(Vibrator::class.java)?.also {
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.run { stop(); release() }
        player = null
        vibrator?.cancel()
        _ringing.value = null
        super.onDestroy()
    }
}
