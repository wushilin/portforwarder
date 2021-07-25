package net.wushilin.portforwarder.tcp

import net.wushilin.portforwarder.common.*
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.time.Duration
import java.util.concurrent.LinkedBlockingDeque
import kotlin.system.exitProcess

data class CopyConfig(var reader:SocketChannel?, var writer:SocketChannel?, var readerKey:SelectionKey?, var writerKey:SelectionKey?) {
    init {
    }
}
val copyConfigPool = Pool(10000, {CopyConfig(null, null, null, null)}) {
    it.reader = null
    it.writer = null
    it.readerKey = null
    it.writerKey = null
}

// Stores the server socket and remote targets
val targetMap = mutableMapOf<ServerSocketChannel, Pair<String, Int>>()

// Stores all current bi-directional pipe mapping pairs
val pipes = mutableMapOf<SocketChannel, SocketChannel>()

// Stores if sockets are writable, and readable, together with their selection keys
val readyWriters = mutableMapOf<SocketChannel, SelectionKey>()
val readyReaders = mutableMapOf<SocketChannel, SelectionKey>()

// Link uptime
val linkUpTs = mutableMapOf<SocketChannel, Long>()

// Temp buffer to avoid reallocating in loop
val toCopy = mutableSetOf<CopyConfig>()

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
        println(" -Dnum.threads=4 - number of threads to run. Each thread is using independent NIO selector.")
        exitProcess(1)
    }

    Log.ENABLE_TS_IN_LOG = Config.get("enable.timestamp.in.log", true) {
        it -> it.toBoolean()
    }
    bufferSize = Config.get("buffer.size", 1048576, ) { i -> i.toInt()}
    if (bufferSize < 1024) {
        if(Log.isErrorEnabled()) {
            Log.error("Require buffer.size >= 1024 for performance reason")
        }
        exitProcess(1)
    }
    Log.LOG_LEVEL = Config.get("log.level", 1) { i -> i.toInt()}
    reportInterval = Config.get("stats.interval", 30000) { i -> i.toLong()}
    val selector: Selector = Selector.open()
    lateinit var bindings:Map<SocketAddress, Pair<String, Int>>;
    try {
        bindings = HostUtils.parse(args)
    } catch(ex:Exception) {
        if(Log.isErrorEnabled()) {
            Log.error("Invalid arguments found.")
            exitProcess(1)
        }
    }
    for (nextArg in bindings.entries) {
        val serverSocket = ServerSocketChannel.open()
        val hostAddress = nextArg.key
        val target = nextArg.value
        try {
            serverSocket.bind(hostAddress)
            serverSocket.configureBlocking(false)
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        } catch (ex: Exception) {
            if(Log.isErrorEnabled()) {
                Log.error("Invalid binding option $hostAddress($ex)")
            }
            exitProcess(1)
        }
        if(Log.isInfoEnabled()) {
            Log.info("Bound to $hostAddress, forwarding to $target")
        }
        targetMap[serverSocket] = target
    }

    if(Log.isInfoEnabled()) {
        Log.info("Allocating global buffer of ${bytesToString(bufferSize.toLong())} bytes...")
    }
    val buffer = ByteBuffer.allocate(bufferSize)


    Log.printLogo()
    if(Log.isInfoEnabled()) {
        Log.info("Server started, stats will be reports every $reportInterval milliseconds...")
    }

    while (true) {
        if (Log.isDebugEnabled()) {
            Log.debug("Selecting channels with timeout of 5 seconds")
        }
        val selectCount = selector.select(5000)
        if (Log.isDebugEnabled()) {
            Log.debug("$selectCount key(s) selected. Ready Readers ${readyReaders.size}, Ready Writers ${readyWriters.size}")
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

        toCopy.clear()
        for (nextReader in readyReaders.keys) {
            val readerKey = readyReaders[nextReader]?:continue
            val nextWriter = pipes[nextReader]?:continue
            if (readyWriters.contains(nextWriter)) {
                val writerKey = readyWriters[nextWriter]!!
                val config = copyConfigPool.acquire()
                config.reader = nextReader
                config.writer = nextWriter
                config.readerKey = readerKey
                config.writerKey = writerKey
                toCopy.add(config)
            }
        }
        for(task in toCopy) {
            val nextReader = task.reader!!
            val nextWriter = task.writer!!
            copy(buffer, nextReader, nextWriter)
        }
        for (task in toCopy) {
            try {
                val nextReader = task.reader!!
                val nextWriter = task.writer!!
                val writerKey = task.writerKey!!
                val readerKey = task.readerKey!!
                readyReaders.remove(nextReader)
                readyWriters.remove(nextWriter)
                if (writerKey.isValid) {
                    writerKey.interestOps(writerKey.interestOps() or SelectionKey.OP_WRITE)
                }
                if (readerKey.isValid) {
                    readerKey.interestOps(readerKey.interestOps() or SelectionKey.OP_READ)
                }
            } finally {
                copyConfigPool.release(task)
            }
        }
    }
}

fun connect(channel: SocketChannel): Boolean {
    return try {
        channel.finishConnect()
        // by default, connected channel is writable immediately.
        if(Log.isInfoEnabled()) {
            Log.info("${remoteAddressFor(channel)} is connected (asynchronously).")
        }
        val now = System.currentTimeMillis()
        linkUpTs[channel] = now
        linkUpTs[pipes[channel]!!] = now
        true
    } catch (ex: Exception) {
        if(Log.isErrorEnabled()) {
            Log.error("Remote address for ${remoteAddressFor(pipes[channel])} can't be connected ($ex)")
        }
        cleanup(pipes[channel]!!, channel)
        false
    }
}

fun connectQueueRun(element:Triple<Selector, SocketChannel, Pair<String, Int>>) {
    val selector = element.first
    val client = element.second
    val target = element.third
    client.configureBlocking(false)
    client.register(selector, SelectionKey.OP_READ or SelectionKey.OP_WRITE)
    val inetAddress = InetSocketAddress(target.first, target.second)
    val sockRemote = SocketChannel.open()
    sockRemote.configureBlocking(false)
    try {
        sockRemote.connect(inetAddress)
        sockRemote.register(selector, SelectionKey.OP_CONNECT)
    } catch (ex: Exception) {
        if(Log.isErrorEnabled()) {
            Log.error("Pipe NOT open for ${remoteAddressFor(client)} <== ${localAddressFor(client)} ==> ${target} (target exception: $ex)")
        }
        close(client)
        return
    }
    pipes[client] = sockRemote
    pipes[sockRemote] = client
    if(Log.isInfoEnabled()) {
        Log.info(
            "Pipe open for ${remoteAddressFor(client)} <== ${localAddressFor(client)} ==> ${remoteAddressFor(sockRemote)} (awaiting remote CONNECT)"
        )
    }
    totalRequests++
    activeRequests++
}

fun accept(selector: Selector, serverSocket: ServerSocketChannel) {
    var client: SocketChannel?
    try {
        client = serverSocket.accept()
        if(Log.isDebugEnabled()) {
            Log.debug("Accepted new incoming connection from ${remoteAddressFor(client)} to ${client.localAddress}")
        }
    } catch (ex: Exception) {
        if(Log.isWarnEnabled()) {
            Log.warn("Failed to accept a connection: ${ex}")
        }
        return
    }
    val target = targetMap[serverSocket]!!
    connectQueueRun(Triple(selector, client, target))
    if(Log.isDebugEnabled()) {
        Log.debug("Accepted connection from ${remoteAddressFor(client)}")
    }
}

fun close(sock: SocketChannel) {
    try {
        sock.close();
    } catch (ex: Exception) {
        if(Log.isWarnEnabled()) {
            Log.warn("Failed to close ${sock}")
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
    if(Log.isInfoEnabled()) {
        Log.info("Pipe closed for ${remoteAddressFor(src)} <== ${localAddressFor(src)} ==> ${remoteAddressFor(dest)}")
        Log.info("  >> Transfer stats: ${remoteAddressFor(src)} => ${remoteAddressFor(dest)}: ${bytesToString(stats[src])}")
        Log.info("  >> Transfer stats: ${remoteAddressFor(src)} <= ${remoteAddressFor(dest)}: ${bytesToString(stats[dest])}")
        val now = System.currentTimeMillis()
        val up = linkUpTs[src]?:linkUpTs[dest]?:now
        val duration = Duration.ofMillis(now - up)
        Log.info("  >> Link up: $duration")
    }
    listOf(src, dest).forEach {
        pipes.remove(it)
        readyReaders.remove(it)
        readyWriters.remove(it)
        stats.remove(it)
        linkUpTs.remove(it)
        close(it)
    }
    activeRequests--
    return
}

fun copy(buffer: ByteBuffer, src: SocketChannel, dest: SocketChannel) {
    var readCount: Int
    try {
        buffer.clear()
        readCount = src.read(buffer)
    } catch (ex: Exception) {
        if(Log.isWarnEnabled()) {
            Log.warn("Read from ${remoteAddressFor(src)} failed ($ex)")
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
    if(Log.isDebugEnabled()) {
        Log.debug("Read $readCount bytes from ${remoteAddressFor(src)}")
    }
    buffer.flip()
    while (buffer.remaining() > 0) {
        try {
            dest.write(buffer)
        } catch (ex: Exception) {
            if(Log.isWarnEnabled()) {
                Log.warn("Failed to write to ${remoteAddressFor(dest)}: ${ex}, data might be lost!")
            }
            cleanup(src, dest)
            return
        }
    }
    if(Log.isDebugEnabled()) {
        Log.debug("Copied ${readCount} bytes from ${remoteAddressFor(src)} to ${remoteAddressFor(dest)}")
    }
    totalBytes += readCount
    if (stats[src] == null) {
        stats[src] = readCount.toLong()
    } else {
        stats[src] = stats[src]!! + readCount.toLong()
    }
}