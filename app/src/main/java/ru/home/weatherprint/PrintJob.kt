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
import androidx.work.ForegroundInfo
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
    suspend fun run(ctx: Context, force: Boolean): Pair<Outcome, String> {
        val p = Prefs(ctx)
        val today = LocalDate.now().toString()

        val paper = Paper(ctx)
        val quarter = paper.next ?: return Outcome.SKIPPED to "Ждём перекладки бумаги"
        if (!force) {
            if (p.lastPrintDate == today) return Outcome.SKIPPED to "Сегодня уже печатали"
            if (!p.scheduleEnabled) return Outcome.SKIPPED to "Печать по расписанию выключена"
            val now = LocalTime.now()
            if (now.hour * 60 + now.minute < p.printMinutes) return Outcome.SKIPPED to "Ещё рано"
        }

        val lan = LocalNet.route(ctx)

        /** Принтер отвечает — значит, телефон дома в той же сети. */
        fun ping() {
            if (p.protocol == "escpr") EscprClient(p.printerHost, lan).ping()
            else IppClient(p.printerHost, p.ippPath, lan).getAttributes(timeoutMs = 3000)
        }

        try {
            ping()
        } catch (e: Exception) {
            // Возможно, роутер выдал принтеру новый IP — ищем его по имени.
            val again = if (p.printerName.isNotEmpty()) PrinterDiscovery.findByName(ctx, p.printerName) else null
            if (again == null) return Outcome.SKIPPED to "Принтер не найден в сети (${e.message})"
            p.printerHost = again.host
            p.ippPath = again.path
            p.protocol = again.protocol
            try {
                ping()
            } catch (e2: Exception) {
                return Outcome.SKIPPED to "Принтер не отвечает (${e2.message})"
            }
        }

        val weather = try {
            WeatherApi.fetch(p.lat, p.lon)
        } catch (e: Exception) {
            return Outcome.FAILED to "Не удалось получить погоду: ${e.message}"
        }

        val footer = footerText(paper)
        try {
            if (p.protocol == "escpr") {
                val q = PageRenderer.renderQuarter(weather, quarter % 4, p.city, footer)
                try {
                    EscprClient(p.printerHost, lan).print(q.bitmap, q.x, q.y)
                } finally {
                    q.bitmap.recycle()
                }
            } else {
                val jpeg = PageRenderer.renderJpeg(weather, quarter % 4, p.city, footer)
                IppClient(p.printerHost, p.ippPath, lan).printJob(jpeg, "image/jpeg", "Погода $today")
            }
        } catch (e: Exception) {
            val msg = "Ошибка печати: ${e.message}"
            p.lastResult = msg
            return Outcome.FAILED to msg
        }

        // Успех — двигаем счётчик
        p.lastPrintDate = today
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        p.lastResult = "Напечатано $today в $time ($footer)"
        paper.printed()
        if (paper.awaitingReload) {
            val (title, text) = paper.reloadNotice()
            notify(ctx, title, text)
        }
        return Outcome.PRINTED to p.lastResult
    }

    /** Подпись внизу прогноза: какая четверть и сколько листов ещё в лотке. */
    fun footerText(paper: Paper): String {
        val q = paper.next ?: return ""
        val side = if (q >= 4) "оборот" else "лицо"
        return "Четверть ${q + 1}/8 ($side) · в лотке ${paper.tray.size} л."
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
    /** Нужен только на Android до 12: там срочная работа выполняется как служба с уведомлением. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("work", "Печать", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "work")
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("Печатаю прогноз погоды")
            .build()
        return ForegroundInfo(2, n)
    }

    override suspend fun doWork(): Result {
        PrintJob.run(applicationContext, force = false)
        return Result.success()
    }
}
