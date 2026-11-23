package com.vortex.net

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.vortex.BuildConfig
import com.vortex.audio.AudioEngine
import com.vortex.audio.BINS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

/** Implemented by the open project so the network layer can read and update its data. */
interface ProjectSync {
    /** Whole project state (operator side), sent to a controller that asks for a sync. */
    fun snapshot(): JSONObject
    /** A message from the other phone. May be called on any thread. */
    fun onRemote(m: JSONObject)
    /** The controller just connected: send queued requests, then ask for a fresh snapshot. */
    fun onConnected()
}

/**
 * Local Wi-Fi link between the two phones. No internet is used.
 * Operator (VORTEX) runs a WebSocket server and sends the live spectrum.
 * Controller (VORTEX E) connects with the operator's address and a 6 digit pairing code.
 * Text messages are JSON. A spectrum frame is a binary message: [1, level, clip, BINS bytes].
 */
object Link {
    const val PORT = 8765
    const val BEACON_PORT = 8766
    val isOperator: Boolean get() = BuildConfig.ROLE != "CONTROLLER"

    data class Found(val name: String, val ip: String, val seen: Long)

    val connected = MutableStateFlow(false)       // controller: connected to operator
    val clients = MutableStateFlow(0)             // operator: number of paired controllers
    val status = MutableStateFlow("Idle")
    val localIp = MutableStateFlow<String?>(null)
    val pairCode = MutableStateFlow("")
    val found = MutableStateFlow<List<Found>>(emptyList())
    val savedHost = MutableStateFlow("")
    val savedCode = MutableStateFlow("")
    val remoteSpectrum = MutableStateFlow<FloatArray?>(null)
    val remoteLevel = MutableStateFlow(-100f)
    val remoteClip = MutableStateFlow(false)

    @Volatile var handler: ProjectSync? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var server: OperatorServer? = null
    private var client: ControllerClient? = null
    private var ctrlJob: Job? = null
    private var beaconJob: Job? = null
    private var listenJob: Job? = null
    private var spectrumJob: Job? = null
    private var lock: WifiManager.MulticastLock? = null
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("vortex_link", Context.MODE_PRIVATE)
        var code = prefs.getString("code", "") ?: ""
        if (code.length != 6) { code = newCodeString(); prefs.edit().putString("code", code).apply() }
        pairCode.value = code
        savedHost.value = prefs.getString("host", "") ?: ""
        savedCode.value = prefs.getString("ctrl_code", "") ?: ""
        bindWifi()
    }

    private fun newCodeString() = Random.nextInt(100000, 999999).toString()

    fun newCode() {
        val c = newCodeString()
        prefs.edit().putString("code", c).apply()
        pairCode.value = c
    }

    /** Keep traffic on Wi-Fi even if the router has no internet and the phone prefers mobile data. */
    private fun bindWifi() {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val net = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            if (net != null) cm.bindProcessToNetwork(net)
        } catch (ignored: Exception) {}
    }

    fun findLocalIp(): String? {
        return try {
            val list = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
            val sorted = list.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            for (ni in sorted) for (a in ni.inetAddresses) {
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) return a.hostAddress
            }
            null
        } catch (ignored: Exception) { null }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }.forEach { ni ->
                ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { out.add(it) } }
            }
        } catch (ignored: Exception) {}
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (ignored: Exception) {}
        return out.distinct()
    }

    private fun acquireMulticast() {
        try {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("vortex").also { it.setReferenceCounted(false); it.acquire() }
        } catch (ignored: Exception) {}
    }

    // ------------------------------------------------------------------ operator

    fun startOperator() {
        if (server != null) return
        bindWifi()
        acquireMulticast()
        localIp.value = findLocalIp()
        try {
            val s = OperatorServer(PORT)
            s.isReuseAddr = true
            s.start()
            server = s
            status.value = "Waiting for VORTEX E"
        } catch (e: Exception) {
            status.value = "Could not start the server: ${e.message}"
        }
        // Beacon: lets the controller find this phone without typing the address.
        beaconJob = scope.launch {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().also { it.broadcast = true }
                var tick = 0
                while (isActive) {
                    if (tick % 5 == 0) { bindWifi(); localIp.value = findLocalIp() }
                    tick++
                    val name = (Build.MODEL ?: "VORTEX").replace("|", " ")
                    val data = "VORTEX1|$name|$PORT".toByteArray()
                    for (addr in broadcastAddresses()) {
                        try { sock.send(DatagramPacket(data, data.size, addr, BEACON_PORT)) } catch (ignored: Exception) {}
                    }
                    delay(1000)
                }
            } catch (ignored: Exception) {
            } finally { sock?.close() }
        }
        // Live spectrum to every paired controller.
        spectrumJob = scope.launch {
            AudioEngine.spectrum.filterNotNull().collect { f ->
                val s = server
                if (s != null && clients.value > 0) s.broadcastBinary(encodeFrame(f, AudioEngine.levelDb.value, AudioEngine.clipping.value))
            }
        }
    }

    fun stopOperator() {
        beaconJob?.cancel(); spectrumJob?.cancel()
        try { server?.stop(500) } catch (ignored: Exception) {}
        server = null
        clients.value = 0
    }

    private fun encodeFrame(f: FloatArray, level: Float, clip: Boolean): ByteArray {
        val b = ByteArray(3 + BINS)
        b[0] = 1
        b[1] = ((level + 100f) * 2.55f).roundToInt().coerceIn(0, 255).toByte()
        b[2] = if (clip) 1 else 0
        for (i in 0 until BINS) b[3 + i] = ((f[i] + 100f) * 2.55f).roundToInt().coerceIn(0, 255).toByte()
        return b
    }

    fun broadcast(m: JSONObject) { server?.broadcastText(m.toString()) }

    fun pushSnapshot() {
        val h = handler ?: return
        server?.broadcastText(h.snapshot().toString())
    }

    internal fun sendSnapshot(conn: WebSocket) {
        val h = handler
        if (h == null) conn.send("""{"t":"noproject"}""") else conn.send(h.snapshot().toString())
    }

    internal fun clientsChanged(n: Int) {
        clients.value = n
        status.value = if (n > 0) "Connected: $n controller" else "Waiting for VORTEX E"
    }

    // ------------------------------------------------------------------ controller

    fun startController() {
        if (listenJob != null) return
        bindWifi()
        acquireMulticast()
        listenJob = scope.launch {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null as SocketAddress?).also { it.reuseAddress = true; it.bind(InetSocketAddress(BEACON_PORT)); it.soTimeout = 1000 }
                val buf = ByteArray(256)
                while (isActive) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val parts = String(p.data, 0, p.length).split("|")
                        if (parts.size >= 3 && parts[0] == "VORTEX1") {
                            val ip = p.address.hostAddress ?: continue
                            val now = System.currentTimeMillis()
                            val list = found.value.filter { it.ip != ip && now - it.seen < 5000 } + Found(parts[1], ip, now)
                            found.value = list
                        }
                    } catch (ignored: java.net.SocketTimeoutException) {
                        val now = System.currentTimeMillis()
                        found.value = found.value.filter { now - it.seen < 5000 }
                    }
                }
            } catch (ignored: Exception) {
            } finally { sock?.close() }
        }
        // Reconnect automatically to the last operator.
        if (savedHost.value.isNotEmpty() && savedCode.value.length == 6) connectController(savedHost.value, savedCode.value)
    }

    fun connectController(host: String, code: String) {
        prefs.edit().putString("host", host).putString("ctrl_code", code).apply()
        savedHost.value = host; savedCode.value = code
        disconnectController(keepSaved = true)
        ctrlJob = scope.launch {
            while (isActive) {
                bindWifi()
                val c = ControllerClient(host, code)
                client = c
                status.value = "Connecting to $host"
                val ok = try { c.connectBlocking(4, TimeUnit.SECONDS) } catch (ignored: Exception) { false }
                if (ok) { while (isActive && c.isOpen && !c.denied) delay(400) }
                connected.value = false
                try { c.close() } catch (ignored: Exception) {}
                if (c.denied) { status.value = "Wrong pairing code"; break }
                status.value = "Reconnecting…"
                delay(1500)
            }
        }
    }

    fun disconnectController(keepSaved: Boolean = false) {
        ctrlJob?.cancel(); ctrlJob = null
        try { client?.close() } catch (ignored: Exception) {}
        client = null
        connected.value = false
        remoteSpectrum.value = null
        if (!keepSaved) status.value = "Disconnected"
    }

    /** Controller to operator. Returns false when not connected (the caller then queues the message). */
    fun sendToServer(m: JSONObject): Boolean {
        val c = client ?: return false
        if (!c.isOpen || !connected.value) return false
        return try { c.send(m.toString()); true } catch (ignored: Exception) { false }
    }

    internal fun onClientText(c: ControllerClient, text: String) {
        val m = try { JSONObject(text) } catch (ignored: Exception) { return }
        when (m.optString("t")) {
            "welcome" -> { connected.value = true; status.value = "Connected"; handler?.onConnected() }
            "denied" -> { c.denied = true; connected.value = false; status.value = "Wrong pairing code" }
            "noproject" -> status.value = "Connected. The operator has no project open yet."
            else -> handler?.onRemote(m)
        }
    }

    internal fun onClientBinary(b: ByteBuffer) {
        if (b.remaining() < 3 + BINS || b.get(0).toInt() != 1) return
        val arr = ByteArray(b.remaining()); b.get(arr)
        remoteLevel.value = (arr[1].toInt() and 0xFF) / 2.55f - 100f
        remoteClip.value = arr[2].toInt() != 0
        val f = FloatArray(BINS) { i -> (arr[3 + i].toInt() and 0xFF) / 2.55f - 100f }
        remoteSpectrum.value = f
    }

    /** Called by a project when it opens. */
    fun attach(h: ProjectSync) {
        handler = h
        if (isOperator) pushSnapshot() else if (connected.value) h.onConnected()
    }

    fun detach(h: ProjectSync) { if (handler === h) handler = null }
}

internal class OperatorServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    private val authed: MutableSet<WebSocket> = Collections.synchronizedSet(HashSet())

    init { connectionLostTimeout = 5 }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {}

    override fun onMessage(conn: WebSocket?, message: String?) {
        if (conn == null || message == null) return
        val m = try { JSONObject(message) } catch (ignored: Exception) { return }
        val t = m.optString("t")
        if (t == "hello") {
            if (m.optString("code") == Link.pairCode.value) {
                authed.add(conn)
                Link.clientsChanged(authed.size)
                conn.send("""{"t":"welcome"}""")
            } else {
                conn.send("""{"t":"denied"}""")
                conn.close()
            }
            return
        }
        if (!authed.contains(conn)) return
        if (t == "sync") { Link.sendSnapshot(conn); return }
        Link.handler?.onRemote(m)
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        if (conn != null) authed.remove(conn)
        Link.clientsChanged(authed.size)
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {}
    override fun onStart() {}

    fun broadcastText(text: String) {
        for (c in authed.toList()) try { c.send(text) } catch (ignored: Exception) {}
    }

    fun broadcastBinary(bytes: ByteArray) {
        for (c in authed.toList()) try { c.send(bytes) } catch (ignored: Exception) {}
    }
}

internal class ControllerClient(host: String, private val code: String) : WebSocketClient(URI("ws://$host:${Link.PORT}")) {
    @Volatile var denied = false

    init { connectionLostTimeout = 5 }

    override fun onOpen(handshakedata: ServerHandshake?) {
        send(JSONObject().put("t", "hello").put("code", code).put("role", "controller").toString())
    }

    override fun onMessage(message: String?) { if (message != null) Link.onClientText(this, message) }
    override fun onMessage(bytes: ByteBuffer?) { if (bytes != null) Link.onClientBinary(bytes) }
    override fun onClose(code: Int, reason: String?, remote: Boolean) {}
    override fun onError(ex: Exception?) {}
}
