package com.example.vpnmodule

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.google.android.gms.net.CronetProviderInstaller
import com.google.net.cronet.okhttptransport.CronetCallFactory
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.chromium.net.CronetEngine
import java.util.stream.Collectors


class TUNActivity : ComponentActivity() {
    interface Prefs {
        companion object {
            const val NAME = "connection"
            const val ALLOW = "allow"
            const val PACKAGES = "packages"
        }
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
    }

    @Composable
    fun ConnectButton(packages: State<String>, allowed: State<Boolean>) {

        Button(onClick = {
            val packageSet = packages.value.split(",").dropLastWhile { it.isEmpty() }
                .map { obj: String -> obj.trim { it <= ' ' } }
                .filter { s: String -> s.isNotEmpty() }
                .toSet()

            if (!checkPackages(packageSet)) {
                return@Button
            }
            prefs.edit()
                .putBoolean(Prefs.ALLOW, allowed.value)
                .putStringSet(Prefs.PACKAGES, packageSet)
                .apply()
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 0)
            } else {
                onActivityResult(0, RESULT_OK, null)
            }
        }) {
            Text("Connect")
        }
    }

    @Composable
    fun DisconnectButton() {
        Button(onClick = {
            startService(
                serviceIntent.setAction(TUNService.ACTION_DISCONNECT)
            )
        }) {
            Text("Disconnect")
        }
    }
    @Composable
    fun TestHTTP3ConnectionButton() {
        Button(onClick = {
            val cronetEngine = CronetEngine.Builder(this).enableQuic(true).build()

            val callFactory = CronetCallFactory.newBuilder(cronetEngine).build()

            val request = Request.Builder()
                .url("https://xhzq.xyz:8889/test")
                .build()

            callFactory.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("DEMO", "onFailure", e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val sb = StringBuilder()
                    sb.append(response.toString())
                    if (response.handshake != null) {
                        sb.append("Handshake: ").append(response.handshake).append("\n")
                    }
                    sb.append(response.code).append(" ").append(response.message).append("\n")
                    response.headers.forEach {
                        sb.append(it.first).append(": ").append(it.second).append("\n")
                    }
                    sb.append("\n")
                    response.body?.string()?.let {
                        sb.append(it)
                    }
                    Log.i("DEMO", "onResponse\n$sb")
                }
            })
        }) {
            Text("Test HTTP/3 Connection")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CronetProviderInstaller.installProvider(this)
        setContent {
            val packages = remember {
                mutableStateOf("")
            }
            val allowed = remember {
                mutableStateOf(true)
            }

            val keyboardController = LocalSoftwareKeyboardController.current

            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                TextField(
                    value = packages.value,
                    trailingIcon = {
                             Button(onClick = {
                                 currentFocus?.clearFocus()
                                 keyboardController?.hide()
                             }) {
                                 Text("Done")
                             }
                    },
                    onValueChange = { packages.value = it },
                    placeholder = { Text("Allowed Package names") }
                )
                ConnectButton(packages, allowed)
                DisconnectButton()
                TestHTTP3ConnectionButton()
            }
        }
    }

    private fun checkPackages(packageNames: Set<String>): Boolean {

        val apps = packageManager.getInstalledPackages(0).stream()
            .map { it.packageName }
            .collect(Collectors.toSet())

        val hasCorrectPackageNames = packageNames.isEmpty() || apps.containsAll(packageNames)

        if (!hasCorrectPackageNames) {
            Toast.makeText(this, R.string.unknown_package_names, Toast.LENGTH_SHORT).show()
        }
        return hasCorrectPackageNames
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (result == RESULT_OK) {
            startService(serviceIntent.setAction(TUNService.ACTION_CONNECT))
        }
        super.onActivityResult(request, result, data)
    }

    private val serviceIntent: Intent
        get() = Intent(this, TUNService::class.java)
}

