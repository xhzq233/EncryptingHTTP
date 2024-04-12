package com.example.xposedmodule

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketAddress

@Suppress("unused")
class HookHTTP : IXposedHookLoadPackage {
    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!lpparam.packageName.contains("com.example.demoapp")) return

        if (XposedHelpers.findClassIfExists("java.net.Socket", lpparam.classLoader) == null) {
            return
        }

        XposedBridge.log("java.net.Socket found in " + lpparam.packageName)

        XposedHelpers.findAndHookMethod(
            "java.net.Socket",
            lpparam.classLoader,
            "connect",
            SocketAddress::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val sa = param.args[0] as SocketAddress
                    val timeout = param.args[1] as Int
                    XposedBridge.log("java.net.Socket.connect $sa timeout:$timeout")
                }
            }
        )



        XposedHelpers.findAndHookMethod(
            "java.net.Socket",
            lpparam.classLoader,
            "getInputStream",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val realInputStream = param.result as InputStream
                    Thread {
                        val bytes = ByteArray(1000)
                        while (true) {
                            val nBytes = realInputStream.read(bytes)
                            if (nBytes == 0) break
                            XposedBridge.log("Receive: " + String(bytes))
                        }
                    }
                    param.result = ByteArrayInputStream(byteArrayOf())
                    XposedBridge.log("java.net.Socket.getInputStream hooked")
                }
            }
        )


        // Hook getOutputStream
        XposedHelpers.findAndHookMethod(
            "java.net.Socket",
            lpparam.classLoader,
            "getOutputStream",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val realOutputStream = param.result as InputStream
                    Thread {
                        val bytes = ByteArray(1000)
                        while (true) {
                            val nBytes = realOutputStream.read(bytes)
                            if (nBytes == 0) break
                            XposedBridge.log("Send: " + String(bytes))
                        }
                    }
                    param.result = ByteArrayInputStream(byteArrayOf())
                    XposedBridge.log("java.net.Socket.getOutputStream hooked")
                }
            }
        )

    }
}