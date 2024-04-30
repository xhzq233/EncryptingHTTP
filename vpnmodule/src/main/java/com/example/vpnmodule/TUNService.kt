package com.example.vpnmodule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Pair
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TUNService : VpnService() {
    private class Connection(thread: Thread?, pfd: ParcelFileDescriptor?) :
        Pair<Thread?, ParcelFileDescriptor?>(thread, pfd)

    private val mConnectingThread = AtomicReference<Thread?>()
    private val mConnection = AtomicReference<Connection?>()
    private var mConfigureIntent: PendingIntent? = null
    override fun onCreate() {

        // Create the intent to "configure" the connection (Tap to start MainActivity).
        mConfigureIntent = PendingIntent
            .getActivity(
                this, 0,
                Intent(this, TUNActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (intent != null && ACTION_DISCONNECT == intent.action) {
            disconnect()
            START_NOT_STICKY
        } else {
            connect()
            START_STICKY
        }
    }

    override fun onDestroy() {
        disconnect()
    }

    private fun connect() {
        // Become a foreground service. Background services can be VPN services too, but they can
        // be killed by background check before getting a chance to receive onRevoke().
        notifyUser(R.string.connecting)

        // Extract information from the shared preferences.
        val prefs = getSharedPreferences(TUNActivity.Prefs.NAME, MODE_PRIVATE)
        val allow = prefs.getBoolean(TUNActivity.Prefs.ALLOW, true)
        val packages: Set<String> = prefs.getStringSet(TUNActivity.Prefs.PACKAGES, emptySet<String>())!!

        val connection = TUNConnection(
            this, allow, packages, mConfigureIntent
        )

        // Replace any existing connecting thread with the  new one.
        val thread = Thread(connection, "ShinVpnThread")
        setConnectingThread(thread)

        // Handler to mark as connected once onEstablish is called.
        connection.setOnEstablishListener { tunInterface: ParcelFileDescriptor? ->
            notifyUser(R.string.connected)

            mConnectingThread.compareAndSet(thread, null)
            setConnection(Connection(thread, tunInterface))
        }
        thread.start()
    }

    private fun notifyUser(res: Int) {
        mainExecutor.execute {
            Toast.makeText(this@TUNService, res, Toast.LENGTH_SHORT).show()
            updateForegroundNotification(res)
        }
    }

    private fun setConnectingThread(thread: Thread?) {
        val oldThread = mConnectingThread.getAndSet(thread)
        oldThread?.interrupt()
    }

    private fun setConnection(connection: Connection?) {
        val oldConnection = mConnection.getAndSet(connection)
        if (oldConnection != null) {
            try {
                oldConnection.first!!.interrupt()
                oldConnection.second!!.close()
            } catch (e: IOException) {
                Log.e(TAG, "Closing VPN interface", e)
            }
        }
    }

    private fun disconnect() {
        Toast.makeText(this, R.string.disconnected, Toast.LENGTH_SHORT).show()
        setConnectingThread(null)
        setConnection(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updateForegroundNotification(message: Int) {

        val mNotificationManager = getSystemService(
            NOTIFICATION_SERVICE
        ) as NotificationManager
        mNotificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        startForeground(
            1, Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentText(getString(message))
                .setContentIntent(mConfigureIntent)
                .build()
        )
    }

    companion object {
        private val TAG = TUNService::class.java.simpleName
        const val ACTION_CONNECT = "com.example.vpnmodule.START"
        const val ACTION_DISCONNECT = "com.example.vpnmodule.STOP"
        const val NOTIFICATION_CHANNEL_ID = "ShinVpn"
    }
}
