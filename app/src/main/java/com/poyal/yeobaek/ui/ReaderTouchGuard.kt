package com.poyal.yeobaek.ui

/** Keeps delayed GestureDetector callbacks tied to the gesture which produced them. */
internal class ReaderTouchGuard(private val slop: Float) {
    private val taps = linkedMapOf<Long, Boolean>()
    private var x = 0f
    private var y = 0f
    var multiplePointers = false
        private set
    fun begin(id: Long, x: Float, y: Float, stopping: Boolean) {
        this.x = x; this.y = y; multiplePointers = false
        if (stopping) reject()
        taps[id] = !stopping
        while (taps.size > 4) taps.remove(taps.keys.first())
    }
    fun move(x: Float, y: Float) {
        if ((x - this.x) * (x - this.x) + (y - this.y) * (y - this.y) > slop * slop) reject()
    }
    fun multiple() { multiplePointers = true; reject() }
    fun reject() { taps.keys.toList().forEach { taps[it] = false } }
    fun isTap(id: Long) = taps[id] == true
    fun clear() { taps.clear(); multiplePointers = false }
}
