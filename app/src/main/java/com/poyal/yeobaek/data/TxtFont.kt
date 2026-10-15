package com.poyal.yeobaek.data

/** Persist names, never ordinals: existing installations only stored a serif Boolean. */
enum class TxtFont(val label: String, val weight: Int = 400) {
    NOTO_SERIF("기존 명조"),
    SYSTEM_SANS("시스템 고딕"),
    SYSTEM_SANS_LIGHT("가는 고딕", 300),
    SYSTEM_SANS_MEDIUM("중간 굵기 고딕", 500),
    SYSTEM_SERIF("시스템 명조"),
    SYSTEM_MONOSPACE("고정폭");

    companion object {
        fun fromPreferences(name: String?, legacySerif: Boolean?): TxtFont =
            entries.firstOrNull { it.name == name }
                ?: if (legacySerif != false) NOTO_SERIF else SYSTEM_SANS
    }
}
