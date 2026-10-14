package com.poyal.yeobaek.image

/** Detect split-volume RAR before libarchive can mistake a complete first volume for a whole book. */
internal fun checkArchiveHeader(bytes: ByteArray) {
    fun byte(at: Int) = bytes.getOrNull(at)?.toInt()?.and(255) ?: -1
    if (byte(0) == 0x50 && byte(1) == 0x4b && byte(2) == 0x07 && byte(3) == 0x08)
        throw ImageFailure("분할 압축파일은 아직 지원하지 않습니다. 하나의 압축파일로 합쳐 주세요.")
    if (bytes.size < 16 || byte(0) != 0x52 || byte(1) != 0x61 || byte(2) != 0x72 || byte(3) != 0x21 || byte(4) != 0x1a || byte(5) != 0x07) return
    var volume = false
    if (byte(6) == 0 && byte(9) == 0x73) volume = byte(10) and 1 != 0
    if (byte(6) == 1 && byte(7) == 0) {
        var at = 12
        fun vint(): Long {
            var value = 0L
            repeat(9) { shift ->
                val b = byte(at++)
                if (b < 0) throw ImageFailure("압축 헤더가 손상되었습니다.")
                value = value or ((b and 127).toLong() shl (shift * 7))
                if (b and 128 == 0) return value
            }
            throw ImageFailure("압축 헤더가 손상되었습니다.")
        }
        vint() // Header size, excluding its vint field and CRC32.
        when (vint()) {
            4L -> throw ImageFailure("암호가 걸린 압축파일은 아직 지원하지 않습니다.")
            1L -> {
                val flags = vint()
                if (flags and 1 != 0L) vint()
                if (flags and 2 != 0L) vint()
                volume = vint() and 1 != 0L
            }
        }
    }
    if (volume) throw ImageFailure("분할 압축파일은 아직 지원하지 않습니다. 하나의 압축파일로 합쳐 주세요.")
}
