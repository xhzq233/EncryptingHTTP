package com.example.vpnmodule

class NativeLib {

    // Return 1 if the packet is to be write back into the TUN interface, 0 if the packet is to be sent to localloop
    external fun handleIpPkt(ipPkt: ByteArray, length: Int): Int

    companion object {
        // Used to load the 'nativelib' library on application startup.
        init {
            System.loadLibrary("nativelib")
        }
    }
}