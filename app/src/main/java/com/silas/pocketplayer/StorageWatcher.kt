package com.silas.pocketplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * OTG 插拔感知：MEDIA_MOUNTED/UNMOUNTED 覆盖系统挂载的 U 盘，
 * USB_DEVICE_ATTACHED/DETACHED 覆盖 USB Host 设备。两者都是动态注册，不需要常驻服务。
 */
class StorageWatcher(context: Context, private val onChanged: (String) -> Unit) {

    private val app = context.applicationContext
    private var registered = false

    private fun receiver() = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            onChanged(intent?.action.orEmpty())
        }
    }

    private val mediaReceiver = receiver()
    private val usbReceiver = receiver()

    fun register() {
        if (registered) return
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        val usbFilter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
            addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(mediaReceiver, mediaFilter, Context.RECEIVER_EXPORTED)
                app.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(mediaReceiver, mediaFilter)
                app.registerReceiver(usbReceiver, usbFilter)
            }
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { app.unregisterReceiver(mediaReceiver) }
        runCatching { app.unregisterReceiver(usbReceiver) }
        registered = false
    }

    /** 当前已连接的 USB 设备数，用于 OTG 面板标题提示（只读，无需 USB 权限） */
    fun usbDeviceCount(): Int = runCatching {
        (app.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.size
    }.getOrDefault(0)
}
