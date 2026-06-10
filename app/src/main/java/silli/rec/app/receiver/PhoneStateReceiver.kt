package silli.rec.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import silli.rec.app.service.AudioRecordingService

class PhoneStateReceiver : BroadcastReceiver() {

    private var wasRecording = false

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (context == null) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val service = AudioRecordingService.getInstance()

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Phone call incoming or active
                if (service?.isCurrentlyRecording() == true) {
                    wasRecording = true
                    service.pauseRecording()
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Phone call ended
                if (wasRecording && service?.isCurrentlyRecording() == true) {
                    service.resumeRecording()
                    wasRecording = false
                }
            }
        }
    }
}
