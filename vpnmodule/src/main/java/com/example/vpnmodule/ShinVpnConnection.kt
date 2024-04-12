package com.example.vpnmodule

import android.app.PendingIntent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer

typealias onEstablishHandler = (ParcelFileDescriptor) -> Unit

class ShinVpnConnection(
    private val mService: VpnService,
    private val mConnectionId: Int,
    allow: Boolean,
    packages: Set<String>,
    private val mConfigureIntent: PendingIntent?
) : Runnable {

    private var mOnEstablishListener: onEstablishHandler? = null

    // Allowed/Disallowed packages for VPN usage
    private val mAllow: Boolean
    private val mPackages: Set<String>

    init {
        mAllow = allow
        mPackages = packages
    }

    fun setOnEstablishListener(listener: onEstablishHandler?) {
        mOnEstablishListener = listener
    }

    override fun run() {
        var iface: ParcelFileDescriptor? = null
        try {
            Log.i(tag, "Starting")
            iface = parcelFileDescriptor()
            // Now we are connected. Set the flag.

            // Packets to be sent are queued in this input stream.
            val `in` = FileInputStream(iface!!.fileDescriptor)

            // Packets received need to be written to this output stream.
            val out = FileOutputStream(iface.fileDescriptor)

            // Allocate the buffer for a single packet.
            val packet = ByteBuffer.allocate(MAX_PACKET_SIZE)

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Read the outgoing packet from the input stream.
                val length = `in`.read(packet.array())
                if (length > 0) {
                    // Write the outgoing packet to the tunnel.
                    packet.limit(length)
//                    val res = NativeLib().handleIpPkt(packet.array(), length)
                    out.write(packet.array(), 0, length)
                } else if (length < 0) {
                    throw IOException("Tunnel EOF")
                }
            }
        } catch (e: IOException) {
            Log.e(tag, "Connection failed, exiting", e)
        } catch (e: InterruptedException) {
            Log.e(tag, "Connection failed, exiting", e)
        } catch (e: IllegalArgumentException) {
            Log.e(tag, "Connection failed, exiting", e)
        } catch (e: SocketException) {
            Log.e(tag, "Cannot use socket", e)
        } finally {
            if (iface != null) {
                try {
                    iface.close()
                } catch (e: IOException) {
                    Log.e(tag, "Unable to close interface", e)
                }
            }
        }
    }


    private fun parcelFileDescriptor(): ParcelFileDescriptor? {
        // Configure a builder while parsing the parameters.
        val builder = mService.Builder()
        builder.addAddress("192.168.0.1", 16)
        builder.addRoute("0.0.0.0", 0)
        builder.allowFamily(OsConstants.AF_INET)

        //             Protect my package's traffic.
//        builder.addDisallowedApplication(mService.packageName)
        builder.addAllowedApplication("com.example.demoapp")
//        builder.addAllowedApplication(mService.packageName)

        builder.setSession(mService.getString(R.string.app_name))

        if (mConfigureIntent != null) {
            builder.setConfigureIntent(mConfigureIntent)
        }
        val proxyAddress = ShinProxyService.servingProxy.address() as InetSocketAddress
        builder.setHttpProxy(ProxyInfo.buildDirectProxy(proxyAddress.hostString, proxyAddress.port))
        ShinProxyService.activate()

        val vpnInterface: ParcelFileDescriptor? = builder.establish()
        if (mOnEstablishListener != null && vpnInterface != null) {
            mOnEstablishListener!!.invoke(vpnInterface)
        }

        Log.i(tag, "New interface: $vpnInterface")
        return vpnInterface
    }

    private val tag: String
        get() = ShinVpnConnection::class.java.simpleName + "[" + mConnectionId + "]"

    companion object {
        /**
         * Maximum packet size is constrained by the MTU, which is given as a signed short.
         */
        private const val MAX_PACKET_SIZE = Short.MAX_VALUE.toInt()
    }
}
