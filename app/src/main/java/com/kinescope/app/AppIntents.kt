package com.kinescope.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** One explicit, reusable entry point for foreground-service notification taps. */
internal object AppIntents {
    const val ACTION_OPEN_HOME = "com.kinescope.app.ACTION_OPEN_HOME"
    private const val REQUEST_OPEN_HOME = 4201

    fun pendingOpenHome(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_HOME,
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_HOME
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
