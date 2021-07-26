package net.wushilin.portforwarder.udp

import net.wushilin.portforwarder.common.*
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.time.Duration
import kotlin.system.exitProcess

// Stores the incoming socket and remote targets, for server setup!
val targetMap = mutableMapOf<DatagramChannel, Pair<String, Int>>()

// Stores the UDPPipe to avoid memory allocation
val pipePool = Pool(10, { UDPPipe() }) {
    it.reset()
}

// Stores all current bi-directional pipe mapping pairs
// from clientaddress -> UDPPipe
// from localaddress -> UDPipe
lateinit var pipes: LRUCache<SocketAddress, UDPPipe>

var channelPool = Pool(1000, {newChannel()}, { recycle(it) }) {
    close(it)
}
// Link uptime, by using localClient address -> remote server.
val linkUpTs = mutableMapOf<SocketAddress, Long>()

// Remember stats for the connections
val stats = mutableMapOf<SocketAddress, Long>()

// Total number of bytes transferred
var totalBytes = 0L

// Number of total requests
var totalRequests = 0L

// Number of active requests
var activeRequests = 0L

// Reports stats every 30 seconds
var reportInterval = 30000L

// Global buffer size. It is used for all sockets!
var bufferSize = 1024 * 1024

// time of last stats reporting
var lastReport = 0L

// time when this program was started
var startTS = System.currentTimeMillis()

// Set of local listener addresses
var localListeners = mutableSetOf<SocketAddress>()

// Set timeout for idle
var idleTimeout = 3600000L

// Max conn tracking
var cacheSize = 0

// Check eviction
var evictionCheckInterval = 30000L

fun newChannel():DatagramChannel {
    val newClient: DatagramChannel = DatagramChannel.open()
    // choose random port
    newClient.bind(null)
    newClient.configureBlocking(false)
    return newClient
}

fun recycle(channel:DatagramChannel) {
    if(Log.isDebugEnabled()) {
            Log.debug("Disconnecting channel for reuse: ${channel.localAddress}")
    }
    channel.disconnect()
}

fun close(channel:DatagramChannel) {
    if(Log.isInfoEnabled()) {
            Log.info("Closing channel for good: ${channel.localAddress}")
    }
    channel.close()
}
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: java -jar portforwarder.jar <localbind>::<remote_target>")
        println("Where:")
        println("  <localbind>     = host:port (e.g. 'localhost:443' or '0.0.0.0:9090' without the quotes!)")
        println("  <remote_target> = host:port (e.g. 'www.google.com:443' or '172.20.11.42:9092' without the quotes!")
        println("For example: ")
        println("Build this with:")
        println("# gradle jar")
        println("# java -jar port-forwarder-1.0.jar 127.0.0.1:22::ZM09.mycompany.com:22")
        println("This will allow you to ssh, or scp to remote server using ssh user@localhost")
        println("This program uses async IO, it is efficient, but single threaded.")
        println("Other options:")
        println(" -Denable.timestamp.in.log=false - disables log with timestamp (default true)")
        println(" -Dbuffer.size=512000 - set the global buffer size in bytes (default 1MiB)")
        println(" -Dlog.level=0|1|2|3|4|5|6 - 0=debug+, 1=info+, 2=warn+, 3=error+, 4=nothing  (default 1)")
        println(" -Dconn.track.max=10000 The UDP source-destination will be tracked for up to this number. LRU will be evicted")
        println(" -Dstats.interval=30000 - min duration in milliseconds between stats reporting (default 30000 = 30 seconds)")
        println(" -Didle.timeout=3600000 - is no traffic for this pipe for more than this, it will be closed.")
        println(" -Didle.check.interval=5000 - the idle timeout is only checked every xxx milliseconds")
        exitProcess(1)
    }

    Log.ENABLE_TS_IN_LOG = Config.get("enable.timestamp.in.log", true) { it -> it.toBoolean() }
    idleTimeout = Config.get("idle.timeout", 3600000L) { it -> it.toLong() }
    evictionCheckInterval = Config.get("idle.check.interval", 5000) { it.toLong() }
    bufferSize = Config.get("buffer.size", 1048576) { it -> it.toInt() }
    if (bufferSize < 100000) {
        if (Log.isErrorEnabled()) {
            Log.error("Require buffer.size >= 100000 (max UDP is 65535) or data might be lost.")
        }
        exitProcess(1)
    }
    Log.LOG_LEVEL = Config.get("log.level", 1) { it -> it.toInt() }
    reportInterval = Config.get("stats.interval", 30000) { it.toLong() }
    cacheSize = Config.get("conn.track.max", 10000) { it.toInt() }
    pipes = LRUCache(cacheSize * 2)
    val selector: Selector = Selector.open()
    val argsParsed = HostUtils.parse(args)
    for (nextArg in argsParsed.entries) {
        val listenAddress = nextArg.key
        val target = nextArg.value
        val serverSocket = DatagramChannel.open()
        try {
            serverSocket.bind(listenAddress)
            serverSocket.configureBlocking(false)
            serverSocket.register(selector, SelectionKey.OP_READ)
            localListeners.add(listenAddress)
        } catch (ex: Exception) {
            if (Log.isErrorEnabled()) {
                Log.error("Invalid binding option $listenAddress ($ex)")
            }
            exitProcess(1)
        }
        if (Log.isInfoEnabled()) {
            Log.info("Bound to $listenAddress, forwarding to $target")
        }
        targetMap[serverSocket] = target
    }

    if (Log.isInfoEnabled()) {
        Log.info("Allocating global buffer of ${bytesToString(bufferSize.toLong())} bytes...")
    }
    val buffer = ByteBuffer.allocate(bufferSize)


    Log.printLogo()
    if (Log.isInfoEnabled()) {
        Log.info("Server started, stats will be reports every $reportInterval milliseconds...")
    }

    var lastCheck = 0L
    while (true) {
        val selectCount = selector.select(1000)
        if (selectCount > 0 && Log.isDebugEnabled()) {
            Log.debug("$selectCount key(s) selected. pipes(${pipes.size()}) stats(${stats.size}) linkup(${linkUpTs.size} pool(${pipePool.size()}))")
        }
        val now = System.currentTimeMillis()
        if (now - lastReport > reportInterval) {
            val uptime = Duration.ofMillis(System.currentTimeMillis() - startTS)
            if (Log.isInfoEnabled()) {
                Log.info(
                    "Status Update: Uptime ${uptime}, ${selector.keys().size} keys, $activeRequests active requests, $totalRequests total requests, ${
                        bytesToString(
                            totalBytes
                        )
                    } transferred, JVM FREE=${bytesToString(Runtime.getRuntime().freeMemory())}/TOTAL=${
                        bytesToString(
                            Runtime.getRuntime().totalMemory()
                        )
                    }, Sock hitRate(${channelPool.hitRate()})"
                )
                Log.info("Object Count: Pipes(${pipes.size()}) Stats(${stats.size}) LinkUp(${linkUpTs.size}) Pool(${pipePool.size()})")
                Log.debug("LRUCache = $pipes")
            }
            lastReport = now
        }
        val selectedKeys = selector.selectedKeys()
        val iter = selectedKeys.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key.isValid && key.isReadable) {
                val channel = key.channel() as DatagramChannel
                if(channel.isOpen) {
                    handleRead(channel, key, buffer, selector)
                }
            }
            iter.remove()
        }
        val elapsed = now - lastCheck
        if (elapsed >= evictionCheckInterval) {
            lastCheck = now
            val watermark = now - idleTimeout
            val evicted = pipes.evictBefore(watermark)
            if (evicted.isNotEmpty()) {
                if (Log.isInfoEnabled()) {
                    Log.info("Evicted ${evicted.size} connections due to idle timeout ($idleTimeout ms).")
                }
                evicted.forEach {
                    cleanup(it)
                }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
fun handleRead(channel: DatagramChannel, key: SelectionKey, buffer: ByteBuffer, selector: Selector) {
    buffer.clear()
    val remoteAddress: SocketAddress?
    try {
        remoteAddress = channel.receive(buffer)
    } catch (ex: Exception) {
        if (Log.isWarnEnabled()) {
            Log.warn("Failed to receive from local ${channel.localAddress}: $ex")
        }
        return
    }
    val localAddress = channel.localAddress
    buffer.flip()
    val readCount = buffer.remaining()
    val isListener = localListeners.contains(localAddress)
    var destAddress: SocketAddress?
    if (isListener) {
        val targetPair = targetMap[channel]!!
        destAddress = HostUtils.convertToAddress(targetPair)
        var eventPipe: UDPPipe?
        // client is ready to send to me and I don't have the session up yet!
        if (pipes.get(remoteAddress) == null) {
            val newClient = channelPool.acquire()
            newClient.connect(destAddress)
            val selectionKey = newClient.register(selector, SelectionKey.OP_READ)
            eventPipe = pipePool.acquire()
            eventPipe.reinitialize(remoteAddress, channel, newClient, destAddress, selectionKey)

            val evicted1 = pipes.put(eventPipe.remoteClientAddress()!!, eventPipe)
            val evicted2 = pipes.put(eventPipe.localClientAddress()!!, eventPipe)
            val evictedList = listOfNotNull(evicted1, evicted2)
            if (evictedList.isNotEmpty()) {
                if (Log.isInfoEnabled()) {
                    Log.info("Evicted ${evictedList.size} connections due to max conn tracking ($cacheSize)")
                }
                evictedList.forEach {
                    cleanup(it)
                }
            }
            activeRequests++
            totalRequests++
            if (Log.isInfoEnabled()) {
                Log.info("Setting up new client for ${remoteAddress} to ${destAddress}, via ${newClient.localAddress}")
            }
            linkUpTs[eventPipe.localClientAddress()!!] = System.currentTimeMillis()
            while (buffer.hasRemaining()) {
                try {
                    newClient.write(buffer)
                } catch (ex: Exception) {
                    if (Log.isWarnEnabled()) {
                        Log.warn("Failed to write to $destAddress, data might be lost: $ex")
                        break
                    }
                }
            }
        } else {
            // the channel had been setup!
            eventPipe = pipes.get(remoteAddress)!!
            while (buffer.hasRemaining()) {
                try {
                    eventPipe.localClient!!.write(buffer)
                } catch (ex: Exception) {
                    if (Log.isWarnEnabled()) {
                        Log.warn("Failed to write to $destAddress, data might be lost: $ex")
                    }
                    break
                }
            }
        }
        addStats(remoteAddress, readCount)
    } else {
        // server's response
        val eventPipe = pipes.get(localAddress)
        if (eventPipe == null) {
            if (Log.isWarnEnabled()) {
                Log.warn("Unable to write $readCount bytes from $remoteAddress back to client. Client might be evicted.")
            }
            return
        }
        destAddress = eventPipe.remoteClientAddress()
        while (buffer.hasRemaining()) {
            eventPipe.localListen!!.send(buffer, eventPipe.remoteClientAddress())
        }
        addStats(eventPipe.localClientAddress()!!, readCount)
    }
    if (Log.isDebugEnabled()) {
        Log.debug("Copied ${readCount} bytes from $remoteAddress to $destAddress")
    }
    totalBytes += readCount
}

fun addStats(from: SocketAddress, diff: Int) {
    val current = stats[from]
    if (current == null) {
        stats[from] = diff.toLong()
    } else {
        stats[from] = current + diff.toLong()
    }
}

fun cleanup(session: UDPPipe?) {
    if (session == null) {
        return
    }
    if (session.isClosed()) {
        return
    }
    try {
        if (Log.isInfoEnabled()) {
            Log.info("Pipe closed for ${session.client} <== ${session.listenAddress()} === ${session.localClientAddress()} ==> ${session.remote}")
            Log.info("  >> Transfer stats: ${session.client} => ${session.remote}: ${bytesToString(stats[session.remoteClientAddress()])}")
            Log.info("  >> Transfer stats: ${session.client} <= ${session.remote}: ${bytesToString(stats[session.localClientAddress()])}")
            val now = System.currentTimeMillis()
            val up = linkUpTs[session.localClientAddress()] ?: now
            val duration = Duration.ofMillis(now - up)
            Log.info("  >> Link up: $duration")
        }
        listOfNotNull(session.remoteClientAddress(), session.localClientAddress()).forEach {
            pipes.remove(it)
            stats.remove(it)
            linkUpTs.remove(it)
        }
        session.key?.cancel()
        activeRequests--
        session.closed = true
    } finally {
        channelPool.release(session.localClient!!)
        // release will reset the session as well using pool config.
        pipePool.release(session)
    }
}

