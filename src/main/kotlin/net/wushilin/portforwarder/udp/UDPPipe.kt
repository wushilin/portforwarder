package net.wushilin.portforwarder.udp

import java.net.SocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey

data class UDPPipe(var client: SocketAddress?=null, var localListen:DatagramChannel?=null,
                   var localClient:DatagramChannel? = null,
                   var remote:SocketAddress?=null, var key: SelectionKey? = null, var closed:Boolean =true) {
    fun reinitialize(client1:SocketAddress, localListen1:DatagramChannel, localClient1:DatagramChannel,
                     remote1:SocketAddress, key1:SelectionKey) {
        closed = false
        client = client1
        localListen = localListen1
        localClient = localClient1
        remote = remote1
        key = key1
    }

    fun reset() {
        closed = true
        client = null
        localListen = null
        localClient = null
        key = null
        remote = null
    }
    fun remoteClientAddress():SocketAddress? = client
    fun listenAddress():SocketAddress? = localListen?.localAddress
    fun targetAddress():SocketAddress? = remote!!
    fun localClientAddress():SocketAddress? = localClient?.localAddress

    fun isClosed():Boolean {
        return closed
    }

    fun close() {
        if(!closed) {
            closed = true
        }
    }
}