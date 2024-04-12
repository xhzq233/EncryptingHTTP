package com.example.vpnmodule

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import java.net.URL
import java.util.Arrays
import java.util.stream.Collectors


class ShinVpnActivity : ComponentActivity() {
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
                serviceIntent.setAction(ShinVpnService.ACTION_DISCONNECT)
            )
        }) {
            Text("Disconnect")
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val packages = remember {
                mutableStateOf("")
            }
            val allowed = remember {
                mutableStateOf(true)
            }

            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                TextField(
                    value = packages.value,
                    onValueChange = { packages.value = it },
                    placeholder = { Text("Package names") })
                Row {
                    RadioButton(selected = allowed.value, onClick = { allowed.value = true })
                    Text("Allow")
                    RadioButton(selected = !allowed.value, onClick = { allowed.value = false })
                    Text("Disallow")
                }
                ConnectButton(packages, allowed)
                DisconnectButton()

                Divider()

                val testResponse = remember { mutableStateOf("") }

                Button(onClick = {
                    // Test connection
                    Log.i("ShinProxyService", "Testing connection")

                    Thread {
                        // Get from http://cip.cc
                        testResponse.value = URL("http://cip.cc").readText()
                    }.start()
                }) {
                    // Test connection
                    Text(text = "Test Connection")
                }

                Text(text = testResponse.value)
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
            startService(serviceIntent.setAction(ShinVpnService.ACTION_CONNECT))
        }
        super.onActivityResult(request, result, data)
    }

    private val serviceIntent: Intent
        get() = Intent(this, ShinVpnService::class.java)
}

