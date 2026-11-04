package com.ogenrwotdaniel.wakeproof.clip

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** A device that can take a clip over LAN. */
data class ClipPeer(
    val installId: String,
    val name: String,
    val addrs: Set<String>,
    val port: Int = ClipLan.CLIP_PORT
)

/**
 * LAN transport of `docs/CLIPBOARD.md`: plain HTTP/1.1 on [CLIP_PORT] (the payload
 * is already sealed), addresses from mDNS ([ClipDiscovery]), presence docs and the
 * pairing itself.
 */
object ClipLan {
    const val CLIP_PORT = 47321
    const val SERVICE_TYPE = "_wakeproofclip._tcp"
    const val PING_BODY = "wakeproof-clip"
    /** Largest body the server reads: a 20 MiB image plus header, length prefix, nonce and tag. */
    const val MAX_BODY_BYTES = ClipCodec.MAX_IMAGE_BYTES + 64 * 1024

    private const val TAG = "ClipLan"

    /** 1.5 s connect, as the spec says; a generous read timeout for a 20 MiB image. */
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Posts [sealed] to every peer, every address in parallel. Returns the
     * installIds that answered 204.
     */
    suspend fun sendToPeers(peers: Collection<ClipPeer>, id: String, sealed: ByteArray): Set<String> =
        coroutineScope {
            peers.map { peer ->
                async(Dispatchers.IO) {
                    val acked = peer.addrs.map { addr -> async { post(addr, peer.port, id, sealed) } }
                        .awaitAll().any { it }
                    peer.installId.takeIf { acked }
                }
            }.awaitAll().filterNotNull().toSet()
        }

    private suspend fun post(addr: String, port: Int, id: String, sealed: ByteArray): Boolean =
        runInterruptible(Dispatchers.IO) {
            runCatching {
                // Raw socket, not OkHttp: see LanHttp for why cleartext needs it.
                LanHttp.post(
                    addr, port, "/clip", sealed, mapOf("X-Clip-Id" to id),
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS
                ).code == 204
            }.onFailure { Log.d(TAG, "LAN $addr:$port: ${it.message}") }.getOrDefault(false)
        }

    /** This device's IPv4 LAN addresses, for its presence doc. */
    fun ownAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    /** Usable host strings from an mDNS resolution: IPv4 and non-link-local IPv6. */
    fun usableHosts(addrs: List<InetAddress>): Set<String> = addrs.filter {
        it is Inet4Address || (it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress)
    }.mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
}

/**
 * The tiny HTTP/1.1 server: `POST /clip` and `GET /ping`, one connection per
 * request (`Connection: close`). Runs on IO threads of the scope it is started in.
 */
class ClipLanServer(private val handler: suspend (id: String, body: ByteArray) -> Int) {

    @Volatile private var server: ServerSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(ClipLan.CLIP_PORT))
                }
            }.onFailure { Log.w(TAG, "cannot listen on ${ClipLan.CLIP_PORT}", it) }.getOrNull() ?: return@launch
            server = socket
            try {
                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    launch { serve(client) }
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        job?.cancel()
        job = null
    }

    private suspend fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val request = runCatching { readHead(input) }.getOrNull()
            if (request == null) {
                respond(out, 400, "Bad Request")
                return
            }
            when {
                request.method == "GET" && request.path == "/ping" ->
                    respond(out, 200, "OK", ClipLan.PING_BODY.toByteArray())
                request.method == "POST" && request.path == "/clip" -> {
                    val id = request.headers["x-clip-id"]
                    val length = request.headers["content-length"]?.toLongOrNull()
                    when {
                        id.isNullOrBlank() || length == null || length < 0 -> respond(out, 400, "Bad Request")
                        length > ClipLan.MAX_BODY_BYTES -> respond(out, 413, "Payload Too Large")
                        else -> {
                            val body = readBody(input, length.toInt())
                            if (body == null) {
                                respond(out, 400, "Bad Request")
                            } else {
                                val code = runCatching { handler(id, body) }.getOrDefault(400)
                                respond(out, code, if (code == 204) "No Content" else "Bad Request")
                            }
                        }
                    }
                }
                else -> respond(out, 404, "Not Found")
            }
        }
    }

    private class Head(val method: String, val path: String, val headers: Map<String, String>)

    private fun readHead(input: InputStream): Head? {
        val lines = mutableListOf<String>()
        val line = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > MAX_HEAD_BYTES) return null
            if (b == '\n'.code) {
                val text = line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.reset()
                if (text.isEmpty()) break
                lines += text
            } else {
                line.write(b)
            }
        }
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { h ->
            val colon = h.indexOf(':')
            if (colon <= 0) null else h.substring(0, colon).trim().lowercase() to h.substring(colon + 1).trim()
        }.toMap()
        return Head(first[0].uppercase(), first[1].substringBefore('?'), headers)
    }

    private fun readBody(input: InputStream, length: Int): ByteArray? {
        val body = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = input.read(body, at, length - at)
            if (n < 0) return null
            at += n
        }
        return body
    }

    private fun respond(out: OutputStream, code: Int, reason: String, body: ByteArray = ByteArray(0)) {
        runCatching {
            val head = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                if (body.isNotEmpty()) append("Content-Type: text/plain\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }

    private companion object {
        const val TAG = "ClipLanServer"
        const val MAX_HEAD_BYTES = 16 * 1024
    }
}

/**
 * mDNS/DNS-SD for `_wakeproofclip._tcp`: registers this device (TXT `id`, `name`)
 * and resolves the others into [peers]. Campus Wi-Fi often blocks multicast, which
 * is why presence docs carry addresses too.
 */
class ClipDiscovery(private val context: Context) {

    private val nsd by lazy { context.getSystemService(NsdManager::class.java) }
    private val executor = Executors.newSingleThreadExecutor()
    private val callbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private val found = ConcurrentHashMap<String, ClipPeer>()
    private val _peers = MutableStateFlow<Map<String, ClipPeer>>(emptyMap())

    /** Resolved peers by installId (never this device). */
    val peers: StateFlow<Map<String, ClipPeer>> get() = _peers

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var ownId: String = ""

    fun start(installId: String, name: String) {
        stop()
        ownId = installId
        val info = NsdServiceInfo().apply {
            serviceName = name.take(60)
            serviceType = ClipLan.SERVICE_TYPE
            port = ClipLan.CLIP_PORT
            setAttribute("id", installId)
            setAttribute("name", name)
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = follow(serviceInfo)
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = unfollow(serviceInfo.serviceName)
        }
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
            registration = reg
        }.onFailure { Log.w(TAG, "mDNS register", it) }
        runCatching {
            nsd.discoverServices(ClipLan.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
            discovery = disc
        }.onFailure { Log.w(TAG, "mDNS discover", it) }
    }

    fun stop() {
        callbacks.values.forEach { cb -> runCatching { nsd.unregisterServiceInfoCallback(cb) } }
        callbacks.clear()
        discovery?.let { d -> runCatching { nsd.stopServiceDiscovery(d) } }
        registration?.let { r -> runCatching { nsd.unregisterService(r) } }
        discovery = null
        registration = null
        found.clear()
        _peers.value = emptyMap()
    }

    private fun follow(info: NsdServiceInfo) {
        val key = info.serviceName
        if (callbacks.containsKey(key)) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                callbacks.remove(key)
            }
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val id = serviceInfo.attributes["id"]?.toString(Charsets.UTF_8) ?: return
                if (id == ownId) return
                val name = serviceInfo.attributes["name"]?.toString(Charsets.UTF_8) ?: serviceInfo.serviceName
                val hosts = ClipLan.usableHosts(serviceInfo.hostAddresses)
                if (hosts.isEmpty()) return
                found[key] = ClipPeer(id, name, hosts, serviceInfo.port)
                publish()
            }
            override fun onServiceLost() = unfollow(key)
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        callbacks[key] = cb
        runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }
            .onFailure { callbacks.remove(key) }
    }

    private fun unfollow(key: String) {
        found.remove(key)
        callbacks.remove(key)?.let { cb -> runCatching { nsd.unregisterServiceInfoCallback(cb) } }
        publish()
    }

    private fun publish() {
        _peers.value = found.values.associateBy { it.installId }
    }

    private companion object {
        const val TAG = "ClipDiscovery"
    }
}
