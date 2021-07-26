package net.wushilin.portforwarder.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

class Pool<T>(private val size: Int, val supplier:()->T, val cleaner:((T)->Unit)? = null) {
    private val buffer = LinkedList<T>()
    private var hardAcquireCount = 0L
    private var acquireCount = 0L
    fun acquire():T {
        acquireCount++
        return if(buffer.size == 0) {
            hardAcquire()
        } else {
            buffer.removeFirst()
        }
    }

    fun release(value:T) {
        try {
            if (buffer.size >= size) {
                return
            }
            buffer.addLast(value)
        } finally {
            cleaner?.let { it(value) }
        }
    }

    fun size():Int {
        return buffer.size
    }

    private fun hardAcquire():T {
        hardAcquireCount++
        return supplier()
    }

    fun hardAcquireCount():Long {
        return hardAcquireCount
    }

    fun hitRate():Double {
        if(acquireCount == 0L) {
            return 0.0;
        }
        return BigDecimal(acquireCount - hardAcquireCount).divide(BigDecimal(acquireCount),
            2, RoundingMode.HALF_UP).toDouble()
    }
}