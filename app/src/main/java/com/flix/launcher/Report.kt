package com.flix.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder

/**
 * "Report a problem": a QR code that opens a GitHub bug report on the phone, already filled
 * in with this TV, the Flix version, the Home button's state and Flix's recent warnings.
 * Nothing is sent from the TV; the user reads and submits it on their phone.
 */
object Report {
    private const val NEW_ISSUE = "https://github.com/HatriGt/flix-launcher/issues/new?template=bug_report.yml"
    // Short enough for a phone to read the code from the sofa; the oldest log lines go first.
    private const val MAX_URL = 900

    fun show(activity: Activity) {
        val url = url(activity)
        val dp = activity.resources.displayMetrics.density
        val size = (260 * dp).toInt()
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(ImageView(activity).apply {
                setImageBitmap(qr(url, size))
                setBackgroundColor(Color.WHITE)
                val pad = (12 * dp).toInt()
                setPadding(pad, pad, pad, pad)
            }, LinearLayout.LayoutParams(size + (24 * dp).toInt(), size + (24 * dp).toInt()))
            addView(TextView(activity).apply {
                text = "Scan with your phone's camera. It opens a bug report with this TV's details and Flix's recent errors filled in. " +
                    "Describe what happened, then submit it (you'll need a GitHub account)."
                textSize = 14f
                setTextColor(0xCCFFFFFF.toInt())
                setPadding((24 * dp).toInt(), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Report a problem")
            .setView(view)
            .setPositiveButton("Done", null)
            .show()
            // Alert dialogs are phone-narrow; widen it so the text sits beside the code.
            .window?.setLayout((680 * dp).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun url(activity: Activity): String {
        val version = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull().orEmpty()
        val tv = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        val base = NEW_ISSUE + "&tv=" + enc(tv) + "&version=" + enc(version)
        val header = "Home button: ${HomeButton.describe(activity)}\n"
        var logs = header + recentLogs()
        // Drop the oldest lines until the encoded link fits the code.
        while (logs.length > header.length && (base + "&logs=" + enc(logs)).length > MAX_URL) {
            val cut = logs.indexOf('\n', header.length)
            logs = if (cut < 0) header else header + logs.substring(cut + 1)
        }
        return base + "&logs=" + enc(logs)
    }

    /**
     * Flix's own warnings and errors from logcat. An app can only read its own log lines,
     * so nothing from other apps is included.
     */
    private fun recentLogs(): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300", "-v", "brief", "*:W"))
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.destroy()
        text.lines()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .map { it.take(160) }
            .takeLast(40)
            .joinToString("\n")
    }.getOrDefault("")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun qr(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
