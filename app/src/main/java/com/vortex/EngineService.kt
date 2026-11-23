package com.vortex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vortex.audio.AudioEngine
import com.vortex.net.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps audio analysis and the Wi-Fi server alive on the operator phone, even if the screen is dimmed
 * or the app is in the background. The controller app does not need it.
 */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("vortex", "VORTEX", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, "vortex")
            .setContentTitle("VORTEX is running")
            .setContentText("Analyzing audio and linked to VORTEX E over Wi-Fi")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        Link.init(this)
        Link.startOperator()
        AudioEngine.start(this, scope)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        AudioEngine.stop()
        Link.stopOperator()
        scope.cancel()
        super.onDestroy()
    }
}
