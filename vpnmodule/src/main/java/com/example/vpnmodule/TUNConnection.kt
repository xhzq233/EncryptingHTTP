package com.example.vpnmodule

import android.app.PendingIntent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress

typealias onEstablishHandler = (ParcelFileDescriptor) -> Unit

class TUNConnection(
    private val vpnService: VpnService,
    // Allowed/Disallowed packages for VPN usage
    private val allow: Boolean,
    private val packages: Set<String>,
    private val configureIntent: PendingIntent?
) : Runnable {

    private var mOnEstablishListener: onEstablishHandler? = null

    private val nativeLib = NativeLib()

    fun setOnEstablishListener(listener: onEstablishHandler?) {
        mOnEstablishListener = listener
    }

    override fun run() {
        var iface: ParcelFileDescriptor? = null
        try {
            iface = parcelFileDescriptor()
            // Now we are connected. Set the flag.

            // Packets to be sent are queued in this input stream.
            val `in` = FileInputStream(iface!!.fileDescriptor)

            // Allocate the buffer for a single packet.
            val packet = ByteArray(MAX_PACKET_SIZE)

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Read the outgoing packet from the input stream.
                val length = `in`.read(packet)
                if (length > 0) {
                    nativeLib.handleIpPkt(packet, length, iface.fd)
                } else if (length < 0) {
                    throw IOException("Tunnel EOF")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Connection failed, exiting", e)
        } finally {
            if (iface != null) {
                try {
                    iface.close()
                    ShinProxyService.deactivate()
                } catch (e: IOException) {
                    Log.e(tag, "Unable to close interface", e)
                }
            }
        }
    }


    private fun parcelFileDescriptor(): ParcelFileDescriptor? {
        // Configure a builder while parsing the parameters.
        val builder = vpnService.Builder()
        builder.addAddress("192.168.0.1", 16)
        builder.addRoute("0.0.0.0", 0)
        builder.allowFamily(OsConstants.AF_INET)

        try {
            if (allow) {
                packages.forEach { builder.addAllowedApplication(it) }
                // For debugging purposes
                if (!packages.contains("com.example.demoapp")) builder.addAllowedApplication("com.example.demoapp")
            } else {
                packages.forEach { builder.addDisallowedApplication(it) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Package not found", e)
        }

        builder.setSession(vpnService.getString(R.string.app_name))

        builder.setBlocking(true)
        if (configureIntent != null) {
            builder.setConfigureIntent(configureIntent)
        }
        val proxyAddress = ShinProxyService.servingProxy.address() as InetSocketAddress
        builder.setHttpProxy(ProxyInfo.buildDirectProxy(proxyAddress.hostString, proxyAddress.port))
        ShinProxyService.activate(context = vpnService)

        val vpnInterface: ParcelFileDescriptor? = builder.establish()
        if (mOnEstablishListener != null && vpnInterface != null) {
            mOnEstablishListener!!.invoke(vpnInterface)
        }

        Log.i(tag, "New interface: $vpnInterface")
        return vpnInterface
    }

    private val tag: String get() = TUNConnection::class.java.simpleName

    companion object {
        /**
         * Maximum packet size is constrained by the MTU, which is given as a signed short.
         */
        private const val MAX_PACKET_SIZE = Short.MAX_VALUE.toInt()
    }
}
