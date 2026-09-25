package com.cyclone.mobile.runtime.background

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.LruCache
import com.cyclone.mobile.R

/** Native task card shared by display-0 and workspace execution. System UI owns its shape. */
internal object TaskProgressNotification {
    // Public Notification.EXTRA_REQUEST_PROMOTED_ONGOING wire key (API 36.1).
    // A Bundle keeps this request compatible with our API 35 compile baseline and older devices.
    private const val REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private val appIcons = LruCache<String, Icon>(8)

    fun build(context: Context, channel: String, task: WorkspaceTaskUi): Notification {
        val progress = PendingIntent.getActivity(context, 0, WorkspaceTasks.progressIntent(context, task),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = TaskNotificationProjection.body(task)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_cyclone_status)
            .setColor(Color.rgb(141, 227, 210))
            .setContentTitle(TaskNotificationProjection.title(task))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(progress)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_cyclone_status)
                .setContentTitle("Cyclone task")
                .setContentText("Unlock to view task progress").build())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(task.working)
            .setAutoCancel(!task.working)
            .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, task.working) })
        appIcon(context, task.packageName)?.let { builder.setLargeIcon(it) }
        if (task.working) {
            val percent = TaskNotificationProjection.progressPercent(task)
            builder.setProgress(100, percent ?: 0, percent == null)
        }
        // Preserve every available interruption/confirmation command. The card itself always opens
        // exact-task details; running tasks have Stop task followed by the explicit View progress.
        TaskNotificationProjection.actions(task).forEach { (command, label) ->
            val parsed = com.cyclone.mobile.task.TaskCommand.parse(command, task.confirmation?.token) ?: return@forEach
            val action = com.cyclone.mobile.task.TaskCommands.pendingIntent(context, task, parsed)
            builder.addAction(Notification.Action.Builder(null, label, action).build())
        }
        if (TaskNotificationProjection.actions(task).size < 3) {
            builder.addAction(Notification.Action.Builder(null, "View progress", progress).build())
        }
        return builder.build()
    }

    private fun appIcon(context: Context, packageName: String): Icon? = appIcons.get(packageName) ?: runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName).mutate()
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        Icon.createWithBitmap(bitmap).also { appIcons.put(packageName, it) }
    }.getOrNull()
}
