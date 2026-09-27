package com.jameslongo.truerun

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Uploads finished runs: TCX to Strava, summary to Google Health.
 * A marker file (.strava / .google) records each success so nothing is sent twice.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val cfg = Config.load(applicationContext)
        val dir = File(applicationContext.filesDir, "runs")
        var failed = false
        for (tcx in dir.listFiles { f -> f.name.endsWith(".tcx") }.orEmpty().sortedBy { it.name }) {
            val base = tcx.path.removeSuffix(".tcx")
            val name = runCatching { JSONObject(File("$base.meta.json").readText()).getString("name") }.getOrDefault("Run")
            cfg.strava?.let { c ->
                if (!File("$base.strava").exists()) runCatching { uploadStrava(c, tcx, name) }
                    .onSuccess { File("$base.strava").writeText(it) }.onFailure { failed = true; log(it) }
            }
            cfg.google?.let { c ->
                if (!File("$base.google").exists()) runCatching { uploadGoogle(c, File("$base.health.json").readText()) }
                    .onSuccess { File("$base.google").writeText(it) }.onFailure { failed = true; log(it) }
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    private fun log(t: Throwable) = File(applicationContext.filesDir, "sync.log")
        .appendText("${java.time.Instant.now()} ${t.message}\n")

    private fun uploadStrava(c: Creds, tcx: File, name: String): String {
        val token = refresh("https://www.strava.com/oauth/token", c, "strava")
        val boundary = "----truerun${System.nanoTime()}"
        val conn = URL("https://www.strava.com/api/v3/uploads").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { out ->
            fun field(k: String, v: String) =
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
            field("data_type", "tcx")
            field("sport_type", "Run")
            field("name", name)
            field("external_id", tcx.name)
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${tcx.name}\"\r\nContent-Type: application/xml\r\n\r\n".toByteArray())
            out.write(tcx.readBytes())
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return read(conn)
    }

    private fun uploadGoogle(c: Creds, body: String): String {
        val token = refresh("https://oauth2.googleapis.com/token", c, "google")
        val conn = URL("https://health.googleapis.com/v4/users/me/dataTypes/exercise/dataPoints")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return read(conn)
    }

    /** Exchanges the refresh token; Strava rotates it, so the newest one is kept in tokens.json. */
    private fun refresh(url: String, c: Creds, key: String): String {
        val store = File(applicationContext.filesDir, "tokens.json")
        val saved = if (store.exists()) JSONObject(store.readText()) else JSONObject()
        val rt = saved.optString(key).ifEmpty { c.refreshToken }
        val form = mapOf(
            "client_id" to c.clientId, "client_secret" to c.clientSecret,
            "grant_type" to "refresh_token", "refresh_token" to rt,
        ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(form.toByteArray()) }
        val j = JSONObject(read(conn))
        j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.writeText(saved.put(key, it).toString()) }
        return j.getString("access_token")
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code >= 400) error("HTTP $code ${conn.url.host}: ${text.take(300)}")
        return text
    }

    companion object {
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
