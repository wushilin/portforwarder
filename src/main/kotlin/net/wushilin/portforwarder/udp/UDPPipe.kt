package net.wushilin.portforwarder.udp

import java.net.SocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey

data class UDPPipe(private var client: SocketAddress?, private var localListen:DatagramChannel?,
                   private var localClient:DatagramChannel?,
                   private var remote:SocketAddress?, private var key: SelectionKey?, private var closed:Boolean = false) {
    fun reuse(client1:SocketAddress, localListen1:DatagramChannel, localClient1:DatagramChannel,
              remote1:SocketAddress, key1:SelectionKey) {
        closed = false
        client = client1
        localListen = localListen1
        localClient = localClient1
        remote = remote1
        key = key1
    }

    fun remoteClientAddress():SocketAddress? = client
    fun localListenAddress():SocketAddress? = localListen?.localAddress
    fun targetAddress():SocketAddress? = remote!!
    fun localClientAddress():SocketAddress? = localClient?.localAddress
    fun localClient():DatagramChannel? = localClient
    fun localListen():DatagramChannel? = localListen
    fun key():SelectionKey? = key
    fun isClosed():Boolean {
        return closed
    }

    fun close() {
        if(!closed) {
            closed = true
        }
    }
}