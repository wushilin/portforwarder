package net.wushilin.portforwarder.udp

import java.net.SocketAddress
import java.nio.channels.DatagramChannel

data class UDPPipe(val client: SocketAddress, val localListen:DatagramChannel, val localClient:DatagramChannel,
                val remote:SocketAddress, var closed:Boolean =false) {
    fun remoteClientAddress():SocketAddress = client
    fun listenAddress():SocketAddress = localListen.localAddress
    fun targetAddress():SocketAddress = remote
    fun localClientAddress():SocketAddress = localClient.localAddress
}