package net.wushilin.portforwarder.common

import java.util.*

class Pool<T>(private val size: Int, val supplier:()->T, val cleaner:((T)->Unit)? = null) {
    private val buffer = LinkedList<T>()
    fun acquire():T {
        return if(buffer.size == 0) {
            hardAcquire()
        } else {
            buffer.removeFirst()
        }
    }

    fun release(value:T) {
        if(buffer.size >= size) {
            return
        }
        buffer.addLast(value)
    }
    private fun hardAcquire():T {
        return supplier()
    }
}