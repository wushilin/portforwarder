package net.wushilin.portforwarder.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

class Pool<T>(private var maxSize: Int, private val supplier:()->T, private val resetter:((T)->Unit)? = null, private val garbageCollector:((T)->Unit)?=resetter) {
    private val buffer = LinkedList<T>()
    private var hardAcquireCount = 0L
    private var acquireCount = 0L

    fun resize(max:Int) {
        maxSize = max
    }

    fun acquire():T {
        acquireCount++
        return if(buffer.size == 0) {
            hardAcquire()
        } else {
            buffer.removeFirst()
        }
    }

    fun release(value:T) {
        if (buffer.size >= maxSize) {
            hardRelease(value)
            return
        }
        softRelease(value)
        buffer.addLast(value)
    }

    fun size():Int {
        return buffer.size
    }

    private fun hardAcquire():T {
        hardAcquireCount++

        return supplier()
    }

    private fun softRelease(value:T) {
        resetter?.let {
            it(value)
        }
    }

    private fun hardRelease(value:T) {
        garbageCollector?.let {
            it(value)
        }
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