# Development Tasks & Architectural Improvements

## 1. Fix Rebroadcast "Race Condition" (Priority: High)

### Context & Analysis

The current implementation in `BeaconManager.kt` has a critical flaw in how it handles the duty
cycle.

* **The Bug:** `advertisePacket()` immediately cancels any running `advertisingJob`.
* **The Scenario:** If the device receives multiple packets in quick succession (e.g., Packet A,
  then 200ms later Packet B), it will start advertising A, immediately cancel it, and switch to B.
* **The Consequence:** Packet A gets < 200ms of airtime, which is insufficient for other nodes to
  discover it. In a busy mesh, only the *last* received packet gets propagated, breaking the relay
  chain.

### Implementation Requirements

Modify `BeaconManager` to implement a **Transmission Queue**.

1. **Queue Structure:** Introduce a `Channel` or `Queue` of packets waiting to be advertised.
2. **Sequential Processing:** The `advertisePacket` function should add to this queue instead of
   launching a new job immediately.
3. **Consumer Loop:** A dedicated coroutine should consume this queue:
    * Take the next packet.
    * Advertise it for the full `MeshConfig.ADVERTISE_DURATION` (15s).
    * Only then move to the next packet.
4. **Queue Management:** Implement a "drop oldest" or "priority" strategy if the queue grows too
   large (e.g., > 5 items) to prevent latency buildup.

## 2. Add Transmission Jitter (Priority: Medium)

### Context & Analysis

When a wave of packets propagates through the mesh, multiple nodes might receive the same message at
roughly the same time.

* **The Issue:** Without jitter, Node B and Node C might try to rebroadcast the same message at the
  exact same instant.
* **The Consequence:** This increases the likelihood of radio collisions on the receiving end (Node
  D), or simply wastes battery by having two nodes shout the same thing simultaneously.

### Implementation Requirements

Modify `MeshRepository` or `MeshViewModel` (wherever the relay logic resides) to add **Random Jitter
**.

1. **Delay:** Before calling `beaconManager.advertisePacket()` for a *relayed* packet, wait for a
   random duration.
2. **Duration:** `Random.nextLong(min = 500, max = 3000)` (milliseconds).
3. **Benefit:** This desynchronizes the nodes, giving the receiver a better chance to hear
   individual advertisements clearly.
