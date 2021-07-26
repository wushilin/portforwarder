package net.wushilin.portforwarder.udp

import java.net.SocketAddress
import java.nio.channels.DatagramChannel

data class UDPPipe(var client: SocketAddress?=null, var localListen:DatagramChannel?=null,
                   var localClient:DatagramChannel? = null,
                var remote:SocketAddress?=null, var closed:Boolean =false) {
    fun reinitialize(client1:SocketAddress, localListen1:DatagramChannel, localClient1:DatagramChannel,
                     remote1:SocketAddress) {
        closed = false
        client = client1
        localListen = localListen1
        localClient = localClient1
        remote = remote1
    }

    fun reset() {
        closed = true
        client = null
        localListen = null
        localClient = null
        remote = null
    }
    fun remoteClientAddress():SocketAddress = client!!
    fun listenAddress():SocketAddress = localListen!!.localAddress
    fun targetAddress():SocketAddress = remote!!
    fun localClientAddress():SocketAddress = localClient!!.localAddress

    fun isClosed():Boolean {
        return closed
    }

    fun close() {
        if(!closed) {
            closed = true
        }
    }
}