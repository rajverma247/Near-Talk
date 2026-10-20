package com.neartalk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

val APP_UUID: UUID = UUID.fromString("7f0a0001-3c1d-4a6b-9c3e-6e6561727461")

/**
 * Bluetooth Classic RFCOMM link. Frame = [type:1][length:4][payload].
 * Types: 1=MSG 2=ACK 3=CALL_REQ 4=CALL_ACCEPT 5=CALL_END 6=AUDIO
 */
@SuppressLint("MissingPermission")
class Conn(private val adapter: BluetoothAdapter, private val cb: Callback) {
    interface Callback {
        fun onState(s: String)                       // main thread
        fun onFrame(type: Int, data: ByteArray)      // reader thread
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null
    @Volatile var connected = false
    @Volatile private var gen = 0

    private fun post(g: Int, s: String) { if (g == gen) main.post { cb.onState(s) } }

    fun listen() {
        val g = stopAll()
        Thread {
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("NearTalk", APP_UUID)
                post(g, "Waiting for a device…")
                val s = server!!.accept()
                server?.close()
                attach(s, g)
            } catch (e: Exception) { post(g, "Stopped: ${e.message}") }
        }.start()
    }

    fun connect(d: BluetoothDevice) {
        val g = stopAll()
        Thread {
            try {
                post(g, "Connecting…")
                val s = d.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                attach(s, g)
            } catch (e: Exception) { post(g, "Connect failed: ${e.message}") }
        }.start()
    }

    private fun attach(s: BluetoothSocket, g: Int) {
        socket = s
        out = DataOutputStream(BufferedOutputStream(s.outputStream))
        connected = true
        post(g, "Connected to ${s.remoteDevice.name ?: s.remoteDevice.address}")
        try {
            val inp = DataInputStream(BufferedInputStream(s.inputStream))
            while (true) {
                val t = inp.readUnsignedByte()
                val len = inp.readInt()
                val d = ByteArray(len)
                inp.readFully(d)
                cb.onFrame(t, d)
            }
        } catch (e: Exception) { }
        connected = false
        try { s.close() } catch (e: IOException) { }
        post(g, "Disconnected")
    }

    fun send(type: Int, data: ByteArray, done: ((Boolean) -> Unit)? = null) {
        exec.execute {
            val ok = try {
                val o = out ?: throw IOException("no link")
                synchronized(o) { o.writeByte(type); o.writeInt(data.size); o.write(data); o.flush() }
                true
            } catch (e: Exception) { false }
            done?.invoke(ok)
        }
    }

    fun stopAll(): Int {
        gen++
        connected = false
        try { server?.close() } catch (e: IOException) { }
        try { socket?.close() } catch (e: IOException) { }
        return gen
    }
}
