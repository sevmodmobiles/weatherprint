package ru.home.weatherprint

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay

/**
 * Серия прочисток головки подряд в фоне: прочистка → ждём, пока принтер закончит → следующая.
 * Пока идёт — уведомление с ходом работы, в конце — уведомление об окончании.
 * По желанию после серии печатается проверка дюз.
 */
class CleaningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK = "head-cleaning"
        private const val CHANNEL = "maintenance"
        private const val ID_PROGRESS = 3
        private const val ID_DONE = 4

        fun start(ctx: Context, times: Int, nozzleCheck: Boolean) {
            val req = OneTimeWorkRequestBuilder<CleaningWorker>()
                .setInputData(workDataOf("times" to times, "check" to nozzleCheck, "pid" to Printers.active(ctx)))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    override suspend fun doWork(): Result {
        val times = inputData.getInt("times", 1).coerceIn(1, 5)
        val check = inputData.getBoolean("check", false)
        val p = Prefs(applicationContext, inputData.getString("pid"))
        val lan = LocalNet.route(applicationContext)
        val epson = EscprClient(p.printerHost, lan)
        var confirmed = true

        try {
            for (i in 1..times) {
                progress("Прочистка $i из $times", "Не выключайте принтер", i, times)
                epson.headCleaning()
                confirmed = PrinterStatus.waitUntilDone(p.printerHost, lan) && confirmed
                if (i < times) {
                    progress("Прочистка $i из $times завершена", "Следующая через несколько секунд", i, times)
                    delay(10_000)
                }
            }
            if (check) {
                progress("Печатаю проверку дюз", "Шаблон из линий каждого цвета", times, times)
                epson.nozzleCheck()
            }
        } catch (e: Exception) {
            if (isStopped) return Result.failure()
            done("Прочистка прервана", "Принтер не ответил: ${e.message}")
            return Result.failure()
        }

        val what = if (times == 1) "Прочистка завершена" else "Прочистка завершена ($times раза подряд)"
        val note = buildString {
            append(if (check) "Проверка дюз напечатана — посмотрите, нет ли разрывов в линиях."
            else "Сделайте проверку дюз, чтобы убедиться, что разрывов нет.")
            if (!confirmed) append(" Принтер не сообщает своё состояние, поэтому окончание определено по времени.")
        }
        done(what, note)
        return Result.success()
    }

    private suspend fun progress(title: String, text: String, i: Int, n: Int) {
        setProgress(workDataOf("title" to title))
        setForeground(foregroundInfo(title, text, i, n))
    }

    override suspend fun getForegroundInfo() = foregroundInfo("Прочистка печатающей головки", "Начинаю…", 0, 1)

    private fun foregroundInfo(title: String, text: String, i: Int, n: Int): ForegroundInfo {
        channel()
        val nn = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(n, i, i == 0)
            .setContentIntent(openApp())
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ID_PROGRESS, nn, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ID_PROGRESS, nn)
    }

    private fun done(title: String, text: String) {
        channel()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(ID_DONE, n)
        } catch (_: SecurityException) {
        }
    }

    private fun channel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обслуживание принтера", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp() = PendingIntent.getActivity(
        applicationContext, 1, Intent(applicationContext, PrinterActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
}
