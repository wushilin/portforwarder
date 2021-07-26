package net.wushilin.portforwarder.udp

import java.net.SocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey

data class UDPPipe(var client: SocketAddress?=null, var localListen:DatagramChannel?=null,
                   var localClient:DatagramChannel? = null,
<<<<<<< HEAD
                var remote:SocketAddress?=null, var closed:Boolean =false) {
    fun reinitialize(client1:SocketAddress, localListen1:DatagramChannel, localClient1:DatagramChannel,
                     remote1:SocketAddress) {
=======
                   var remote:SocketAddress?=null, var key: SelectionKey? = null, var closed:Boolean =true) {
    fun reinitialize(client1:SocketAddress, localListen1:DatagramChannel, localClient1:DatagramChannel,
                     remote1:SocketAddress, key1:SelectionKey) {
>>>>>>> 85695aa70d92d49480d7d92766a62777b473d960
        closed = false
        client = client1
        localListen = localListen1
        localClient = localClient1
        remote = remote1
<<<<<<< HEAD
=======
        key = key1
>>>>>>> 85695aa70d92d49480d7d92766a62777b473d960
    }

    fun reset() {
        closed = true
        client = null
        localListen = null
        localClient = null
<<<<<<< HEAD
        remote = null
    }
    fun remoteClientAddress():SocketAddress = client!!
    fun listenAddress():SocketAddress = localListen!!.localAddress
    fun targetAddress():SocketAddress = remote!!
    fun localClientAddress():SocketAddress = localClient!!.localAddress
=======
        key = null
        remote = null
    }
    fun remoteClientAddress():SocketAddress? = client
    fun listenAddress():SocketAddress? = localListen?.localAddress
    fun targetAddress():SocketAddress? = remote!!
    fun localClientAddress():SocketAddress? = localClient?.localAddress
>>>>>>> 85695aa70d92d49480d7d92766a62777b473d960

    fun isClosed():Boolean {
        return closed
    }

    fun close() {
        if(!closed) {
            closed = true
        }
    }
}