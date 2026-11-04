package com.metrolist.music.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class SsdpDiscovery(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val discoveredMap = ConcurrentHashMap<String, DlnaDevice>()
    private var discoveryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val SSDP_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("MetrolistSSDP")?.apply {
            setReferenceCounted(true)
            try {
                acquire()
            } catch (e: Exception) {
                Timber.w(e, "Could not acquire multicast lock")
            }
        }

        discoveryJob = scope.launch(Dispatchers.IO) {
            Timber.d("Starting SSDP discovery for DLNA TVs")
            while (isActive) {
                try {
                    searchDevices()
                } catch (e: Exception) {
                    Timber.e(e, "Error during SSDP discovery round")
                }
                delay(12_000)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Timber.w(e, "Error releasing multicast lock")
        }
        multicastLock = null
    }

    private suspend fun searchDevices() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                soTimeout = 3500
            }

            val group = InetAddress.getByName(SSDP_IP)

            // Broadcast M-SEARCH for each target
            for (st in SEARCH_TARGETS) {
                val query = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_IP:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 3\r\n")
                    append("ST: $st\r\n")
                    append("\r\n")
                }
                val data = query.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket.send(packet)
            }

            val buffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 4000) {
                try {
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    parseSsdpResponse(response)
                } catch (e: java.net.SocketTimeoutException) {
                    break
                } catch (e: Exception) {
                    Timber.d("Socket receive error: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed in SSDP socket search")
        } finally {
            socket?.close()
        }
    }

    private fun parseSsdpResponse(response: String) {
        val lines = response.lines()
        var location: String? = null
        for (line in lines) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].trim().equals("LOCATION", ignoreCase = true)) {
                location = parts[1].trim()
                break
            }
        }

        if (location != null && !discoveredMap.containsKey(location)) {
            scope.launch(Dispatchers.IO) {
                fetchDeviceDescription(location)
            }
        }
    }

    private fun fetchDeviceDescription(locationUrl: String) {
        try {
            val request = Request.Builder().url(locationUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return

            val xml = response.body?.string() ?: return
            val device = parseDeviceXml(xml, locationUrl)
            if (device != null) {
                discoveredMap[locationUrl] = device
                _devices.value = discoveredMap.values.toList()
                Timber.d("Discovered DLNA TV: ${device.name} (${device.modelName}) at ${device.avTransportUrl}")
            }
        } catch (e: Exception) {
            Timber.d("Failed to parse device description from $locationUrl: ${e.message}")
        }
    }

    private fun parseDeviceXml(xml: String, locationUrl: String): DlnaDevice? {
        return try {
            val baseUri = URI(locationUrl)
            val docBuilder = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder()

            val doc = docBuilder.parse(ByteArrayInputStream(xml.toByteArray()))
            val root = doc.documentElement

            // Resolve custom URLBase if specified in root
            val urlBaseList = root.getElementsByTagName("URLBase")
            val effectiveBaseUri = if (urlBaseList.length > 0) {
                val urlBaseStr = urlBaseList.item(0).textContent.trim()
                if (urlBaseStr.isNotEmpty()) URI(urlBaseStr) else baseUri
            } else {
                baseUri
            }

            var friendlyName = ""
            var manufacturer: String? = null
            var modelName: String? = null
            var iconUrl: String? = null
            var avTransportUrl: String? = null
            var renderingControlUrl: String? = null
            var udn: String? = null

            val friendlyNodes = root.getElementsByTagName("friendlyName")
            if (friendlyNodes.length > 0) {
                friendlyName = friendlyNodes.item(0).textContent.trim()
            }

            val manufacturerNodes = root.getElementsByTagName("manufacturer")
            if (manufacturerNodes.length > 0) {
                manufacturer = manufacturerNodes.item(0).textContent.trim()
            }

            val modelNodes = root.getElementsByTagName("modelName")
            if (modelNodes.length > 0) {
                modelName = modelNodes.item(0).textContent.trim()
            }

            val udnNodes = root.getElementsByTagName("UDN")
            if (udnNodes.length > 0) {
                udn = udnNodes.item(0).textContent.trim()
            }

            // Find icons
            val iconNodes = root.getElementsByTagName("icon")
            for (i in 0 until iconNodes.length) {
                val iconEl = iconNodes.item(i) as? Element ?: continue
                val urlNodes = iconEl.getElementsByTagName("url")
                if (urlNodes.length > 0) {
                    val rawUrl = urlNodes.item(0).textContent.trim()
                    iconUrl = effectiveBaseUri.resolve(rawUrl).toString()
                    break
                }
            }

            // Find AVTransport and RenderingControl services
            val serviceNodes = root.getElementsByTagName("service")
            for (i in 0 until serviceNodes.length) {
                val serviceEl = serviceNodes.item(i) as? Element ?: continue
                val typeNodes = serviceEl.getElementsByTagName("serviceType")
                val controlNodes = serviceEl.getElementsByTagName("controlURL")

                if (typeNodes.length > 0 && controlNodes.length > 0) {
                    val serviceType = typeNodes.item(0).textContent.trim()
                    val rawControlUrl = controlNodes.item(0).textContent.trim()
                    val fullControlUrl = effectiveBaseUri.resolve(rawControlUrl).toString()

                    if (serviceType.contains("AVTransport", ignoreCase = true)) {
                        avTransportUrl = fullControlUrl
                    } else if (serviceType.contains("RenderingControl", ignoreCase = true)) {
                        renderingControlUrl = fullControlUrl
                    }
                }
            }

            // An AVTransport endpoint is required for media playback
            if (avTransportUrl != null) {
                val id = udn ?: locationUrl
                val cleanName = friendlyName.ifEmpty { modelName ?: "DLNA TV" }
                DlnaDevice(
                    id = id,
                    name = cleanName,
                    manufacturer = manufacturer,
                    modelName = modelName,
                    locationUrl = locationUrl,
                    avTransportUrl = avTransportUrl,
                    renderingControlUrl = renderingControlUrl,
                    iconUrl = iconUrl
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing DLNA device XML")
            null
        }
    }
}
