package ru.home.weatherprint

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Печать по расписанию: точный будильник на выбранное время каждый день.
 * Если в этот момент принтер недоступен (телефон не дома), печать доберёт
 * периодическая проверка раз в ~30 минут — до конца дня.
 */
object PrintSchedule {

    private const val REQUEST = 42

    fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** Ближайший момент печати; null — расписание выключено. */
    fun next(ctx: Context): LocalDateTime? {
        val p = Prefs(ctx)
        if (!p.scheduleEnabled) return null
        val at = LocalTime.of(p.printMinutes / 60, p.printMinutes % 60)
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        val printedToday = p.lastPrintDate == today.toString()
        val t = today.atTime(at)
        return if (t.isAfter(now) && !printedToday) t else today.plusDays(1).atTime(at)
    }

    /** Для экрана: «сегодня в 17:00», «завтра в 17:00», «выключена». */
    fun describe(ctx: Context): String {
        val n = next(ctx) ?: return "выключена"
        val day = when (n.toLocalDate()) {
            LocalDate.now() -> "сегодня"
            LocalDate.now().plusDays(1) -> "завтра"
            else -> n.format(DateTimeFormatter.ofPattern("dd.MM"))
        }
        return "$day в ${n.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    /** Ставит (или снимает) будильник на ближайшую печать. */
    fun arm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, REQUEST, Intent(ctx, PrintAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pi)
        val n = next(ctx) ?: return
        val at = n.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Запускает попытку печати прямо сейчас (в фоне, как только есть сеть). */
    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<PrintWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("scheduled-print", ExistingWorkPolicy.REPLACE, req)
    }
}

/** Будильник сработал: печатаем и ставим будильник на завтра. */
class PrintAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PrintSchedule.runNow(ctx)
        PrintSchedule.arm(ctx)
    }
}

/** После перезагрузки телефона и обновления приложения будильники сбрасываются — ставим заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PrintSchedule.arm(ctx)
        PrintJob.schedule(ctx)
    }
}
