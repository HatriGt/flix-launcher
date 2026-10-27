package com.flix.launcher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves every Flix setting (look, clock, wallpaper, favourites and their order, hidden apps
 * and titles, addon rows) to one small JSON file, and restores it.
 *
 * Backups go to Flix's own folder on the TV's storage (Android/data/com.flix.launcher/files/
 * backups), which adb and file-transfer apps can reach; Android TV has no file picker for
 * apps to use. A copy also goes to Download/FlixLauncher, which survives uninstalling Flix:
 * after a reinstall, copy it back into the backups folder to restore it.
 *
 * The Nuvio sign-in isn't included: its tokens are encrypted with a key that never leaves
 * this install, so you sign in again after restoring on a new install.
 */
object Backup {
    private const val FORMAT = 1

    /** Preference files worth keeping; "flix_looks" is only a cache of icon colours. */
    private val FILES = listOf("flix", "flix_settings")

    class Saved(val file: File, val created: Date)

    fun folder(context: Context): File? = context.getExternalFilesDir("backups")?.apply { mkdirs() }

    /** Writes a backup and returns it; also copies it to Download/FlixLauncher where possible. */
    fun save(context: Context): Saved {
        val dir = folder(context) ?: error("TV storage isn't available")
        val now = Date()
        val name = "flix-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now) + ".json"
        val json = JSONObject()
            .put("app", "Flix Launcher")
            .put("format", FORMAT)
            .put("created", now.time)
            .put("prefs", JSONObject().apply { FILES.forEach { put(it, export(context, it)) } })
            .toString(2)
        val file = File(dir, name)
        SafeIo.write(file, json)
        runCatching { copyToDownloads(context, name, json) }
        return Saved(file, now)
    }

    /** Backups in Flix's folder, newest first. */
    fun list(context: Context): List<Saved> = folder(context)?.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { f -> created(f)?.let { Saved(f, it) } }
        .sortedByDescending { it.created }

    /** Replaces the current settings with those in [backup]. Returns how many settings were restored. */
    fun restore(context: Context, backup: Saved): Int {
        val root = JSONObject(backup.file.readText())
        require(root.optString("app") == "Flix Launcher") { "That file isn't a Flix backup" }
        require(root.optInt("format") <= FORMAT) { "That backup is from a newer Flix" }
        val prefs = root.getJSONObject("prefs")
        var count = 0
        for (name in FILES) {
            val values = prefs.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (key in values.keys()) {
                val entry = values.getJSONObject(key)
                when (entry.getString("t")) {
                    "s" -> editor.putString(key, entry.getString("v"))
                    "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                    "i" -> editor.putInt(key, entry.getInt("v"))
                    "l" -> editor.putLong(key, entry.getLong("v"))
                    "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                    "set" -> editor.putStringSet(key, entry.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    else -> continue
                }
                count++
            }
            editor.commit() // written before the home screen is told to reload
        }
        return count
    }

    private fun export(context: Context, name: String) = JSONObject().apply {
        for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            val entry = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> null
            } ?: continue
            put(key, entry)
        }
    }

    private fun created(file: File): Date? = runCatching {
        JSONObject(file.readText()).takeIf { it.optString("app") == "Flix Launcher" }?.let { Date(it.getLong("created")) }
    }.getOrNull()

    /** Download/FlixLauncher, through MediaStore (no storage permission needed on Android 10+). */
    private fun copyToDownloads(context: Context, name: String, json: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FlixLauncher")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
    }
}
