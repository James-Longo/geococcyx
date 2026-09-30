package com.jameslongo.geococcyx

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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Uploads saved runs: TCX to Strava and to a Google Drive folder, summary to Google Health.
 * A marker file (.strava / .drive / .google) records each success so nothing is sent twice.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val cfg = Config.load(applicationContext)
        val dir = File(applicationContext.filesDir, "runs")
        var failed = false
        for (tcx in dir.listFiles { f -> f.name.endsWith(".tcx") }.orEmpty().sortedBy { it.name }) {
            // Deleted on the watch while this sync was running.
            if (!tcx.exists()) continue
            val base = tcx.path.removeSuffix(".tcx")
            val meta = runCatching { JSONObject(File("$base.meta.json").readText()) }.getOrDefault(JSONObject())
            val name = meta.optString("name", "Run")
            cfg.strava?.let { c ->
                if (!File("$base.strava").exists()) runCatching { uploadStrava(c, tcx, name) }
                    .onSuccess { File("$base.strava").writeText(it) }.onFailure { failed = true; log(it) }
            }
            cfg.google?.takeIf { cfg.drive }?.let { c ->
                val title = driveTitle(tcx.name, meta.optLong("start").takeIf { it > 0 }, meta.optString("tz").ifEmpty { null }, name)
                if (!File("$base.drive").exists()) runCatching { uploadDrive(c, tcx, title) }
                    .onSuccess { File("$base.drive").writeText(it) }.onFailure { failed = true; log(it) }
            }
            cfg.google?.takeIf { cfg.googleHealth }?.let { c ->
                if (!File("$base.google").exists()) runCatching { uploadGoogle(c, File("$base.health.json").readText()) }
                    .onSuccess { File("$base.google").writeText(it) }.onFailure { failed = true; log(it) }
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    private fun log(t: Throwable) = File(applicationContext.filesDir, "sync.log")
        .appendText("${java.time.Instant.now()} ${t.message}\n")

    private suspend fun uploadStrava(c: Creds, tcx: File, name: String): String {
        val token = refresh("https://www.strava.com/oauth/token", c, "strava")
        val boundary = "----geococcyx${System.nanoTime()}"
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
            // A plain run keeps Strava's own "Afternoon Run"-style name.
            if (name != "Run") field("name", name)
            field("external_id", tcx.name)
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${tcx.name}\"\r\nContent-Type: application/xml\r\n\r\n".toByteArray())
            out.write(tcx.readBytes())
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return awaitStrava(token, JSONObject(read(conn)).getLong("id"))
    }

    /** Strava processes uploads asynchronously; wait for the activity or its error. */
    private suspend fun awaitStrava(token: String, uploadId: Long): String {
        repeat(40) {
            val conn = URL("https://www.strava.com/api/v3/uploads/$uploadId").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            val j = JSONObject(read(conn))
            val err = j.optString("error").takeIf { it.isNotEmpty() && it != "null" }
            if (!j.isNull("activity_id")) return "activity ${j.getLong("activity_id")}"
            // Already on Strava (e.g. also synced by Fitbit): count as done, keep the reason.
            if (err != null && err.contains("duplicate", ignoreCase = true)) return "duplicate: $err"
            if (err != null) error("Strava: $err")
            kotlinx.coroutines.delay(3000)
        }
        error("Strava still processing upload $uploadId")
    }

    /** Puts the TCX in the Drive folder [DRIVE_FOLDER] as [title], making the folder on first use; returns the file's id. */
    private fun uploadDrive(c: Creds, tcx: File, title: String): String {
        val token = refresh("https://oauth2.googleapis.com/token", c, "google")
        val folder = driveFolder(token)
        val meta = JSONObject().put("name", title).put("parents", org.json.JSONArray().put(folder))
        val boundary = "----geococcyx${System.nanoTime()}"
        val conn = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            out.write("--$boundary\r\nContent-Type: application/vnd.garmin.tcx+xml\r\n\r\n".toByteArray())
            out.write(tcx.readBytes())
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return JSONObject(read(conn)).getString("id")
    }

    /** The app's Drive folder id: remembered in tokens.json, else found by name, else created. */
    private fun driveFolder(token: String): String {
        val store = File(applicationContext.filesDir, "tokens.json")
        val saved = if (store.exists()) JSONObject(store.readText()) else JSONObject()
        saved.optString("driveFolder").takeIf { it.isNotEmpty() }?.let { return it }
        val q = URLEncoder.encode("name = '$DRIVE_FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false", "UTF-8")
        val find = URL("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)").openConnection() as HttpURLConnection
        find.setRequestProperty("Authorization", "Bearer $token")
        val found = JSONObject(read(find)).getJSONArray("files").optJSONObject(0)?.getString("id")
        val id = found ?: run {
            val make = URL("https://www.googleapis.com/drive/v3/files?fields=id").openConnection() as HttpURLConnection
            make.requestMethod = "POST"
            make.doOutput = true
            make.setRequestProperty("Authorization", "Bearer $token")
            make.setRequestProperty("Content-Type", "application/json")
            make.outputStream.use { it.write(JSONObject().put("name", DRIVE_FOLDER).put("mimeType", FOLDER_MIME).toString().toByteArray()) }
            JSONObject(read(make)).getString("id")
        }
        store.writeText(saved.put("driveFolder", id).toString())
        return id
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
        private const val DRIVE_FOLDER = "Geococcyx"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/**
 * The Drive file name: "2026-09-30 10:36 Run.tcx", the start in local time where the run happened.
 * [startMs] and [tz] come from the run's meta.json; runs saved before those were recorded fall back
 * to the UTC start in the file name ("2026-09-30T143645.850Z.tcx") and this watch's current zone.
 */
fun driveTitle(fileName: String, startMs: Long?, tz: String?, name: String): String {
    val start = startMs?.let(Instant::ofEpochMilli) ?: fileName.take(22).let {
        Instant.parse("${it.take(13)}:${it.substring(13, 15)}:${it.substring(15)}")
    }
    val zone = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(start) + " $name.tcx"
}
