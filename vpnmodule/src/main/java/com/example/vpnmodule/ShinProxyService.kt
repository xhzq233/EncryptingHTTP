package com.example.vpnmodule

import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class ShinProxyService {
    companion object {

        private val TAG: String = ShinProxyService::class.java.simpleName

        val servingProxy: Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9999))

        var protector: ((Socket) -> Unit)? = null

        private var executors: ExecutorService? = null

        fun activate() {
            // Bind listening socket
            val socket = ServerSocket()
            socket.bind(servingProxy.address())

            executors = Executors.newFixedThreadPool(10)

            executors?.submit {
                while (true) {
                    val proxyClient: Socket = socket.accept()
                    Log.i(TAG, "New client from ${proxyClient.remoteSocketAddress}")

                    executors?.submit {
                        forwardHTTPPkt(proxyClient)
                    }
                }
            }
        }

        fun deactivate() {
            executors?.shutdown()
        }

        private fun getUrlFromPkt(buffer: ByteArray, lengthRead: Int): URL {
            // Read the first line of the pkt, which contains the host
            // Find '\r\n' to get the end of the first line
            val rAscii: Int = '\r'.code
            val nAscii: Int = '\n'.code
            var i = 0
            while (i < lengthRead) {
                if (buffer[i].toInt() == rAscii && buffer[i + 1].toInt() == nAscii) {
                    break
                }
                i++
            }
            val firstLine = buffer.sliceArray(0 until i)

            // Extract the url from the first line(eg. GET http://www.google.com/ HTTP/1.1)
            val url = String(firstLine).split(" ")[1]
            Log.d(TAG, "URL: $url")
            if (!url.startsWith("http")) {
                if (url.contains(":")) {
                    val parts = url.split(":")
                    val port = if (parts.size == 1) 80 else parts[1].toInt()
                    val scheme = if (port == 443) "https" else "http"
                    return URL("$scheme://$url")
                }
                return URL("http://$url")
            }
            return URL(url)
        }

        private fun debugPrint(buffer: ByteArray, lengthRead: Int, socket: Socket) {
            Log.d(TAG, "Received $lengthRead bytes from ${socket.remoteSocketAddress}")
            Log.d(TAG, String(buffer.sliceArray(0 until lengthRead)))
        }

        private fun forwardHTTPPkt(proxyClient: Socket) {
            val buffer = ByteArray(4096)

            val clientIn = proxyClient.getInputStream()
            val clientOut = proxyClient.getOutputStream()

            val lengthRead = clientIn.read(buffer)

            val url = getUrlFromPkt(buffer, lengthRead)
            val destination: InetAddress = InetAddress.getByName(url.host)
            Log.d(TAG, "DNS resolved $url to $destination")

            // Connect and send to the destination
            val server = Socket()
            if (protector != null) protector!!(server)
            val port = if (url.port == -1) url.defaultPort else url.port
            server.connect(InetSocketAddress(destination, port))

            val serverIn = server.getInputStream()
            val serverOut = server.getOutputStream()

            if (url.protocol == "https") {
                // Send a 200 OK response to the client
                val response = "HTTP/1.1 200 Connection Established\r\n" +
                        "Proxy-agent: ShinProxyService\r\n" +
                        "\r\n"
                clientOut.write(response.toByteArray())

                executors?.submit {
                    clientIn.copyTo(serverOut)
                    Log.i(TAG, "Closing client connection from ${proxyClient.remoteSocketAddress}")
                    proxyClient.close()
                    server.close()
                }
                executors?.submit {
                    serverIn.copyTo(clientOut)
                    Log.i(TAG, "Closing server connection to ${server.remoteSocketAddress}")
                    server.close()
                    proxyClient.close()
                }
            } else {
                serverOut.write(buffer, 0, lengthRead)

                executors?.submit {
                    serverIn.copyTo(clientOut)
                    Log.i(TAG, "Closing server connection to ${server.remoteSocketAddress}")
                    server.close()
                    proxyClient.close()
                }
                executors?.submit {
                    clientIn.copyTo(serverOut)
                    Log.i(TAG, "Closing client connection from ${proxyClient.remoteSocketAddress}")
                    proxyClient.close()
                    server.close()
                }
            }
        }
    }

}