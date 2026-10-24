package com.dietcode.diplo.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dietcode.diplo.MainActivity

/**
 * While Google ARTEMIS drives other apps, Diplo is behind them: its Stop button is out of reach
 * and the mic isn't listening for "ruko". This notification keeps "रोकें" one swipe away from any
 * app, for as long as a phone task runs.
 */
class StopNotification(private val context: Context, onStop: () -> Unit) {

    private val manager = NotificationManagerCompat.from(context)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = onStop()
    }

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("फ़ोन पर चल रहा काम")
                .build(),
        )
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // Without the notification permission (Android 13+, asked in onboarding) there is just no
    // notification; the in-app Stop button and "ruko" still work.
    @SuppressLint("MissingPermission")
    fun show(task: String) {
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_STOP).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Diplo फ़ोन चला रहा है")
            .setContentText(task)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "रोकें", stop)
            .build()
        try {
            manager.notify(ID, notification)
        } catch (e: SecurityException) {
            // permission not granted
        }
    }

    fun hide() = manager.cancel(ID)

    fun destroy() {
        hide()
        context.unregisterReceiver(receiver)
    }

    private companion object {
        const val CHANNEL = "phone_task"
        const val ID = 1
        const val ACTION_STOP = "com.dietcode.diplo.STOP_PHONE_TASK"
    }
}
