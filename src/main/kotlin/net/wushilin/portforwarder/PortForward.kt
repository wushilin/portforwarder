package net.wushilin.portforwarder

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
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

// Stores the server socket and remote targets
val targetMap = mutableMapOf<ServerSocketChannel, String>()

// Stores all current bi-directional pipe mapping pairs
val pipes = mutableMapOf<SocketChannel, SocketChannel>()

// Stores if sockets are writable, and readable, together with their selection keys
val readyWriters = mutableMapOf<SocketChannel, SelectionKey>()
val readyReaders = mutableMapOf<SocketChannel, SelectionKey>()

// Link uptime
val linkUpTs = mutableMapOf<SocketChannel, Long>()

// Temp buffer to avoid reallocating in loop
val toRemove = mutableSetOf<SocketChannel>()

// Temp buffer to hold the keys to test
val toRead = mutableListOf<SocketChannel>()

// Remember stats for the connections
val stats = mutableMapOf<SocketChannel, Long>()

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
        println(" -Dstats.interval=30000 - min duration in milliseconds between stats reporting (default 30000 = 30 seconds)")
        exitProcess(1)
    }

    enableTsInLog = paramFor("enable.timestamp.in.log", "true").toBoolean()
    if(isInfoEnabled()) {
        info("enable.timestamp.in.log = $enableTsInLog")
    }
    bufferSize = paramFor("buffer.size", "1048576").toInt()
    if(isInfoEnabled()) {
        info("buffer.size = $bufferSize")
    }
    if (bufferSize < 1024) {
        if(isErrorEnabled()) {
            error("Require buffer.size >= 1024 for performance reason")
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
        val serverSocket = ServerSocketChannel.open()
        val hostAddress = InetSocketAddress(srcTokens[0], srcTokens[1].toInt())
        try {
            serverSocket.bind(hostAddress)
            serverSocket.configureBlocking(false)
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
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
    while (true) {
        if (isDebugEnabled()) {
            debug("Selecting channels with timeout of 5 seconds")
        }
        val selectCount = selector.select(5000)
        if (isDebugEnabled()) {
            debug("$selectCount key(s) selected. Ready Readers ${readyReaders.size}, Ready Writers ${readyWriters.size}")
        }
        val now = System.currentTimeMillis()
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
            if (key.isValid && key.isConnectable) {
                val channel = key.channel() as SocketChannel
                if (connect(channel)) {
                    key.interestOps(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                } else {
                    key.cancel()
                }
            }
            if (key.isValid && key.isAcceptable) {
                val channel = key.channel() as ServerSocketChannel
                accept(selector, channel)
            }
            if (key.isValid && key.isReadable) {
                val channel = key.channel() as SocketChannel
                readyReaders[channel] = key
                // Mute the read readiness
                key.interestOps(key.interestOps() and (SelectionKey.OP_READ.inv()))
            }
            if (key.isValid && key.isWritable) {
                val channel = key.channel() as SocketChannel
                readyWriters[channel] = key
                // Mute the write readiness
                key.interestOps(key.interestOps() and (SelectionKey.OP_WRITE.inv()))
            }

            iter.remove()
        }
        toRemove.clear()
        toRead.clear()
        toRead.addAll(readyReaders.keys)
        for (nextReader in toRead) {
            val readerKey = readyReaders[nextReader] ?: continue
            val nextWriter = pipes[nextReader] ?: continue
            if (readyWriters.contains(nextWriter)) {
                val writerKey = readyWriters[nextWriter]!!
                readyWriters.remove(nextWriter)
                copy(buffer, nextReader, nextWriter)
                toRemove.add(nextReader)
                // Re-register interest of reader and writers
                if (writerKey.isValid) {
                    writerKey.interestOps(writerKey.interestOps() or SelectionKey.OP_WRITE)
                }
                if (readerKey.isValid) {
                    readerKey.interestOps(readerKey.interestOps() or SelectionKey.OP_READ)
                }
            }
        }
        for (nextKey in toRemove) {
            readyReaders.remove(nextKey)
        }
    }
}

fun connect(channel: SocketChannel): Boolean {
    return try {
        channel.finishConnect()
        // by default, connected channel is writable immediately.
        if(isInfoEnabled()) {
            info("${remoteAddressFor(channel)} is connected (asynchronously).")
        }
        val now = System.currentTimeMillis()
        linkUpTs[channel] = now
        linkUpTs[pipes[channel]!!] = now
        true
    } catch (ex: Exception) {
        if(isErrorEnabled()) {
            error("Remote address for ${remoteAddressFor(pipes[channel])} can't be connected ($ex)")
        }
        cleanup(channel, pipes[channel]!!)
        false
    }
}

fun accept(selector: Selector, serverSocket: ServerSocketChannel) {
    var client: SocketChannel?
    try {
        client = serverSocket.accept()
        if(isDebugEnabled()) {
            debug("Accepted new incoming connection from ${remoteAddressFor(client)} to ${client.localAddress}")
        }
    } catch (ex: Exception) {
        if(isWarnEnabled()) {
            warn("Failed to accept a connection: ${ex}")
        }
        return
    }
    totalRequests++
    activeRequests++
    client.configureBlocking(false)
    client.register(selector, SelectionKey.OP_READ or SelectionKey.OP_WRITE)
    val target: String = targetMap[serverSocket]!!
    val tokens = target.split(":")
    val host = tokens[0]
    val port = tokens[1].toInt()
    val inetAddress = InetSocketAddress(host, port)
    val sockRemote = SocketChannel.open()
    sockRemote.configureBlocking(false)
    try {
        sockRemote.connect(inetAddress)
        sockRemote.register(selector, SelectionKey.OP_CONNECT)
    } catch (ex: Exception) {
        if(isErrorEnabled()) {
            error("Pipe NOT open for ${remoteAddressFor(client)} <== ${localAddressFor(client)} ==> ${target} (target exception: $ex)")
        }
        client.close()
        activeRequests--
        return
    }
    pipes[client] = sockRemote
    pipes[sockRemote] = client
    if(isInfoEnabled()) {
        info(
            "Pipe open for ${remoteAddressFor(client)} <== ${localAddressFor(client)} ==> ${remoteAddressFor(sockRemote)} (awaiting remote CONNECT)"
        )
    }
}

fun close(sock: SocketChannel) {
    try {
        sock.close();
    } catch (ex: Exception) {
        if(isWarnEnabled()) {
            warn("Failed to close ${sock}")
        }
    }
}

fun localAddressFor(channel: SocketChannel?): String {
    return if (channel != null) {
        try {
            "${channel.localAddress}"
        } catch (ex: Exception) {
            "[closed channel]"
        }
    } else {
        "[null channel]"
    }
}

fun remoteAddressFor(channel: SocketChannel?): String {
    return if (channel != null) {
        try {
            "${channel.remoteAddress}"
        } catch (ex: Exception) {
            "[closed channel]"
        }
    } else {
        "[null channel]"
    }
}

fun cleanup(src: SocketChannel, dest: SocketChannel) {
    if(isInfoEnabled()) {
        info("Pipe closed for ${remoteAddressFor(src)} <== ${localAddressFor(src)} ==> ${remoteAddressFor(dest)}")
        info("  >> Transfer stats: ${remoteAddressFor(src)} => ${remoteAddressFor(dest)}: ${bytesToString(stats[src])}")
        info("  >> Transfer stats: ${remoteAddressFor(src)} <= ${remoteAddressFor(dest)}: ${bytesToString(stats[dest])}")
        val now = System.currentTimeMillis()
        val up = linkUpTs[src]?:linkUpTs[dest]?:now
        val duration = Duration.ofMillis(now - up)
        info("  >> Link up: $duration")
    }
    pipes.remove(dest)
    pipes.remove(src)
    close(src)
    close(dest)
    readyReaders.remove(src)
    readyReaders.remove(dest)
    readyWriters.remove(src)
    readyWriters.remove(dest)
    stats.remove(src)
    stats.remove(dest)
    linkUpTs.remove(src)
    linkUpTs.remove(dest)
    activeRequests--
    return
}

fun copy(buffer: ByteBuffer, src: SocketChannel, dest: SocketChannel) {
    var readCount: Int
    try {
        buffer.clear()
        readCount = src.read(buffer)
    } catch (ex: Exception) {
        if(isWarnEnabled()) {
            warn("Read from ${remoteAddressFor(src)} failed ($ex)")
        }
        cleanup(src, dest)
        return
    }
    if (readCount == 0) {
        return
    }
    if (readCount < 0) {
        // EOF
        cleanup(src, dest)
        return
    }
    if(isDebugEnabled()) {
        debug("Read $readCount bytes from ${remoteAddressFor(src)}")
    }
    buffer.flip()
    while (buffer.remaining() > 0) {
        try {
            dest.write(buffer)
        } catch (ex: Exception) {
            if(isWarnEnabled()) {
                warn("Failed to write to ${remoteAddressFor(dest)}: ${ex}, data might be lost!")
            }
            cleanup(src, dest)
            return
        }
    }
    if(isDebugEnabled()) {
        debug("Copied ${readCount} bytes from ${remoteAddressFor(src)} to ${remoteAddressFor(dest)}")
    }
    totalBytes += readCount
    if (stats[src] == null) {
        stats[src] = readCount.toLong()
    } else {
        stats[src] = stats[src]!! + readCount.toLong()
    }
}