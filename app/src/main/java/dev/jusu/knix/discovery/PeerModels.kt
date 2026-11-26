package dev.jusu.knix.discovery

enum class DiscoveryTransport { WIFI_DIRECT, LOCAL_NETWORK, BLUETOOTH_LE }

data class DiscoveredPeer(
    val id: String,
    val name: String,
    val address: String? = null,
    val port: Int? = null,
    val transport: DiscoveryTransport,
    val lastSeenMillis: Long = System.currentTimeMillis()
)
