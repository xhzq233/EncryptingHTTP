package com.example.vpnmodule

class NativeLib {

    external fun handleIpPkt(ipPkt: ByteArray, length: Int, tunFd: Int)

    external fun listenTunTcp()

    companion object {
        // Used to load the 'nativelib' library on application startup.
        init {
            System.loadLibrary("nativelib")
        }
    }
}