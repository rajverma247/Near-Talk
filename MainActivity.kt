package com.neartalk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

data class Msg(val id: Long, val text: String, val mine: Boolean, var status: String) {
    override fun toString() = if (mine) "Me: $text   [$status]" else "Peer: $text"
}

@SuppressLint("MissingPermission")
class MainActivity : Activity(), Conn.Callback {
    private var adapter: BluetoothAdapter? = null
    private lateinit var conn: Conn
    private val msgs = ArrayList<Msg>()
    private lateinit var chat: ArrayAdapter<Msg>
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var callBtn: Button
    private val devices = ArrayList<BluetoothDevice>()
    private var callState = 0 // 0 idle, 1 calling, 2 ringing, 3 active
    private var track: AudioTrack? = null
    @Volatile private var recording = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val a = adapter
        if (a == null) { toast("This phone has no Bluetooth"); finish(); return }
        conn = Conn(a, this)
        load()

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            fitsSystemWindows = true
        }
        status = TextView(this).apply { text = "Not connected"; textSize = 16f }
        spinner = Spinner(this)
        val connectBtn = Button(this).apply { text = "Connect"; setOnClickListener { connectSelected() } }
        val row1 = LinearLayout(this)
        row1.addView(spinner, LinearLayout.LayoutParams(0, wrap, 1f))
        row1.addView(connectBtn)

        val listenBtn = Button(this).apply { text = "Wait for connection"; setOnClickListener { conn.listen() } }
        callBtn = Button(this).apply { text = "Call"; setOnClickListener { onCallClick() } }
        val row2 = LinearLayout(this)
        row2.addView(listenBtn)
        row2.addView(callBtn)

        val listView = ListView(this)
        chat = ArrayAdapter(this, android.R.layout.simple_list_item_1, msgs)
        listView.adapter = chat
        listView.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        listView.setOnItemClickListener { _, _, pos, _ ->
            val m = msgs[pos]
            if (m.mine && m.status == "failed") { m.status = "sending"; sendMsg(m); refresh() }
        }

        val input = EditText(this).apply { hint = "Type a message" }
        val sendBtn = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    input.setText("")
                    val m = Msg(System.currentTimeMillis(), t, true, "sending")
                    msgs.add(m); save(); sendMsg(m); refresh()
                }
            }
        }
        val row3 = LinearLayout(this)
        row3.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))
        row3.addView(sendBtn)

        root.addView(status)
        root.addView(row1)
        root.addView(row2)
        root.addView(listView, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(row3)
        setContentView(root)

        val need = ArrayList<String>()
        need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) loadDevices() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
    }

    private fun loadDevices() {
        val a = adapter ?: return
        if (!a.isEnabled) { toast("Turn Bluetooth on first"); return }
        devices.clear()
        devices.addAll(a.bondedDevices)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            devices.map { it.name ?: it.address })
        if (devices.isEmpty()) status.text = "Pair both phones in system Bluetooth settings first, then reopen the app."
    }

    private fun connectSelected() {
        val i = spinner.selectedItemPosition
        if (i in devices.indices) conn.connect(devices[i]) else toast("No paired device selected")
    }

    private fun refresh() = chat.notifyDataSetChanged()

    // ---- Messaging: offline-first queue, ACK = delivered, tap failed to retry ----
    private fun sendMsg(m: Msg) {
        if (!conn.connected) return // stays "sending" and is flushed on connect
        val b = m.text.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(8 + b.size).putLong(m.id).put(b).array()
        conn.send(1, payload) { ok ->
            runOnUiThread {
                if (ok) { if (m.status != "delivered") m.status = "sent" } else m.status = "failed"
                save(); refresh()
            }
        }
    }

    // ---- Conn.Callback ----
    override fun onState(s: String) {
        status.text = s
        if (conn.connected) msgs.filter { it.mine && it.status != "delivered" }.forEach { sendMsg(it) }
        else stopCall()
    }

    override fun onFrame(type: Int, data: ByteArray) {
        when (type) {
            1 -> {
                val id = ByteBuffer.wrap(data).long
                val text = String(data, 8, data.size - 8, Charsets.UTF_8)
                conn.send(2, ByteBuffer.allocate(8).putLong(id).array())
                runOnUiThread {
                    if (msgs.none { it.id == id && !it.mine }) { msgs.add(Msg(id, text, false, "")); save(); refresh() }
                }
            }
            2 -> {
                val id = ByteBuffer.wrap(data).long
                runOnUiThread { msgs.find { it.id == id && it.mine }?.let { it.status = "delivered"; save(); refresh() } }
            }
            3 -> runOnUiThread { callState = 2; callBtn.text = "Accept call"; toast("Incoming call") }
            4 -> runOnUiThread { callState = 3; startAudio() }
            5 -> runOnUiThread { stopCall() }
            6 -> track?.write(data, 0, data.size)
        }
    }

    // ---- Calls: 8 kHz mono PCM over the same RFCOMM link (use headphones to avoid echo) ----
    private fun onCallClick() {
        if (!conn.connected) { toast("Connect to a device first"); return }
        when (callState) {
            0 -> { conn.send(3, ByteArray(0)); callState = 1; callBtn.text = "Cancel call" }
            2 -> { conn.send(4, ByteArray(0)); callState = 3; startAudio() }
            else -> { conn.send(5, ByteArray(0)); stopCall() }
        }
    }

    private fun startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission needed"); return
        }
        callBtn.text = "End call"
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        val min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(8000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, 3200)).build().also { it.play() }
        recording = true
        Thread {
            val rmin = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(rmin, 3200))
            rec.startRecording()
            val buf = ByteArray(320) // 20 ms
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) conn.send(6, buf.copyOf(n))
            }
            rec.stop(); rec.release()
        }.start()
    }

    private fun stopCall() {
        recording = false
        track?.let { try { it.stop() } catch (e: IllegalStateException) { }; it.release() }
        track = null
        (getSystemService(AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_NORMAL
        callState = 0
        callBtn.text = "Call"
    }

    // ---- Local storage (SharedPreferences JSON; swap for SQLite/Room later) ----
    private fun save() {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.text).put("m", it.mine).put("s", it.status)) }
        getSharedPreferences("neartalk", MODE_PRIVATE).edit().putString("msgs", arr.toString()).apply()
    }

    private fun load() {
        val s = getSharedPreferences("neartalk", MODE_PRIVATE).getString("msgs", null) ?: return
        val arr = JSONArray(s)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            msgs.add(Msg(o.getLong("id"), o.getString("t"), o.getBoolean("m"), o.getString("s")))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::conn.isInitialized) conn.stopAll()
        stopCall()
    }
}
