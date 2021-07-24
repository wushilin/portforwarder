package net.wushilin.portforwarder.udp


fun main(args:Array<String>) {
    val cache = LRUCache<String, String>(5);
    cache.put("hello", "world")
    println(cache)

    cache.put("wu1", "shilin")
    println(cache)

    cache.put("wu2", "shilin")
    println(cache)

    cache.put("wu3", "shilin")
    println(cache)

    cache.put("wu4", "shilin")
    println(cache)

    cache.put("wu5", "shilin")
    println(cache)

    cache.put("wu6", "shilin")
    println(cache)

    println(cache.get("hello"))
    println(cache.put("wu1", "newvalue"))
    println(cache)
    println(cache)
}

data class TimestampedKV<K,V>(var key:K, var value:V) {
    var timestamp = System.currentTimeMillis()
}

class LRUCache<K, V>(val maxSize:Int) {
    var nodeMap = mutableMapOf<K, Node<TimestampedKV<K,V>>>()
    var nodeList = NodeList<TimestampedKV<K, V>>()

    // Put a cache. Return the evicted object, if any. The evicted object should be cleaned up
    fun put(key:K, value:V):V? {
        var result: V? = null
        if(nodeMap.size >= maxSize) {
            result = evictOne()
        }
        if(nodeMap.containsKey(key)) {
            val currentNode = nodeMap[key]
            currentNode!!.value.value = value
            currentNode!!.value.timestamp = System.currentTimeMillis()
            nodeList.removeNode(currentNode)
            nodeList.addNode(currentNode)
        } else {
            val newNode = nodeList.add(TimestampedKV(key, value))
            nodeMap[key] = newNode
        }
        return result
    }

    fun remove(key:K):V? {
        if(!nodeMap.containsKey(key)) {
            return null
        }
        val node = nodeMap.get(key)!!
        nodeList.removeNode(node)
        nodeMap.remove(key)
        return node.value.value
    }

    fun evictBefore(timestamp:Long):List<V> {
        val result = mutableListOf<V>()
        while(true) {
            val headTS = oldestTimeStamp() ?: break
            if(headTS < timestamp) {
                val headData = evictOne()!!
                result.add(headData)
            } else {
                break
            }
        }
        return result
    }

    fun oldestTimeStamp():Long? {
        if(nodeList.head == null) {
            return null
        }

        return nodeList.head!!.value.timestamp
    }
    fun evictOne():V? {
        val head = nodeList.removeHead() ?: return null
        nodeMap.remove(head.value.key)
        return head.value.value
    }

    fun get(key:K):V? {
        if(!nodeMap.containsKey(key)) {
            return null
        }

        val currentNode = nodeMap[key]!!
        currentNode.value.timestamp = System.currentTimeMillis()
        nodeList.removeNode(currentNode)
        nodeList.addNode(currentNode)
        return currentNode.value.value
    }

    override fun toString():String {
        return nodeMap?.toString() + ":" + nodeList?.toString()
    }

    fun size():Int {
        return nodeList.size()
    }
}


class NodeList<T>() {
    var count:Int = 0
    var head: Node<T>? = null
    var tail: Node<T>? = null

    fun size():Int {
        return count
    }

    fun addNode(newNode:Node<T>) {
        if(count == 0) {
            newNode.next = null
            newNode.prev = null
            head = newNode
            tail = newNode
        } else {
            tail!!.next = newNode
            newNode.prev = tail
            tail = newNode
        }
        count++
    }

    fun add(value:T):Node<T> {
        if(count == 0) {
            head = Node(value, null, null)
            tail = head
        } else {
            val newNode = Node(value, tail, null)
            tail!!.next = newNode
            tail = newNode
        }
        count++
        return tail!!
    }

    fun removeHead():Node<T>?{
        if(count == 0) {
            return null
        }
        val result = head
        head = head!!.next
        if(head != null) {
            head!!.prev = null
        }
        count--
        return result
    }

    fun removeTail():Node<T>?{
        if(count == 0) {
            return null
        }

        val result = tail
        tail = tail!!.prev
        if(tail != null) {
            tail!!.next = null
        }
        count--
        return result
    }

    fun addHead(value:T):Node<T> {
        if(count == 0) {
            return add(value)
        }
        val newNode = Node(value, null, head)
        head!!.prev = newNode
        head = newNode
        count++
        return newNode
    }

    fun addTail(value:T):Node<T> {
        return add(value)
    }

    fun removeNode(node:Node<T>):Node<T>{
        if(head == node) {
            head = node.next
        }
        if(tail == node) {
            tail = node.prev
        }
        if(node.prev != null) {
            node.prev!!.next = node.next
        }
        if(node.next != null) {
            node.next!!.prev = node.prev
        }
        node.next = null
        node.prev = null
        count--
        return node
    }

    override fun toString():String {
        if(count == 0) {
            return "[]"
        }
        var currentNode = head
        val sb = StringBuilder()
        sb.append("[")
        while(currentNode != null) {
            sb.append(currentNode)
            sb.append(",")
            currentNode = currentNode.next
        }
        sb.setLength(sb.length - 1)
        sb.append("]")
        return sb.toString()
    }
}

data class Node<T>(var value:T, var prev:Node<T>?, var next:Node<T>?) {
    override fun toString():String {
        return value.toString()
    }
}