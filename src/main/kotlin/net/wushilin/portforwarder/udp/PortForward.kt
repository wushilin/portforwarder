package net.wushilin.portforwarder.udp

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.system.exitProcess


val LOG_DEBUG = 1
val LOG_INFO = 2
val LOG_WARN = 3
val LOG_ERR = 4

var LOG_LEVEL = 5

// Stores the incoming socket and remote targets, for server setup!
val targetMap = mutableMapOf<DatagramChannel, String>()

// Stores all current bi-directional pipe mapping pairs
// from clientaddress -> UDPPipe
// from localaddress -> UDPipe
lateinit var pipes:LRUCache<SocketAddress, UDPPipe>

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

// Enable Timestamp in log
var enableTsInLog = true

// Set of local listener addresses
var localListeners = mutableSetOf<SocketAddress>()

// Set timeout for idle
var idleTimeout = 3600000L

// Max conn tracking
var cacheSize = 0

// Check eviction
var evictionCheckInterval = 30000L

// Remember the expiry
fun error(msg: String) {
    log(LOG_ERR, msg)
}

fun log(level: Int, msg: String) {
    if (level > LOG_LEVEL) {
        if (enableTsInLog) {
            println("${ZonedDateTime.now()} - $msg")
        } else {
            println(msg)
        }
    }
}

fun isInfoEnabled(): Boolean {
    return LOG_INFO > LOG_LEVEL
}

fun isDebugEnabled(): Boolean {
    return LOG_DEBUG > LOG_LEVEL
}

fun isWarnEnabled(): Boolean {
    return LOG_WARN > LOG_LEVEL
}

fun isErrorEnabled(): Boolean {
    return LOG_ERR > LOG_LEVEL
}

fun printLogo() {
    val logo =
        """
██████╗  ██████╗ ██████╗ ████████╗    ███████╗ ██████╗ ██████╗ ██╗    ██╗ █████╗ ██████╗ ██████╗ ███████╗██████╗ 
██╔══██╗██╔═══██╗██╔══██╗╚══██╔══╝    ██╔════╝██╔═══██╗██╔══██╗██║    ██║██╔══██╗██╔══██╗██╔══██╗██╔════╝██╔══██╗
██████╔╝██║   ██║██████╔╝   ██║       █████╗  ██║   ██║██████╔╝██║ █╗ ██║███████║██████╔╝██║  ██║█████╗  ██████╔╝
██╔═══╝ ██║   ██║██╔══██╗   ██║       ██╔══╝  ██║   ██║██╔══██╗██║███╗██║██╔══██║██╔══██╗██║  ██║██╔══╝  ██╔══██╗
██║     ╚██████╔╝██║  ██║   ██║       ██║     ╚██████╔╝██║  ██║╚███╔███╔╝██║  ██║██║  ██║██████╔╝███████╗██║  ██║
╚═╝      ╚═════╝ ╚═╝  ╚═╝   ╚═╝       ╚═╝      ╚═════╝ ╚═╝  ╚═╝ ╚══╝╚══╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═════╝ ╚══════╝╚═╝  ╚═╝
                                                                                                                 
                ██████╗ ██╗   ██╗    ██╗    ██╗██╗   ██╗    ███████╗██╗  ██╗██╗██╗     ██╗███╗   ██╗             
                ██╔══██╗╚██╗ ██╔╝    ██║    ██║██║   ██║    ██╔════╝██║  ██║██║██║     ██║████╗  ██║             
                ██████╔╝ ╚████╔╝     ██║ █╗ ██║██║   ██║    ███████╗███████║██║██║     ██║██╔██╗ ██║             
                ██╔══██╗  ╚██╔╝      ██║███╗██║██║   ██║    ╚════██║██╔══██║██║██║     ██║██║╚██╗██║             
                ██████╔╝   ██║       ╚███╔███╔╝╚██████╔╝    ███████║██║  ██║██║███████╗██║██║ ╚████║             
                ╚═════╝    ╚═╝        ╚══╝╚══╝  ╚═════╝     ╚══════╝╚═╝  ╚═╝╚═╝╚══════╝╚═╝╚═╝  ╚═══╝
                """.trim()
    if(isInfoEnabled()) {
        info("\n$logo")
    }
}

fun bytesToString(bytes: Long?): String? {
    if (bytes == null) {
        return "0 B"
    }
    val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(bytes)
    if (absB < 1024) {
        return "$bytes B"
    }
    var value = absB
    val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
    var i = 40
    while (i >= 0 && absB > 0xfffccccccccccccL shr i) {
        value = value shr 10
        ci.next()
        i -= 10
    }
    value *= java.lang.Long.signum(bytes).toLong()
    return String.format("%.1f %ciB", value / 1024.0, ci.current())
}

fun debug(msg: String) {
    log(LOG_DEBUG, msg)
}

fun info(msg: String) {
    log(LOG_INFO, msg)
}

fun warn(msg: String) {
    log(LOG_WARN, msg)
}

fun paramFor(key: String, defaultValue: String): String {
    val stringValue = System.getProperties().getProperty(key)
    if (stringValue == null || stringValue.trim().isBlank()) {
        return defaultValue
    }

    return stringValue.trim()
}

fun stringToSocketAddress(input:String):SocketAddress {
    val tokens = input.split(":")
    return InetSocketAddress(tokens[0], tokens[1].toInt())
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

    enableTsInLog = paramFor("enable.timestamp.in.log", "true").toBoolean()
    if(isInfoEnabled()) {
        info("enable.timestamp.in.log = $enableTsInLog")
    }
    idleTimeout = paramFor("idle.timeout", "3600000").toLong()
    if(isInfoEnabled()) {
        info("idle.timeout = $idleTimeout")
    }

    evictionCheckInterval = paramFor("idle.check.interval", "5000").toLong()
    if(isInfoEnabled()) {
        info("idle.check.interval = $evictionCheckInterval")
    }

    bufferSize = paramFor("buffer.size", "1048576").toInt()
    if(isInfoEnabled()) {
        info("buffer.size = $bufferSize")
    }
    if (bufferSize < 100000) {
        if(isErrorEnabled()) {
            error("Require buffer.size >= 100000 (max UDP is 65535) or data might be lost.")
        }
        exitProcess(1)
    }
    LOG_LEVEL = paramFor("log.level", "1").toInt()
    if(isInfoEnabled()) {
        info("log.level = $LOG_LEVEL")
    }
    reportInterval = paramFor("stats.interval", "30000").toLong()
    if(isInfoEnabled()) {
        info("stats.interval = $reportInterval")
    }

    cacheSize = paramFor("conn.track.max", "10000").toInt()
    if(isInfoEnabled()) {
        info("conn.track.max = $cacheSize (adjusted to even number due to the pairing)")
    }
    pipes = LRUCache(cacheSize * 2)
    val selector: Selector = Selector.open()
    for (nextArg in args) {
        val tokens = nextArg.split("::")
        if (tokens.size != 2) {
            if(isErrorEnabled()) {
                error("${nextArg} is invalid!")
            }
            exitProcess(1)
        }
        val firstToken = tokens[0]
        val secondToken = tokens[1]

        val srcTokens = firstToken.split(":")
        val destTokens = secondToken.split(":")
        if (srcTokens.size != 2) {
            if(isErrorEnabled()) {
                error("$firstToken is invalid!")
            }
            exitProcess(1)
        }
        if (destTokens.size != 2) {
            if(isErrorEnabled()) {
                error("$secondToken is invalid!")
            }
            exitProcess(1)
        }
        try {
            val srcPort = srcTokens[1].toInt()
            val destPort = destTokens[1].toInt()
            if (srcPort > 65535 || destPort > 65535) {
                throw IllegalArgumentException("Invalid port")
            }
        } catch (ex: Exception) {
            if(isErrorEnabled()) {
                error("${srcTokens[1]} or ${destTokens[1]} are not valid ports ($ex)")
            }
            exitProcess(1)
        }
        val serverSocket = DatagramChannel.open()
        val hostAddress = InetSocketAddress(srcTokens[0], srcTokens[1].toInt())
        try {
            serverSocket.bind(hostAddress)
            serverSocket.configureBlocking(false)
            serverSocket.register(selector, SelectionKey.OP_READ)
            localListeners.add(hostAddress)
        } catch (ex: Exception) {
            if(isErrorEnabled()) {
                error("Invalid binding option $firstToken ($ex)")
            }
            exitProcess(1)
        }
        if(isInfoEnabled()) {
            info("Bound to $firstToken, forwarding to $secondToken")
        }
        targetMap[serverSocket] = secondToken
    }

    if(isInfoEnabled()) {
        info("Allocating global buffer of ${bytesToString(bufferSize.toLong())} bytes...")
    }
    val buffer = ByteBuffer.allocate(bufferSize)


    printLogo()
    if(isInfoEnabled()) {
        info("Server started, stats will be reports every $reportInterval milliseconds...")
    }

    var lastCheck = 0L
    while (true) {
        if (isDebugEnabled()) {
            debug("Selecting channels with timeout of 5 seconds")
        }
        val selectCount = selector.select(5000)
        if (isDebugEnabled()) {
            debug("$selectCount key(s) selected.")
        }
        val now = System.currentTimeMillis()
        val elapsed = now - lastCheck
        if(elapsed >= evictionCheckInterval) {
            lastCheck = now
            val watermark = now - idleTimeout
            val evicted = pipes.evictBefore(watermark)
            if(evicted.isNotEmpty()) {
                if (isInfoEnabled()) {
                    info("Evicted ${evicted.size} connections due to idle timeout ($idleTimeout ms).")
                }
                evicted.forEach {
                    cleanup(it)
                }
            }
        }
        if (now - lastReport > reportInterval) {
            val uptime = Duration.ofMillis(System.currentTimeMillis() - startTS)
            if (isInfoEnabled()) {
                info(
                    "Status Update: Uptime ${uptime}, ${selector.keys().size} keys, $activeRequests active requests, $totalRequests total requests, ${
                        bytesToString(
                            totalBytes
                        )
                    } transferred"
                )
            }
            lastReport = now
        }
        val selectedKeys = selector.selectedKeys()
        val iter = selectedKeys.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key.isValid && key.isReadable) {
                val channel = key.channel() as DatagramChannel
                handleRead(channel, key, buffer, selector)
            }
            iter.remove()
        }
    }
}

fun handleRead(channel:DatagramChannel, key:SelectionKey, buffer:ByteBuffer, selector:Selector) {
    buffer.clear()
    val remoteAddress: SocketAddress?
    try {
        remoteAddress = channel.receive(buffer)
    } catch(ex:Exception) {
        if(isWarnEnabled()) {
            warn("Failed to receive from local ${channel.localAddress}: $ex")
        }
        return
    }
    val localAddress = channel.localAddress
    buffer.flip()
    val readCount = buffer.remaining()
    val isListener = localListeners.contains(localAddress)
    println("IsListener? $isListener ${localListeners} $remoteAddress ${channel.localAddress}")
    var destAddress:SocketAddress?
    if(isListener) {
        val targetString = targetMap[channel]!!
        destAddress = stringToSocketAddress(targetString)
        var eventPipe:UDPPipe?
        // client is ready to send to me.
        if(pipes.get(remoteAddress) == null) {
            val newClient:DatagramChannel = DatagramChannel.open()
            newClient.configureBlocking(false)
            // choose random port
            newClient.bind(null)
            // bind to the destination, not for reusing.
            newClient.connect(destAddress)
            newClient.register(selector, SelectionKey.OP_READ)
            eventPipe = UDPPipe(remoteAddress, channel, newClient, destAddress)
            val evicted1 = pipes.put(eventPipe.remoteClientAddress(), eventPipe)
            val evicted2 = pipes.put(eventPipe.localClientAddress(), eventPipe)
            var evictedCount = 0
            if(evicted1 != null) {
                evictedCount++
            }
            if(evicted2 != null) {
                evictedCount++
            }
            if(evictedCount > 0) {
                if(isInfoEnabled()) {
                    info("Evicted $evictedCount connections due to max conn tracking ($cacheSize)")
                }
                listOf(evicted1, evicted2).forEach {
                    cleanup(it)
                }
            }
            activeRequests++
            totalRequests++
            if(isInfoEnabled()) {
                info("Setting up new client for ${remoteAddress} to ${destAddress}, via ${newClient.localAddress}")
            }
            linkUpTs[newClient.localAddress] = System.currentTimeMillis()
            while(buffer.hasRemaining()) {
                try {
                    newClient.write(buffer)
                } catch(ex:Exception) {
                    if(isWarnEnabled()) {
                        warn("Failed to write to $destAddress, data might be lost: $ex")
                        break
                    }
                }
            }
        } else {
            // the channel had been setup!
            eventPipe = pipes.get(remoteAddress)!!
            while(buffer.hasRemaining()) {
                try {
                    eventPipe.localClient.write(buffer)
                } catch(ex:Exception) {
                    if(isWarnEnabled()) {
                        warn("Failed to write to $destAddress, data might be lost: $ex")
                    }
                    break
                }
            }
        }
        addStats(remoteAddress, readCount)
    } else {
        // server's response
        val localAddress = channel.localAddress
        val eventPipe = pipes.get(localAddress)
        if(eventPipe == null) {
            if(isWarnEnabled()) {
                warn("Unable to write $readCount bytes from $remoteAddress back to client. Client might be evicted.")
            }
            return
        }
        destAddress = eventPipe.client
        while(buffer.hasRemaining()) {
            eventPipe.localListen.send(buffer, eventPipe.client)
        }
        addStats(eventPipe.localClient.localAddress, readCount)
    }
    if(isDebugEnabled()) {
        debug("Copied ${readCount} bytes from $remoteAddress to $destAddress")
    }
    totalBytes += readCount
}

fun addStats(from:SocketAddress, diff:Int) {
    val current = stats[from]
    if(current == null) {
        stats[from] = diff.toLong()
    } else {
        stats[from] = current + diff.toLong()
    }
}
fun close(sock: DatagramChannel) {
    try {
        sock.close();
    } catch (ex: Exception) {
        if(isWarnEnabled()) {
            warn("Failed to close ${sock}")
        }
    }
}

fun cleanup(session:UDPPipe?) {
    if(session == null) {
        return
    }
    if(session.closed) {
        return
    }
    if(isInfoEnabled()) {
        info("Pipe closed for ${session.client} <== ${session.listenAddress()} === ${session.localClientAddress()} ==> ${session.remote}")
        info("  >> Transfer stats: ${session.client} => ${session.remote}: ${bytesToString(stats[session.client])}")
        info("  >> Transfer stats: ${session.client} <= ${session.remote}: ${bytesToString(stats[session.localClient.localAddress])}")
        val now = System.currentTimeMillis()
        val up = linkUpTs[session.localClientAddress()]?:now
        val duration = Duration.ofMillis(now - up)
        info("  >> Link up: $duration")
    }
    listOf(session.client, session.localListen.localAddress).forEach {
        pipes.remove(it)
        stats.remove(it)
        linkUpTs.remove(it)
    }

    close(session.localClient)
    activeRequests--
    session.closed = true
    return
}

