package com.stealth.vaultnotes

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            rescheduleActiveReminders(context)
            return
        }

        // Ignore legacy private-note alarms left by older app versions.
        if (intent.getBooleanExtra("privateNote", false)) return

        val title = intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: AppLanguage.text(context, "Note reminder", "تذكير الملاحظة")
        val preview = intent.getStringExtra("preview")?.takeIf { it.isNotBlank() }
            ?: AppLanguage.text(context, "You have a scheduled note reminder", "لديك تذكير ملاحظة مجدول")
        val noteId = intent.getStringExtra("noteId") ?: "0"

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "notes_reminder_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                AppLanguage.text(context, "Note reminders", "تذكيرات الملاحظات"),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("openNoteId", noteId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            noteId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(preview)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(noteId.hashCode(), notification)
    }

    private fun rescheduleActiveReminders(context: Context) {
        val now = System.currentTimeMillis()
        StorageRepository.loadPublicNotes(context).forEach { note ->
            val reminderAt = note.reminderTime
            if (!note.isTrashed && reminderAt != null && reminderAt > now) {
                scheduleReminder(context, note, reminderAt)
            } else {
                cancelReminder(context, note.id)
            }
        }
    }

    companion object {
        fun cancelReminder(context: Context, noteId: String) {
            val intent = Intent(context, ReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                noteId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
            // Also remove an already-posted reminder when its note is trashed or deleted.
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(noteId.hashCode())
        }

        fun scheduleReminder(
            context: Context,
            note: NoteItem,
            triggerAtMillis: Long
        ) {
            if (note.isSecret || triggerAtMillis <= System.currentTimeMillis()) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("title", note.title.ifBlank { AppLanguage.text(context, "Untitled note", "ملاحظة بلا عنوان") })
                putExtra("preview", note.plainTextPreview.take(80))
                putExtra("noteId", note.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                note.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }
    }
}