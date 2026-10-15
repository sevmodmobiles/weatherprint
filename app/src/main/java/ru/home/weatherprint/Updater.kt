package ru.home.weatherprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Самообновление из релизов GitHub.
 * GitHub Actions публикует каждую сборку как релиз v1.N, где N = versionCode.
 */
object Updater {
    private const val REPO = "sevmodmobiles/weatherprint"

    data class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String)

    fun currentVersionCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Возвращает релиз, если он новее установленной версии, иначе null. */
    fun checkForUpdate(ctx: Context): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) error("GitHub HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")                     // "v1.7"
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            val rel = Release(
                versionCode = code,
                name = tag.removePrefix("v"),
                notes = json.optString("body", "").trim(),
                apkUrl = apk.getString("browser_download_url"),
            )
            return if (rel.versionCode > currentVersionCode(ctx)) rel else null
        } finally {
            conn.disconnect()
        }
    }

    /** Скачивает APK во внутренний кэш. onProgress получает 0..100. */
    fun download(ctx: Context, rel: Release, onProgress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update.apk")
        var url = URL(rel.apkUrl)
        var conn: HttpURLConnection
        // GitHub отдаёт файл через перенаправление на другой домен — проходим его вручную.
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                hops++
                continue
            }
            if (code != 200) error("HTTP $code при скачивании")
            break
        }
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                    if (total > 0 && done != total) error("Файл скачан не полностью ($done из $total)")
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** Есть ли у приложения право ставить APK; если нет — открывает нужный экран настроек. */
    fun ensureInstallPermission(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }

    /** Ставит скачанный APK поверх текущей версии через PackageInstaller. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Начиная со второго самообновления Android может ставить без вопроса.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                ctx, sessionId, Intent(ctx, InstallReceiver::class.java), flags
            )
            session.commit(pi.intentSender)
        }
    }
}

/** Получает статус установки; если Android просит подтверждение — показывает его. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // приложение перезапустится в новой версии
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка"
                Toast.makeText(context, "Обновление не установлено: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
