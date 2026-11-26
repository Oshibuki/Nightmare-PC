package com.abrah.nightmare.desktop.gui

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The one network seam behind `download_lora` (ADR-0015). [contentLength]
 * reports the announced byte size without reading a body (-1 when the source
 * does not say); [download] streams the body into an already-validated path.
 * JVM tests inject a fake here, so no unit test ever opens a socket.
 */
interface LoraDownloader {
    fun contentLength(url: String): Long
    fun download(url: String, dest: Path): Long
}

/** The only production transport: bounded connect/read, temp file, then move. */
object HttpLoraDownloader : LoraDownloader {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 30_000

    override fun contentLength(url: String): Long {
        val connection = open(url)
        return try {
            connection.requestMethod = "HEAD"
            connection.contentLengthLong
        } catch (e: IOException) {
            throw IOException("无法读取 LoRA 文件大小：${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, dest: Path): Long {
        val connection = open(url)
        return try {
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code >= 400) throw IOException("LoRA 下载失败：HTTP $code")
            val parent = dest.toAbsolutePath().parent
                ?: throw IOException("下载路径无效：$dest")
            Files.createDirectories(parent)
            val temp = Files.createTempFile(parent, ".lora-", ".part")
            try {
                val bytes = connection.inputStream.use { input ->
                    Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
                }
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING)
                bytes
            } finally {
                runCatching { Files.deleteIfExists(temp) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }
            .getOrElse { throw IOException("无效的 LoRA 下载地址", it) }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        return connection
    }
}
