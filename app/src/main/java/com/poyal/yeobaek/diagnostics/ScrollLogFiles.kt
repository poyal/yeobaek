package com.poyal.yeobaek.diagnostics

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Used only by the diagnostics IO actor. Both disk use and export memory are bounded. */
internal class ScrollLogFiles(private val directory: File,
    private val fileBytes: Int = 8 * 1024 * 1024, private val count: Int = 16) {
    init { require(fileBytes > 0 && count > 0) }
    private fun file(index: Int) = File(directory, "txt-scroll-$index.log")

    fun append(line: String) {
        check(directory.isDirectory || directory.mkdirs()) { "진단 로그 폴더를 만들지 못했습니다." }
        val bytes = (line.take((fileBytes - 1) / 4) + "\n").toByteArray(Charsets.UTF_8)
        if (file(0).length() + bytes.size > fileBytes) {
            for (index in count - 2 downTo 0) {
                if (file(index).exists()) Files.move(file(index).toPath(), file(index + 1).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // count == 1 has no rotation destination.
            if (count == 1) file(0).writeBytes(byteArrayOf())
        }
        file(0).appendBytes(bytes)
    }

    fun copyTo(output: OutputStream) {
        for (index in count - 1 downTo 0) if (file(index).exists()) file(index).inputStream().use { it.copyTo(output) }
    }
}
