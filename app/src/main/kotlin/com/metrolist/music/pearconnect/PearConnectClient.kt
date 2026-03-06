/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.pearconnect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.*
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val SUPABASE_URL = "https://yalwnibhsrmhomjifwdb.supabase.co"
private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlhbHduaWJoc3JtaG9tamlmd2RiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI2MzI3MzEsImV4cCI6MjA4ODIwODczMX0.i_IQn3H5vbAmA4JrWDBaBFrKHWWh-_5PCZOrkKP3djA"

enum class PearConnectState {
    DISCONNECTED,
    DISCOVERING,
    DEVICE_FOUND,
    CONNECTING,
    PAIRING,
    CONNECTED,
    ERROR
}

data class DiscoveredDevice(
    val serviceName: String,
    val ipAddress: String,
    val port: Int,
    val requiresAuth: Boolean
)

@Singleton
class PearConnectClient @Inject constructor(
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private var resolveListener: NsdManager.ResolveListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _state = MutableStateFlow(PearConnectState.DISCONNECTED)
    val state: StateFlow<PearConnectState> = _state.asStateFlow()

    private val _connectionMode = MutableStateFlow(PearConnectMode.AUTO)
    val connectionMode: StateFlow<PearConnectMode> = _connectionMode.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _desktopPlaybackState = MutableStateFlow<PlaybackStatePayload?>(null)
    val desktopPlaybackState: StateFlow<PlaybackStatePayload?> = _desktopPlaybackState.asStateFlow()

    private val _desktopQueue = MutableStateFlow<List<QueueItemPayload>>(emptyList())
    val desktopQueue: StateFlow<List<QueueItemPayload>> = _desktopQueue.asStateFlow()

    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()
    
    // Supabase
    private var supabaseClient: SupabaseClient? = null
    private var supabaseChannel: RealtimeChannel? = null
    private var supabaseJob: kotlinx.coroutines.Job? = null
    
    private val PEAR_CONNECT_MODE_KEY = stringPreferencesKey("pear_connect_mode")
    private val PEAR_CONNECT_TOKEN_KEY = stringPreferencesKey("pear_connect_token")
    private val PEAR_CONNECT_IP_KEY = stringPreferencesKey("pear_connect_ip")
    private val PEAR_CONNECT_PORT_KEY = stringPreferencesKey("pear_connect_port")
    private val PEAR_CONNECT_CODE_KEY = stringPreferencesKey("pear_connect_code")

    init {
        scope.launch {
            val savedMode = context.dataStore.data.first()[PEAR_CONNECT_MODE_KEY]
            _connectionMode.value = PearConnectMode.entries.find { it.name == savedMode } ?: PearConnectMode.AUTO
            
            initSupabase()
        }
    }

    private fun initSupabase() {
        try {
            supabaseClient = createSupabaseClient(SUPABASE_URL, SUPABASE_ANON_KEY) {
                install(Realtime)
            }
        } catch (e: Exception) {
            Timber.e(e, "PearConnect: Failed to init Supabase")
        }
    }
    
    private var currentConnectedDevice: DiscoveredDevice? = null

    fun setConnectionMode(mode: PearConnectMode) {
        _connectionMode.value = mode
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PEAR_CONNECT_MODE_KEY] = mode.name
            }
        }
        
        // Reconnect if needed
        if (_state.value == PearConnectState.CONNECTED || _state.value == PearConnectState.DISCONNECTED || _state.value == PearConnectState.ERROR) {
             reconnectIfNeeded()
        }
    }

    fun startDiscovery() {
        isExplicitDisconnect = false
        if (_state.value != PearConnectState.DISCONNECTED && _state.value != PearConnectState.ERROR) {
            return
        }
        
        _state.value = PearConnectState.DISCOVERING
        _discoveredDevices.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Timber.d("PearConnect: Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Timber.d("PearConnect: Service found ${service.serviceName}")
                if (service.serviceType.contains("pear-connect")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Timber.e("PearConnect: Service lost ${service.serviceName}")
                _discoveredDevices.value = _discoveredDevices.value.filter { it.serviceName != service.serviceName }
                if (_discoveredDevices.value.isEmpty() && _state.value == PearConnectState.DEVICE_FOUND) {
                    _state.value = PearConnectState.DISCOVERING
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Timber.d("PearConnect: Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("PearConnect: Discovery failed $errorCode")
                _state.value = PearConnectState.ERROR
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("PearConnect: Stop discovery failed $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        try {
            nsdManager.discoverServices("_pear-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Timber.e(e, "PearConnect: Failed to start discovery")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("PearConnect: Resolve failed $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Timber.d("PearConnect: Resolve Succeeded. ${serviceInfo.serviceName}")

                if (serviceInfo.serviceName == "Pear Desktop" || serviceInfo.serviceName.contains("Pear")) {
                    val requiresAuth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        serviceInfo.attributes["requiresAuth"]?.let { String(it) } == "true"
                    } else false

                    val hostAddress = serviceInfo.host.hostAddress
                    if (hostAddress != null) {
                        val device = DiscoveredDevice(
                            serviceName = serviceInfo.serviceName,
                            ipAddress = hostAddress,
                            port = serviceInfo.port,
                            requiresAuth = requiresAuth
                        )
                        val newList = _discoveredDevices.value.toMutableList()
                        if (!newList.any { it.ipAddress == device.ipAddress }) {
                            newList.add(device)
                            _discoveredDevices.value = newList
                            if (_state.value == PearConnectState.DISCOVERING) {
                                _state.value = PearConnectState.DEVICE_FOUND
                            }
                        }
                    }
                }
            }
        }
        
        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Timber.e(e, "PearConnect: Resolve failed")
        }
    }

    fun stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Timber.e(e, "PearConnect: Error stopping discovery")
            }
            discoveryListener = null
        }
        if (_state.value == PearConnectState.DISCOVERING || _state.value == PearConnectState.DEVICE_FOUND) {
            _state.value = PearConnectState.DISCONNECTED
        }
    }

    private var isExplicitDisconnect = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var lastIp: String? = null
    private var lastPort: Int? = null
    private var lastPairingCode: String? = null

    private fun scheduleReconnect() {
        if (isExplicitDisconnect) return
        val ip = lastIp ?: return
        val port = lastPort ?: return
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(5000)
            val isConnected = _state.value == PearConnectState.CONNECTED
            if (!isExplicitDisconnect && !isConnected) {
                Timber.d("PearConnect: Attempting auto-reconnect...")
                if (_state.value != PearConnectState.CONNECTED) {
                    _state.value = PearConnectState.DISCONNECTED
                }
                connectWithQr(ip, port, lastPairingCode ?: "")
            }
        }
    }

    fun reconnectIfNeeded() {
        val currentMode = _connectionMode.value
        if (_state.value == PearConnectState.CONNECTED) {
            if (currentMode == PearConnectMode.LOCAL || currentMode == PearConnectMode.AUTO) {
                if (webSocket == null) {
                    _state.value = PearConnectState.DISCONNECTED
                    scheduleReconnect()
                }
            }
            if (currentMode == PearConnectMode.SUPABASE || currentMode == PearConnectMode.AUTO) {
                if (supabaseChannel == null) {
                    _state.value = PearConnectState.DISCONNECTED
                    scheduleReconnect()
                }
            }
        } else if (_state.value == PearConnectState.DISCONNECTED || _state.value == PearConnectState.ERROR) {
            scope.launch {
                val ip = lastIp ?: context.dataStore.data.first()[PEAR_CONNECT_IP_KEY]
                val port = lastPort ?: context.dataStore.data.first()[PEAR_CONNECT_PORT_KEY]?.toIntOrNull()
                val code = lastPairingCode ?: context.dataStore.data.first()[PEAR_CONNECT_CODE_KEY]
                if (ip != null && port != null && code != null) {
                    lastIp = ip; lastPort = port; lastPairingCode = code
                    connectWithQr(ip, port, code)
                }
            }
        }
    }

    fun connectToDevice(device: DiscoveredDevice) {
        _state.value = PearConnectState.CONNECTING
        currentConnectedDevice = device
        isExplicitDisconnect = false
        reconnectJob?.cancel()
        stopDiscovery()
        performConnect(device.ipAddress, device.port, null)
    }

    fun connectWithQr(ip: String, port: Int, pairingCode: String, fallbackIp: String? = null, fallbackPort: Int? = null) {
        if (_state.value == PearConnectState.CONNECTED || _state.value == PearConnectState.CONNECTING) return
        _state.value = PearConnectState.CONNECTING
        isExplicitDisconnect = false
        reconnectJob?.cancel()
        stopDiscovery()
        
        val mode = _connectionMode.value
        if (mode == PearConnectMode.SUPABASE || mode == PearConnectMode.AUTO) {
            initSupabaseChannel(pairingCode)
        }
        
        if (mode == PearConnectMode.LOCAL || mode == PearConnectMode.AUTO) {
            if (fallbackIp != null && fallbackIp != ip) {
                performConnect(fallbackIp, fallbackPort ?: port, pairingCode, isFastAttempt = true) { success ->
                    if (!success) {
                        if (mode == PearConnectMode.LOCAL) {
                            performConnect(ip, port, pairingCode, isFastAttempt = false)
                        }
                    }
                }
            } else {
                performConnect(ip, port, pairingCode, isFastAttempt = false)
            }
        }
    }

    private fun initSupabaseChannel(pairingCode: String) {
        lastPairingCode = pairingCode
        supabaseJob?.cancel()
        supabaseJob = scope.launch {
            try {
                if (supabaseClient == null) initSupabase()
                supabaseChannel?.unsubscribe()
                val channel = supabaseClient?.realtime?.channel("room-$pairingCode")
                supabaseChannel = channel

                val flowJob = channel?.broadcastFlow<kotlinx.serialization.json.JsonObject>("desktop-state")?.onEach {
                    try {
                        val text = it.toString()
                        Timber.d("PearConnect: Received Supabase broadcast: $text")
                        handleMessage(text)
                        if (_state.value != PearConnectState.CONNECTED) {
                            Timber.i("PearConnect: Transitions to CONNECTED via Supabase activity")
                            _state.value = PearConnectState.CONNECTED
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "PearConnect: Error handling Supabase message")
                    }
                }?.launchIn(this) // Use 'this' (the room-init job scope)

                // Wait for the channel to be joined before sending AUTH
                Timber.d("PearConnect: Subscribing to Supabase channel room-$pairingCode...")
                channel?.subscribe(blockUntilSubscribed = true)
                
                Timber.i("PearConnect: Supabase channel joined for room-$pairingCode")

                if (_connectionMode.value == PearConnectMode.SUPABASE || _connectionMode.value == PearConnectMode.AUTO) {
                    // Only send if we are trying to connect
                    if (_state.value == PearConnectState.CONNECTING || _state.value == PearConnectState.PAIRING || _state.value == PearConnectState.DISCONNECTED) {
                        Timber.d("PearConnect: Sending Supabase AUTH_REQUEST")
                        sendMessage("AUTH_REQUEST", JsonObject(mapOf(
                            "pairingCode" to JsonPrimitive(pairingCode),
                            "deviceName" to JsonPrimitive(Build.MODEL ?: "Metrolist Mobile"),
                            "deviceType" to JsonPrimitive("mobile")
                        )))
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Timber.e(e, "PearConnect: Supabase join failed")
                    _state.value = PearConnectState.ERROR
                }
            }
        }
    }

    private fun performConnect(ip: String, port: Int, pairingCode: String? = null, isFastAttempt: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        lastIp = ip; lastPort = port; if (pairingCode != null) lastPairingCode = pairingCode
        isExplicitDisconnect = false
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PEAR_CONNECT_IP_KEY] = ip
                prefs[PEAR_CONNECT_PORT_KEY] = port.toString()
                if (pairingCode != null) prefs[PEAR_CONNECT_CODE_KEY] = pairingCode
            }
        }

        val wsUrl = buildWsUrl(ip, port)
        val currentClient = if (isFastAttempt) client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).build() else client
        val request = Request.Builder().url(wsUrl).header("bypass-tunnel-reminder", "true").build()

        webSocket = currentClient.newWebSocket(request, object : WebSocketListener() {
            var hasNotifiedResult = false
            override fun onOpen(ws: WebSocket, response: Response) {
                if (!hasNotifiedResult) { hasNotifiedResult = true; onResult?.invoke(true) }
                scope.launch {
                    val savedToken = context.dataStore.data.first()[PEAR_CONNECT_TOKEN_KEY]
                    if (savedToken != null) {
                        Timber.d("PearConnect: Sending WS AUTH_REQUEST with token")
                        sendMessage("AUTH_REQUEST", JsonObject(mapOf("token" to JsonPrimitive(savedToken))))
                    } else {
                        Timber.d("PearConnect: Sending WS AUTH_REQUEST with pairingCode")
                        val authPayload = mutableMapOf("deviceName" to JsonPrimitive(Build.MODEL ?: "Metrolist Mobile"), "deviceType" to JsonPrimitive("mobile"))
                        if (pairingCode != null) authPayload["pairingCode"] = JsonPrimitive(pairingCode)
                        sendMessage("AUTH_REQUEST", JsonObject(authPayload))
                        _state.value = PearConnectState.PAIRING
                    }
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                Timber.v("PearConnect: Received WS message: $text")
                handleMessage(text)
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                this@PearConnectClient.webSocket = null
                if (_connectionMode.value == PearConnectMode.LOCAL) {
                    _state.value = PearConnectState.DISCONNECTED
                    _desktopPlaybackState.value = null
                }
                if (!isExplicitDisconnect) scheduleReconnect()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "PearConnect: WS connection failure")
                if (this@PearConnectClient.webSocket === ws) this@PearConnectClient.webSocket = null
                if (!hasNotifiedResult && onResult != null) { hasNotifiedResult = true; onResult.invoke(false) }
                else if (_connectionMode.value == PearConnectMode.LOCAL) {
                    _state.value = PearConnectState.ERROR
                    if (!isExplicitDisconnect) scheduleReconnect()
                }
            }
        })
    }

    private fun buildWsUrl(host: String, port: Int): String {
        val scheme = if (port == 443) "wss" else "ws"
        val portSuffix = if (port == 443 || port == 80) "" else ":$port"
        return "$scheme://$host$portSuffix"
    }
    
    fun sendPairingCodeResponse(code: String) = sendMessage("AUTH_RESPONSE", JsonObject(mapOf("code" to JsonPrimitive(code))))

    private fun handleMessage(text: String) {
        if (isExplicitDisconnect) {
            Timber.d("PearConnect: Ignoring message during explicit disconnect: $text")
            return
        }
        try {
            val msg = json.decodeFromString<PearConnectMessage>(text)
            handleParsedMessage(msg)
        } catch (e: Exception) {
            Timber.e(e, "PearConnect: Parse failed for message: $text")
        }
    }

    private fun handleParsedMessage(msg: PearConnectMessage) {
        try {
            when (msg.type) {
                "AUTH_SUCCESS" -> {
                    Timber.i("PearConnect: AUTH_SUCCESS received")
                    val token = (msg.payload as? JsonObject)?.get("token")?.let { (it as? JsonPrimitive)?.content }
                    if (token != null) {
                        scope.launch { context.dataStore.edit { it[PEAR_CONNECT_TOKEN_KEY] = token } }
                    }
                    _state.value = PearConnectState.CONNECTED
                    _pairingCode.value = lastPairingCode
                }
                "AUTH_FAILED" -> {
                    Timber.e("PearConnect: AUTH_FAILED received")
                    scope.launch { context.dataStore.edit { it.remove(PEAR_CONNECT_TOKEN_KEY); it.remove(PEAR_CONNECT_CODE_KEY) } }
                    lastPairingCode = null; _state.value = PearConnectState.DISCONNECTED; _pairingCode.value = null
                }
                "STATE_UPDATE" -> {
                    msg.payload?.let { 
                        try {
                            _desktopPlaybackState.value = json.decodeFromJsonElement<PlaybackStatePayload>(it) 
                        } catch (e: Exception) {
                            Timber.e(e, "PearConnect: Error parsing STATE_UPDATE")
                        }
                    }
                }
                "QUEUE_UPDATE" -> {
                    msg.payload?.let { 
                        try { 
                            _desktopQueue.value = json.decodeFromJsonElement<List<QueueItemPayload>>(it) 
                        } catch (e: Exception) {
                            Timber.e(e, "PearConnect: Error parsing QUEUE_UPDATE")
                        } 
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "PearConnect: Message process failed")
        }
    }

    fun sendMessage(type: String, payload: kotlinx.serialization.json.JsonElement? = null) {
        val msg = PearConnectMessage(type, payload)
        val text = json.encodeToString(msg)
        val mode = _connectionMode.value
        
        if (mode == PearConnectMode.LOCAL || mode == PearConnectMode.AUTO) {
            val sent = webSocket?.send(text)
            if (sent == true) {
                Timber.v("PearConnect: Sent WS message: $type")
                if (mode == PearConnectMode.LOCAL) return
            }
            if (sent == false && mode == PearConnectMode.LOCAL) {
                Timber.w("PearConnect: Failed to send WS message: $type")
                webSocket = null; _state.value = PearConnectState.ERROR
                if (!isExplicitDisconnect) scheduleReconnect()
                return
            }
        }
        
        if (mode == PearConnectMode.SUPABASE || mode == PearConnectMode.AUTO) {
            scope.launch {
                try {
                    supabaseChannel?.let { channel ->
                        val jsonElement = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                        Timber.d("PearConnect: Broadcasting to Supabase room-$lastPairingCode: $type")
                        channel.broadcast(event = "remote-control", message = jsonElement)
                    } ?: Timber.w("PearConnect: Cannot broadcast, supabaseChannel is null")
                } catch (e: Exception) {
                    Timber.e(e, "PearConnect: Supabase broadcast failed for $type")
                }
            }
        }
    }
    
    fun disconnect() {
        isExplicitDisconnect = true
        Timber.i("PearConnect: Explicit disconnect requested")
        
        try {
            sendMessage("DISCONNECT")
        } catch (e: Exception) {
            Timber.w("PearConnect: Failed to send DISCONNECT message")
        }

        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        
        supabaseJob?.cancel()
        supabaseJob = null
        
        scope.launch { 
            try { 
                supabaseChannel?.unsubscribe() 
            } catch (e: Exception) {
                Timber.w("PearConnect: Error during supabase unsubscribe")
            } finally {
                supabaseChannel = null
            }
        }
        
        _state.value = PearConnectState.DISCONNECTED
        _desktopPlaybackState.value = null
        _desktopQueue.value = emptyList()
        _pairingCode.value = null
        currentConnectedDevice = null
        stopDiscovery()
    }
    
    // Playback Controls
    fun play() = sendMessage("PLAY")
    fun pause() = sendMessage("PAUSE")
    fun next() = sendMessage("NEXT")
    fun previous() = sendMessage("PREVIOUS")
    fun seekTo(position: Long) = sendMessage("SEEK", kotlinx.serialization.json.JsonPrimitive(position))
    fun setVolume(volume: Float) = sendMessage("SET_VOLUME", kotlinx.serialization.json.JsonPrimitive((volume * 100).toInt()))

    /**
     * Tells the desktop to load and play a specific YouTube Music video by ID.
     * Used when the user picks a song on the phone while connected.
     */
    fun playVideoOnDesktop(videoId: String) {
        Timber.i("PearConnect: Requesting desktop playback of $videoId")
        sendMessage(
            "PLAY_VIDEO_ID",
            kotlinx.serialization.json.JsonPrimitive(videoId)
        )
    }

    fun adjustVolume(delta: Int) {
        val current = _desktopPlaybackState.value?.volume ?: 100.0
        val newVol = (current + delta).coerceIn(0.0, 100.0)
        sendMessage("SET_VOLUME", kotlinx.serialization.json.JsonPrimitive(newVol.toInt()))
    }

    fun toggleImmersive() = sendMessage("TOGGLE_IMMERSIVE")
}
