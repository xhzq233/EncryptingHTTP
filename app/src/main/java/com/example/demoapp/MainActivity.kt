package com.example.demoapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.demoapp.ui.theme.DemoAppTheme
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.lang.StringBuilder
import java.net.Socket


class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DemoAppTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Request(client)
                }
            }
        }
    }
}

@Composable
@Preview
fun Request(client: OkHttpClient = OkHttpClient()) {
    val stringResp: MutableState<String> = remember { mutableStateOf("None") }

    val callback = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            stringResp.value = e.message.toString()
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
            stringResp.value = sb.toString()
        }
    }

    fun request(url: String) {
        stringResp.value = "None"
        val req = Request.Builder()
            .url(url)
            .build()

        client.newCall(req).enqueue(callback)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button({ request("http://http3.is") }) { Text(text = "Request HTTP") }
        Button({ request("https://http2.pro/api/v1") }) { Text(text = "Request HTTPS(HTTP2)") }
        Button({ request("https://http3.is") }) { Text(text = "Request HTTPS(HTTP3)") }
        Divider()
        Text(
            stringResp.value,
            Modifier
                .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                .padding(2.dp)
                .align(Alignment.CenterHorizontally)
        )
    }
}