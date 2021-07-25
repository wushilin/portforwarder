package net.wushilin.portforwarder.common

object Config {
    fun <T> get(key:String, defaultValue:T, print:Boolean = true, converter:(String)->T):T{
        val result = getInternal(key, defaultValue, print, converter)
        if(print && Log.isInfoEnabled()) {
            Log.info("$key = $result")
        }
        return result
    }

    private inline fun <T> getInternal(key:String, defaultValue:T, print:Boolean=true, converter:(String) -> T):T {
        val sysProps = System.getProperty(key)
        if(sysProps == null || sysProps.isBlank()) {
            if(print && Log.isDebugEnabled()) {
                Log.debug("$key is not defined, using default $defaultValue")
            }
            return defaultValue
        }

        return try {
            val result = converter(sysProps)
            result
        } catch(ex:Exception) {
            if(Log.isWarnEnabled()) {
                Log.warn("Failed to convert $key = $sysProps to desired value. Using $defaultValue instead($ex)")
            }
            defaultValue
        }
    }
}