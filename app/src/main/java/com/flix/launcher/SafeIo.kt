package com.flix.launcher

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * File and network helpers that can't leave the launcher in a bad state: cache files are
 * written whole or not at all (a power cut mid-write would otherwise leave a broken copy
 * that's trusted for days), and downloads stop at a size limit (a broken or hostile addon
 * sending an endless reply would otherwise run a 2 GB TV out of memory).
 */
object SafeIo {
    /** Largest JSON reply read from any server: addon catalogues are well under 1 MB. */
    const val MAX_JSON = 4 * 1024 * 1024

    /** Largest image downloaded: a 1920 px photo is about 300 KB, UHD ones a few MB. */
    const val MAX_IMAGE = 12 * 1024 * 1024

    /** Writes [bytes] to a temporary file beside [file], then renames it over [file]. */
    fun write(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Couldn't save ${file.name}")
        }
    }

    fun write(file: File, text: String) = write(file, text.toByteArray())

    /** Reads at most [limit] bytes from [input], failing rather than reading further. */
    fun readBytes(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("Reply too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** The connection's body as text, up to [limit] bytes; closes the stream. */
    fun readText(conn: HttpURLConnection, limit: Int = MAX_JSON): String =
        conn.inputStream.use { String(readBytes(it, limit)) }
}
