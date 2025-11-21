# Project Beacon: The Connectionless Mesh Protocol

## 1. Executive Summary & Historical Context

### The "Why"

We are pivoting to this architecture after exhaustive testing of a **Connected Mesh** (
GATT/Socket-based) approach in the `SimpleMesh` project.

**The Failure Mode:**
Android's Bluetooth stack, specifically when abstracted through Google Nearby Connections
`P2P_CLUSTER`, cannot reliably maintain more than 2-3 concurrent, high-traffic bidirectional
connections.

* **Symptom:** `IOException: Broken Pipe` and `STATUS_BLUETOOTH_ERROR`.
* **Root Cause:** The single radio hardware cannot "time-slice" efficiently enough to be a Master to
  some nodes, a Slave to others, Advertise itself, and Scan for neighbors simultaneously. This leads
  to a "Broadcast Storm" of radio interrupt requests that crashes the lower-level stack.

**The Solution:**
**Abandon Connections.**
We will move to a **Connectionless (Beaconing)** architecture. We will never establish a socket. We
will never "pair." We will communicate solely by changing our public "Nametag" (Advertising Data)
and reading the "Nametags" of others (Discovery).

---

## 2. The "Connectionless" Paradigm

### How It Works

Imagine a room full of people where **no one is allowed to talk**.
To communicate, everyone wears a digital nametag.

* **Sending:** If Alice wants to say "Hello", she updates her nametag to display `MSG:Hello`.
* **Receiving:** Bob looks around the room (Scans). He sees Alice's nametag says `MSG:Hello`. Bob
  writes this down.
* **Relaying (Mesh):** If Bob wants to help Alice reach Charlie, Bob changes *his* nametag to
  `FWD:Alice_says_Hello`.

### The Trade-Offs (Read Carefully)

| Feature         | Connected Mesh (Old)                | Beacon Mesh (New)                          |
|:----------------|:------------------------------------|:-------------------------------------------|
| **Stability**   | **Fragile** (Crashes with >3 peers) | **Rock Solid** (No sockets to break)       |
| **Capacity**    | High (Streaming, Files)             | **Tiny** (Short Text / Status Codes only)  |
| **Latency**     | Low (< 100ms)                       | **High** (2s - 10s per hop)                |
| **Scalability** | Low (Limit 4 peers)                 | **Infinite** (1 Broadcast = ∞ Listeners)   |
| **Privacy**     | Encryption Negotiated               | **Public** (Must encrypt payload manually) |

---

## 3. Technical Specifications & Requirements

### 3.1 The Payload Constraint

We are hijacking the `EndpointName` field of the Nearby Connections API.

* **Theoretical Limit:** ~131 bytes (Bluetooth 5.x Extended Advertising).
* **Safe Limit:** **~80 Bytes**.
    * We must leave room for Nearby Connections' own internal headers (Service ID hashes, etc.).
    * Exceeding this limit causes the advertisement to be silently truncated or dropped.

### 3.2 The Protocol (The "Tweet" Format)

Since we only have ~80 bytes, we cannot send JSON. We need a dense binary or compact string format.
**Proposed Structure (Base64 Encoded String):**

`[IV][NetID][Seq][TTL][Data]`

1. **IV (Target Hash) [4 chars]:** A short hash of the intended recipient (or "ALL" for broadcast).
2. **NetID [2 chars]:** To ignore beacons from other apps/meshes.
3. **Seq (Sequence) [3 chars]:** A rolling counter (0-999). If Bob sees Seq #5 from Alice, and then
   sees Seq #5 again, he ignores it. If he sees Seq #6, it's new.
4. **TTL (Time To Live) [1 char]:** Starts at '3'. Bob decrements to '2' before rebroadcasting.
   Prevents infinite loops.
5. **Data [~70 chars]:** The actual message.
    * *Example:* "Fire at Loc A"
    * *Example:* "Ben: OK"

### 3.3 The Duty Cycle (The Heartbeat)

The radio cannot broadcast a new message instantly.

1. **User types message.**
2. **Stop Advertising** (Takes ~500ms).
3. **Update Endpoint Name** (Payload).
4. **Start Advertising** (Takes ~500ms).
5. **Wait:** The message must "sit" on the airwaves for at least 3-5 seconds to ensure scanning
   nodes rotate their frequencies and catch it.

---

## 4. Implementation Directives for Jules (The Builder)

**Target:** Create a new Android App from scratch. Do not reuse `SimpleMesh` code directly, but you
may reference its permission handling logic.

### Phase 1: Project Setup

* **Name:** `BeaconMesh`
* **Min SDK:** 26 (Android 8.0) - Required for better BLE Advertisement support.
* **Permissions:**
    * `BLUETOOTH_SCAN`
    * `BLUETOOTH_ADVERTISE`
    * `BLUETOOTH_CONNECT` (Still needed for API access, even if we don't "connect")
    * `ACCESS_FINE_LOCATION` (Required for discovery on older Androids)
    * `NEARBY_WIFI_DEVICES` (If targeting Tiramisu+)

### Phase 2: The "Blinker" (Transmitter)

* Create a `BeaconManager` class.
* **Function:** `updateMessage(text: String)`
* **Logic:**
    * Use `Nearby.getConnectionsClient(context).startAdvertising(...)`.
    * **Strategy:** Use `Strategy.P2P_POINT_TO_POINT`.
        * *Why?* It is the lightest weight strategy. We don't need the topology management of
          `CLUSTER`. We just need the radio to scream our name.
    * **Name:** The `text` payload.
    * **ServiceId:** "com.example.beaconmesh" (Must be unique).

### Phase 3: The "Listener" (Receiver)

* **Function:** `startListening()`
* **Logic:**
    * Use `Nearby.getConnectionsClient(context).startDiscovery(...)`.
    * **Strategy:** Must match advertiser (`Strategy.P2P_POINT_TO_POINT`).
    * **Callback:** `onEndpointFound(endpointId, info)`.
* **CRITICAL:** Inside `onEndpointFound`:
    1. **Read** `info.endpointName`.
    2. **Log/Display** it.
    3. **Do NOT** call `requestConnection`.
    4. **Do NOT** stop discovery (unless necessary to save battery).

    * *Note:* Nearby Connections might cache "Found" endpoints. You may need to cycle
      `stopDiscovery` / `startDiscovery` every 30 seconds to refresh the "Nametags" if the API
      doesn't fire updates for name changes automatically (It usually fires `onEndpointLost` then
      `onEndpointFound` if the advertisement changes significantly, or we might need to rely on the
      `onEndpointFound` firing repeatedly).

### Phase 4: The "Gossip" (Relay Logic)

* Implement a **Message Cache** (`Set<String> seenMessages`).
* **Logic:**
    1. Hear "Hello" (Seq #10) from Alice.
    2. Check Cache: Have I seen "Alice:10"?
    3. **No:**
        * Add "Alice:10" to Cache.
        * Display "Hello".
        * **Rebroadcast:**
            * Check TTL. If > 0, decrement TTL.
            * `updateMessage("FWD:Alice:10:Hello")`.
    4. **Yes:** Ignore.

### Phase 5: The UI

* **Main Screen:**
    * **Top:** "My Status" (Input field).
    * **Middle:** "Live Peers" (List of currently visible advertisements).
    * **Bottom:** "Message Log" (History of heard gossip).

---

## 5. Success Criteria

1. **Stability:** app runs for >1 hour on 4 devices without crashing the Bluetooth stack.
2. **Propagations:** A message sent from Device A (out of range of C) reaches Device C via Device B.
3. **Recovery:** If Device B walks away and comes back, it resumes bridging immediately without a "
   reconnection" handshake.

## Code Snippets

Consider using the following:

```kt
object DeviceIdentifier {
    private const val PREFS_NAME = "SimpleMeshPrefs"
    private const val PREF_UNIQUE_ID = "UUID"
    private var uniqueID: String? = null

    private const val BASE58_ALPHABET = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"

    fun randomString(length: Int): String = buildString(length) {
        repeat(length) {
            val randomIndex = Random.nextInt(BASE58_ALPHABET.length)
            append(BASE58_ALPHABET[randomIndex])
        }
    }

    fun get(context: Context): String {
        if (uniqueID == null) {
            val sharedPrefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            uniqueID = sharedPrefs.getString(PREF_UNIQUE_ID, null)
            if (uniqueID == null) {
                uniqueID = randomString(4)
                sharedPrefs.edit {
                    putString(PREF_UNIQUE_ID, uniqueID)
                }
            }
        }
        return uniqueID!!
    }
}
```

```txt
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.play.services.nearby)
    implementation(libs.kotlinx.serialization.cbor)
```

```xml

<uses-permission android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" /><uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
android:maxSdkVersion="30" /><uses-permission
android:name="android.permission.ACCESS_WIFI_STATE" /><uses-permission
android:name="android.permission.CHANGE_WIFI_STATE" /><uses-permission
android:name="android.permission.ACCESS_COARSE_LOCATION" /><uses-permission
android:name="android.permission.ACCESS_FINE_LOCATION" /><uses-permission
android:name="android.permission.HIGH_RESOLUTION_SENSORS" /><uses-permission
android:name="android.permission.BLUETOOTH_SCAN" /><uses-permission
android:name="android.permission.BLUETOOTH_CONNECT" /><uses-permission
android:name="android.permission.NFC" />

    <!-- Additions -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" /><uses-permission
android:name="android.permission.BLUETOOTH_ADVERTISE" />
```