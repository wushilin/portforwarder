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

var LOG_LEVEL = 0

// Stores the server socket and remote targets
val targetMap = mutableMapOf<ServerSocketChannel, String>()

// Stores all current bi-directional pipe mapping pairs
val pipes = mutableMapOf<SocketChannel, SocketChannel>()

// Stores if sockets are writable, and readable, together with their selection keys
val readyWriters = mutableMapOf<SocketChannel, SelectionKey>()
val readyReaders = mutableMapOf<SocketChannel, SelectionKey>()

// Temp buffer to avoid reallocating in loop
val toRemove = mutableSetOf<SocketChannel>()

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

fun error(msg: String) {
    log(LOG_ERR, msg)
}

fun log(level: Int, msg: String) {
    if (level > LOG_LEVEL) {
        println("${ZonedDateTime.now()} - $msg")
    }
}

fun printLogo() {
    val logo =
        """██████╗  ██████╗ ██████╗ ████████╗    ███████╗ ██████╗ ██████╗ ██╗    ██╗ █████╗ ██████╗ ██████╗ ███████╗██████╗ 
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
                ╚═════╝    ╚═╝        ╚══╝╚══╝  ╚═════╝     ╚══════╝╚═╝  ╚═╝╚═╝╚══════╝╚═╝╚═╝  ╚═══╝ """
    println(logo)
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
        exitProcess(1)
    }

    val selector: Selector = Selector.open()
    for (nextArg in args) {
        val tokens = nextArg.split("::")
        if (tokens.size != 2) {
            error("${nextArg} is invalid!")
            exitProcess(1)
        }
        val firstToken = tokens[0]
        val secondToken = tokens[1]

        val srcTokens = firstToken.split(":")
        val destTokens = secondToken.split(":")
        if (srcTokens.size != 2) {
            error("$firstToken is invalid!")
            exitProcess(1)
        }
        if (destTokens.size != 2) {
            error("$secondToken is invalid!")
            exitProcess(1)
        }
        try {
            val srcPort = srcTokens[1].toInt()
            val destPort = destTokens[1].toInt()
            if (srcPort > 65535 || destPort > 65535) {
                throw IllegalArgumentException("Invalid port")
            }
        } catch (ex: Exception) {
            error("${srcTokens[1]} or ${destTokens[1]} are not valid ports.")
            exitProcess(1)
        }
        val serverSocket = ServerSocketChannel.open()
        val hostAddress = InetSocketAddress(srcTokens[0], srcTokens[1].toInt())
        try {
            serverSocket.bind(hostAddress)
            serverSocket.configureBlocking(false)
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        } catch(ex:Exception) {
            error("Invalid binding option $firstToken.")
            exitProcess(1)
        }
        info("Bound to $firstToken, forwarding to $secondToken")
        targetMap[serverSocket] = secondToken
    }

    info("Allocating global buffer of ${bytesToString(bufferSize.toLong())} bytes...")
    val buffer = ByteBuffer.allocate(bufferSize)


    printLogo()
    info("Starter Started, stats will be reports every $reportInterval milliseconds...")
    while (true) {
        selector.select(10000)
        val now = System.currentTimeMillis()
        if (now - lastReport > reportInterval) {
            val uptime = Duration.ofMillis(System.currentTimeMillis() - startTS)

            debug(
                "Status Update: Uptime ${uptime}, $activeRequests active requests, $totalRequests total requests, ${
                    bytesToString(
                        totalBytes
                    )
                } transferred"
            )
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

            toRemove.clear()
            for (nextReader in readyReaders.keys) {
                val readerKey = readyReaders[nextReader]!!
                val nextWriter = pipes[nextReader]!!
                if (readyWriters.contains(nextWriter)) {
                    val writerKey = readyWriters[nextWriter]!!
                    readyWriters.remove(nextWriter)
                    read(buffer, nextReader, nextWriter)
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
            iter.remove()
        }
    }
}

fun connect(channel: SocketChannel): Boolean {
    return try {
        channel.finishConnect()
        // by default, connected channel is writable immediately.
        debug("${addressFor(channel)} is connected (asynchronously).")
        true
    } catch (ex: Exception) {
        warn("Remote address for ${addressFor(pipes[channel])} can't be connected ($ex)")
        cleanup(channel, pipes[channel]!!)
        false
    }
}

fun accept(selector: Selector, serverSocket: ServerSocketChannel) {
    var client: SocketChannel?
    try {
        client = serverSocket.accept()
    } catch (ex: Exception) {
        warn("Failed to accept a connection: ${ex}")
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
        warn("Failed to connect to remote ${target} (${ex})")
        info("Pipe NOT setup for ${addressFor(client)} <== THIS-SERVER ==> ${target}")
        client.close()
        activeRequests--
        return
    }
    pipes[client] = sockRemote
    pipes[sockRemote] = client
    info(
        "Pipe setup for ${addressFor(client)} <== THIS-SERVER ==> ${addressFor(sockRemote)} (awaiting ${
            addressFor(
                sockRemote
            )
        } to be connected)"
    )
}

fun close(sock: SocketChannel) {
    try {
        sock.close();
    } catch (ex: Exception) {
        warn("Failed to close ${sock}")
    }
}

fun addressFor(channel: SocketChannel?): String {
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
    info("Pipe closed for ${addressFor(src)} <== THIS-SERVER ==> ${addressFor(dest)}")
    info("  >> Transfer stats: ${addressFor(src)} => ${addressFor(dest)}: ${bytesToString(stats[src])}")
    info("  >> Transfer stats: ${addressFor(src)} <= ${addressFor(dest)}: ${bytesToString(stats[dest])}")

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
    activeRequests--
    return
}

fun read(buffer: ByteBuffer, src: SocketChannel, dest: SocketChannel) {
    var readCount: Int
    try {
        buffer.clear()
        readCount = src.read(buffer)
    } catch (ex: Exception) {
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
    buffer.flip()
    while (buffer.remaining() > 0) {
        try {
            dest.write(buffer)
        } catch (ex: Exception) {
            warn("Failed to write to ${addressFor(dest)}: ${ex}")
            cleanup(src, dest)
            return
        }
    }
    totalBytes += readCount
    if (stats[src] == null) {
        stats[src] = readCount.toLong()
    } else {
        stats[src] = stats[src]!! + readCount.toLong()
    }
}