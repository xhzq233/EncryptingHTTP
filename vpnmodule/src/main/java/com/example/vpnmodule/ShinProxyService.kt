package com.example.vpnmodule

import android.util.Log
import com.google.net.cronet.okhttptransport.CronetCallFactory
import okhttp3.Call
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.chromium.net.CronetEngine
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class ShinProxyService {
    companion object {

        private val TAG: String = ShinProxyService::class.java.simpleName

        val servingProxy: Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9999))

        var protector: ((Socket) -> Unit)? = null

        private var executors: ExecutorService? = null
        private var serverSocket: ServerSocket? = null
        private var cronetEngine: CronetEngine? = null
        private var callFactory: Call.Factory? = null

        fun activate(context: android.content.Context) {
            // Bind listening socket
            serverSocket = ServerSocket()
            serverSocket!!.bind(servingProxy.address())

            executors = Executors.newFixedThreadPool(10)

            cronetEngine = CronetEngine.Builder(context).build()

            callFactory = CronetCallFactory.newBuilder(cronetEngine).build()

            val socket = serverSocket!!
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
            executors = null
            cronetEngine?.shutdown()
            cronetEngine = null
            callFactory = null
            serverSocket?.close()
            serverSocket = null
        }

        private fun forwardHTTPPkt(proxyClient: Socket) {
            val clientIn = proxyClient.getInputStream()
            val clientOut = proxyClient.getOutputStream()

            val header = parseHttpHead(BufferedInputStream(clientIn))
            val url = header.url

            if (url.protocol == "https") {
                val destination: InetAddress = InetAddress.getByName(url.host)
                Log.d(TAG, "DNS resolved $url to $destination")
                // Connect and send to the destination
                val server = Socket()
                if (protector != null) protector!!(server)
                val port = if (url.port == -1) url.defaultPort else url.port
                server.connect(InetSocketAddress(destination, port))

                val serverIn = server.getInputStream()
                val serverOut = server.getOutputStream()

                Log.i(TAG, "HTTPS Connected to ${server.remoteSocketAddress}")

                // Send a 200 OK response to the client
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                val clientSideFuture = executors?.submit {
                    clientIn.copyTo(serverOut)
                }
                executors?.submit {
                    serverIn.copyTo(clientOut)
                    clientSideFuture?.cancel(true)
                    server.close()
                    proxyClient.close()
                    Log.i(TAG, "Closed server connection to ${server.remoteSocketAddress}")
                }
            } else {
                val newUrl = URL(url.toString().replace("http", "https"))
                Log.d(TAG, "Upgrading $url to $newUrl")
                val newHeader =
                    HttpHead(header.version, header.method, newUrl, header.headers, header.mediaType, header.body)
                upgradeToSecureHttp3(newHeader, clientOut)
                proxyClient.close()
            }

        }

        private fun upgradeToSecureHttp3(httpHead: HttpHead, clientOut: OutputStream) {
            val request = okhttp3.Request.Builder()
                .url(httpHead.url)
                .method(httpHead.method, httpHead.body?.toRequestBody(httpHead.mediaType))
                .headers(httpHead.headers.toHeaders())
                .build()
            Log.d(TAG, "${request.method}(${request.url}) headers: ${request.headers}")

            val call = callFactory?.newCall(request)
            val response: Response? = call?.execute()

            if (response == null) {
                Log.e(TAG, "Failed to get response from $httpHead")
                return
            }
            Log.d(TAG, "Response: ${response.code} ${response.message}")
            val responseBuffer = ByteArrayOutputStream()

            responseBuffer.write("${httpHead.version} ${response.code} ${response.message}\r\n".toByteArray())
            response.headers.forEach {
                responseBuffer.write("${it.first}: ${it.second}\r\n".toByteArray())
            }
            responseBuffer.write("\r\n".toByteArray())
            clientOut.write(responseBuffer.toByteArray())

            response.body?.bytes()?.let {
                Log.d(TAG, "Response body: ${String(it)}")
                clientOut.write(it)
            }
            response.close()
        }

        private class HttpHead(
            val version: String,
            val method: String,
            val url: URL,
            val headers: Map<String, String>,
            val mediaType: MediaType? = null,
            val body: ByteArray? = null
        )

        private fun InputStream.readLine(): String {
            val builder = StringBuilder()
            var haveCR = false
            while (true) {
                val byte = this.read()
                if (byte == -1) break
                // Check for CRLF
                if (byte == 10 && haveCR) break
                if (byte == 13) {
                    haveCR = true
                    continue
                }
                builder.append(byte.toChar())
            }
            Log.d(TAG, "Read line: $builder")
            return builder.toString()
        }

        private fun parseHttpHead(inputStream: InputStream): HttpHead {
            val firstLine = inputStream.readLine().trim()
            val headers = mutableMapOf<String, String>()
            var line = inputStream.readLine()
            while (line.isNotEmpty()) {
                val parts = line.split(":")
                headers[parts[0]] = parts[1]
                line = inputStream.readLine()
            }
            // Extract from the first line(eg. GET http://www.google.com/ HTTP/1.1)
            val splits = firstLine.split(" ")
            val method = splits[0]
            val urlString = splits[1]
            val version = splits[2]
            val url = if (!urlString.startsWith("http")) {
                URL("https://$urlString")
            } else URL(urlString)

            // Body
            val body = if (headers.containsKey("Content-Length")) {
                val length = headers["Content-Length"]!!.toInt()
                val buffer = ByteArray(length)
                inputStream.read(buffer)
                buffer
            } else null

            val mediaType = if (headers.containsKey("Content-Type")) {
                headers["Content-Type"]!!.toMediaTypeOrNull()
            } else null

            Log.d(TAG, "Parsed HTTP head: $method $url $version $headers $mediaType ${body?.size} bytes")

            return HttpHead(version, method, url, headers, mediaType, body)
        }
    }

}