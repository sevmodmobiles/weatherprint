package ru.home.weatherprint

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Вся логика одной попытки печати. */
object PrintJob {

    enum class Outcome { PRINTED, SKIPPED, FAILED }

    /**
     * @param force true — «напечатать сейчас» из приложения: игнорирует дату и час,
     *              но счётчик бумаги учитывает так же.
     */
    fun run(ctx: Context, force: Boolean): Pair<Outcome, String> {
        val p = Prefs(ctx)
        val today = LocalDate.now().toString()

        if (p.awaitingReload) return Outcome.SKIPPED to "Ждём перекладки бумаги"
        if (!force) {
            if (p.lastPrintDate == today) return Outcome.SKIPPED to "Сегодня уже печатали"
            if (LocalTime.now().hour < p.fromHour) return Outcome.SKIPPED to "Ещё рано"
        }

        val ipp = IppClient(p.printerHost, p.ippPath)

        // Принтер отвечает — значит, телефон дома в той же сети.
        try {
            ipp.getAttributes(timeoutMs = 3000)
        } catch (e: Exception) {
            return Outcome.SKIPPED to "Принтер не найден в сети (${e.message})"
        }

        val weather = try {
            WeatherApi.fetch(p.lat, p.lon)
        } catch (e: Exception) {
            return Outcome.FAILED to "Не удалось получить погоду: ${e.message}"
        }

        val side = if (p.backSide) "оборот" else "лицо"
        val footer = "Лист ${p.sheetIndex + 1}/${p.sheets} · проход ${p.pass + 1}/8 ($side)"
        val jpeg = PageRenderer.renderJpeg(weather, p.quadrant, p.city, footer)

        try {
            ipp.printJob(jpeg, "image/jpeg", "Погода $today")
        } catch (e: Exception) {
            val msg = "Ошибка печати: ${e.message}"
            p.lastResult = msg
            return Outcome.FAILED to msg
        }

        // Успех — двигаем счётчик
        p.lastPrintDate = today
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        p.lastResult = "Напечатано $today в $time ($footer)"
        p.sheetIndex = p.sheetIndex + 1
        if (p.sheetIndex >= p.sheets) {
            p.sheetIndex = 0
            p.pass = p.pass + 1
            p.awaitingReload = true
            when {
                p.pass >= 8 -> {
                    p.pass = 0
                    notify(ctx, "Бумага израсходована полностью",
                        "Все ${p.sheets} листов исписаны с двух сторон. Заложите новую пачку и нажмите «Бумага заложена».")
                }
                p.pass == 4 -> notify(ctx, "Переверните пачку",
                    "Лицевая сторона заполнена. Достаньте листы, переверните пачку чистой стороной и нажмите «Бумага заложена».")
                else -> notify(ctx, "Положите листы обратно",
                    "Проход ${p.pass} из 8 завершён. Достаньте листы из выходного лотка и положите обратно той же стороной, не поворачивая.")
            }
        }
        return Outcome.PRINTED to p.lastResult
    }

    private const val CHANNEL = "paper"

    fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Бумага в принтере", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1, n)
        } catch (_: SecurityException) {
        }
    }

    /** Проверка каждые 30 минут, только по Wi-Fi. Печать — не чаще раза в день. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PrintWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork("daily-weather-print", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class PrintWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        PrintJob.run(applicationContext, force = false)
        return Result.success()
    }
}
