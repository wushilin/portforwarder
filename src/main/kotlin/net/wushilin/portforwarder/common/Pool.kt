package net.wushilin.portforwarder.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

class Pool<T>(private var maxSize: Int, val supplier:()->T, val resetter:((T)->Unit)? = null, val cleaner:((T)->Unit)?=resetter) {
    private val buffer = LinkedList<T>()
    private var hardAcquireCount = 0L
    private var acquireCount = 0L
<<<<<<< HEAD
=======

    fun resize(max:Int) {
        maxSize = max
    }

>>>>>>> refs/remotes/origin/main
    fun acquire():T {
        acquireCount++
        return if(buffer.size == 0) {
            hardAcquire()
        } else {
            buffer.removeFirst()
        }
    }

    fun release(value:T) {
<<<<<<< HEAD
        try {
            if (buffer.size >= size) {
                return
            }
            buffer.addLast(value)
        } finally {
            cleaner?.let { it(value) }
        }
=======
        if (buffer.size >= maxSize) {
            hardRelease(value)
            return
        }
        softRelease(value)
        buffer.addLast(value)
>>>>>>> refs/remotes/origin/main
    }

    fun size():Int {
        return buffer.size
    }

    private fun hardAcquire():T {
        hardAcquireCount++
        return supplier()
    }

<<<<<<< HEAD
=======
    private fun softRelease(value:T) {
        resetter?.let {
            it(value)
        }
    }

    private fun hardRelease(value:T) {
        cleaner?.let {
            it(value)
        }
    }

>>>>>>> refs/remotes/origin/main
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
<<<<<<< HEAD
}
=======
}
>>>>>>> refs/remotes/origin/main
