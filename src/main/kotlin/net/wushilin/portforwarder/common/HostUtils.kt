package net.wushilin.portforwarder.common

import java.net.InetSocketAddress
import java.net.SocketAddress

object HostUtils {
    fun parse(args:Array<String>):Map<SocketAddress, Pair<String, Int>> {
        val result = mutableMapOf<SocketAddress, Pair<String, Int>>()
        for(next in args) {
            val tokens = next.split("::")
            if(tokens.size != 2) {
                throw IllegalArgumentException("Invalid binding $next")
            }
            val bind = tokens[0]
            val target = tokens[1]
            result[convertToAddress(bind)] = convertToHostAndPort(target)
        }
        return result
    }

    fun convertToAddress(arg:Pair<String, Int>):SocketAddress {
        return InetSocketAddress(arg.first, arg.second)
    }

    fun convertToAddress(input:String):SocketAddress {
        val pair = convertToHostAndPort(input)
        return convertToAddress(pair)
    }

    fun convertToHostAndPort(input:String):Pair<String, Int> {
        val tokens = input.split(":")
        if(tokens.size != 2) {
            throw IllegalArgumentException("Invalid host:port pair: $input")
        }
        var port:Int
        try {
            port = tokens[1].toInt()
        } catch(ex:Exception) {
            throw IllegalArgumentException("Invalid port ${tokens[1]}")
        }
        checkPort(port)
        return Pair(tokens[0], port)
    }

    private fun checkPort(port:Int) {
        if(port < 1 || port > 65535) {
            throw java.lang.IllegalArgumentException("Invalid port $port")
        }
    }
}