package net.wushilin.portforwarder.common

import java.time.ZonedDateTime

object Log {
    const val LEVEL_DEBUG = 1
    const val LEVEL_INFO = 2
    const val LEVEL_WARN = 3
    const val LEVEL_ERROR = 4
    var LOG_LEVEL:Int = 0
    var ENABLE_TS_IN_LOG = true

    fun debug(msg:String) {
        log(LEVEL_DEBUG, msg)
    }

    fun warn(msg:String) {
        log(LEVEL_WARN, msg)
    }

    fun info(msg:String) {
        log(LEVEL_INFO, msg)
    }

    fun error(msg: String) {
        log(LEVEL_ERROR, msg)
    }

    fun log(level: Int, msg: String) {
        if (level > LOG_LEVEL) {
            if (ENABLE_TS_IN_LOG) {
                println("${ZonedDateTime.now()} - $msg")
            } else {
                println(msg)
            }
        }
    }

    fun isInfoEnabled(): Boolean {
        return LEVEL_INFO > LOG_LEVEL
    }

    fun isDebugEnabled(): Boolean {
        return LEVEL_DEBUG > LOG_LEVEL
    }

    fun isWarnEnabled(): Boolean {
        return LEVEL_WARN > LOG_LEVEL
    }

    fun isErrorEnabled(): Boolean {
        return LEVEL_ERROR > LOG_LEVEL
    }

    fun printLogo() {
        if(isInfoEnabled()) {
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
            info("\n$logo")
        }
    }
}

