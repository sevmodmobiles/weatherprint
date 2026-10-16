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
     * Попытка печати.
     *
     * По расписанию ([force] = false) — на тот из своих принтеров, что сейчас рядом (отвечает и проходит
     * проверку уникального номера) и сегодня ещё не печатал. «Напечатать сейчас» — только на активный.
     */
    suspend fun run(ctx: Context, force: Boolean): Pair<Outcome, String> {
        val g = Prefs(ctx)
        val today = LocalDate.now().toString()
        if (!force) {
            if (!g.scheduleEnabled) return Outcome.SKIPPED to "Печать по расписанию выключена"
            val now = LocalTime.now()
            if (now.hour * 60 + now.minute < g.printMinutes) return Outcome.SKIPPED to "Ещё рано"
        }

        val candidates = if (force) listOf(Printers.active(ctx)).filter { Prefs(ctx, it).printerName.isNotEmpty() }
        else Printers.configured(ctx).filter { Prefs(ctx, it).lastPrintDate != today }
        if (candidates.isEmpty()) {
            return Outcome.SKIPPED to if (Printers.configured(ctx).isEmpty()) "Принтер не выбран" else "Сегодня уже печатали"
        }

        val lan = LocalNet.route(ctx)
        var target: String? = null
        var reason = "Принтер не найден в сети"
        for (id in candidates) {
            val r = reach(ctx, id, lan)
            if (r == null) { target = id; break }
            reason = r
        }
        // Возможно, роутер выдал принтеру новый IP — ищем его в сети по уникальному номеру
        if (target == null && LocalNet.wifi(ctx) != null) target = rediscover(ctx, candidates, lan)
        target ?: return Outcome.SKIPPED to reason

        return printOn(ctx, target, lan)
    }

    /** null — принтер на месте и это действительно он; иначе — причина, почему печатать нельзя. */
    fun reach(ctx: Context, id: String, lan: android.net.Network?): String? {
        val p = Prefs(ctx, id)
        try {
            if (p.protocol == "escpr") EscprClient(p.printerHost, lan).ping()
            else IppClient(p.printerHost, p.ippPath, lan).getAttributes(timeoutMs = 3000)
        } catch (e: Exception) {
            return "Принтер не найден в сети"
        }
        // По тому же адресу может оказаться чужой принтер — в гостях, в офисе
        if (PrinterInfo.verify(p, lan) == false) return "По адресу ${p.printerHost} другой принтер — печать пропущена"
        return null
    }

    /** Ищет свои принтеры в текущей сети по уникальному номеру; найденному обновляет адрес. */
    private suspend fun rediscover(ctx: Context, candidates: List<String>, lan: android.net.Network?): String? {
        val withId = candidates.filter { Prefs(ctx, it).printerIdentity.isNotEmpty() }
        if (withId.isEmpty()) return null
        val found = runCatching { PrinterDiscovery.discover(ctx, seconds = 4).found }.getOrDefault(emptyList())
        for (f in found) {
            val identity = PrinterInfo.readIdentity(f.host, f.protocol, f.path, lan) ?: continue
            val id = withId.firstOrNull { Prefs(ctx, it).printerIdentity == identity } ?: continue
            val p = Prefs(ctx, id)
            p.printerHost = f.host
            p.ippPath = f.path
            p.protocol = f.protocol
            return id
        }
        return null
    }

    private fun printOn(ctx: Context, id: String, lan: android.net.Network?): Pair<Outcome, String> {
        val p = Prefs(ctx, id)
        val today = LocalDate.now().toString()
        val paper = Paper(ctx, id)
        val quarter = paper.next ?: return Outcome.SKIPPED to "Ждём перекладки бумаги"
        // Печатаем на этом принтере — он и становится активным на экранах
        Printers.setActive(ctx, id)

        val weather = try {
            WeatherApi.fetch(p.lat, p.lon)
        } catch (e: Exception) {
            return Outcome.FAILED to "Не удалось получить погоду: ${e.message}"
        }

        val footer = footerText(paper)
        val phrase = Phrases.forDay(weather, p.usedPhrases)
        try {
            if (p.protocol == "escpr") {
                val q = PageRenderer.renderQuarter(weather, quarter % 4, p.city, footer, phrase)
                try {
                    EscprClient(p.printerHost, lan).print(q.bitmap, q.x, q.y)
                } finally {
                    q.bitmap.recycle()
                }
            } else {
                val jpeg = PageRenderer.renderJpeg(weather, quarter % 4, p.city, footer, phrase)
                IppClient(p.printerHost, p.ippPath, lan).printJob(jpeg, "image/jpeg", "Погода $today")
            }
        } catch (e: Exception) {
            val msg = "Ошибка печати: ${e.message}"
            p.lastResult = msg
            return Outcome.FAILED to msg
        }

        // Успех — двигаем счётчик этого принтера
        p.lastPrintDate = today
        p.lastPrintAt = java.time.LocalDateTime.now().withSecond(0).withNano(0).toString().take(16)
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        p.lastResult = "Напечатано $today в $time ($footer)"
        paper.printed()
        if (Phrases.isWeather(phrase)) p.usedPhrases = p.usedPhrases + phrase
        if (paper.awaitingReload) {
            val (title, text) = paper.reloadNotice()
            val many = Printers.configured(ctx).size > 1
            notify(ctx, if (many) "$title — ${p.displayName}" else title, text)
        }
        val where = if (Printers.configured(ctx).size > 1) " на «${p.displayName}»" else ""
        return Outcome.PRINTED to "Напечатано$where в $time"
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
