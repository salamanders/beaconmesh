# BeaconMesh Context

## Project Overview

BeaconMesh is an Android application that implements a **Connectionless Mesh Protocol**. Unlike
traditional mesh networks that establish bidirectional sockets (GATT/L2CAP), BeaconMesh communicates
solely by changing the device's Bluetooth "Nametag" (Advertising Data) and scanning for the "
Nametags" of nearby devices. This avoids the stability issues of Android's Bluetooth stack when
handling multiple concurrent connections.

## Key Technologies

* **Language:** Kotlin (2.x)
* **UI Framework:** Jetpack Compose (Material 3)
* **Build System:** Gradle (Kotlin DSL)
* **Core API:** Google Nearby Connections (`Strategy.P2P_POINT_TO_POINT`)
* **Concurrency:** Kotlin Coroutines & Flows

## Architecture

The app follows a clean **MVVM** architecture:

### Domain Layer

* **`Packet.kt`**: Defines the binary data structure.
    * **Format:** `[NetID: 2b][TargetID: 4b][SourceID: 4b][Seq: 2b][TTL: 1b][Payload: Var]`
    * Serialized to Base64 for the Bluetooth Endpoint Name.
* **`MeshConfig.kt`**: Constants for timing, NetID (0xBEAC), and duty cycles.

### Data Layer

* **`BeaconManager.kt`**: Wrapper around `Nearby.getConnectionsClient`.
    * **Advertising:** Implements a "Duty Cycle" (Start Adv -> Wait 15s -> Stop).
    * **Scanning:** Continuously scans for `P2P_POINT_TO_POINT` advertisements.
* **`MeshRepository.kt`**: The brain of the mesh.
    * Maintains a `seenMessages` cache to prevent loops.
    * Handles **Relay Logic**: Decrements TTL and queues packets for rebroadcast if `TTL > 0`.

### UI Layer

* **`MeshViewModel.kt`**: Manages the data flow between Repository and UI. Exposes `messages` and
  `currentStatus` as `StateFlow`.
* **`MeshScreen.kt`**: Main Composable UI.
    * **Top:** Status input (User's current broadcast).
    * **Bottom:** Scrollable list of received/heard packets ("Live Feed").

## Protocol Specification

The payload is strictly constrained to **~80 bytes** (safe limit for Bluetooth 5.x Extended
Advertising Endpoint Name).

* **Encoding:** Base64
* **Fields:**
    1. **NetID:** Identifies the mesh network.
    2. **TargetID:** Hash of intended recipient (or broadcast ALL).
    3. **SourceID:** Random ID of the sender.
    4. **Sequence:** Rolling counter to identify new messages.
    5. **TTL:** Time-To-Live, decremented on each hop.
    6. **Payload:** The actual text message.

## Building and Running

* **Build Debug APK:**
  ```bash
  ./gradlew assembleDebug
  ```
* **Run Unit Tests:**
  ```bash
  ./gradlew test
  ```
* **Deploy to Device:**
  ```bash
  ./deploy_all.sh  # (If available) or standard Android Studio run
  ```

## Development Conventions

* **Dependency Injection:** Manual DI is used (Factories) instead of Hilt/Koin for simplicity.
* **Logging:** `Timber` is used for all logging.
* **Permissions:** Managed via `PermissionManager.kt` / `EnsurePermissions` composable.

## Known Issues & Roadmap

* **Rebroadcast Race Condition:** (Critical) `BeaconManager` currently cancels active advertisements
  if a new request comes in too fast. Needs a queue.
* **Collisions:** No jitter is currently implemented for relays, leading to potential packet
  collisions.
* **See `TODO.md` for detailed implementation plans.**

## Conventions

1. It is always useful to run the following at the start of a session:
   `FindFiles '**/*.{kt,md,xml}'`
2. Strongly prefer idiomatic Kotlin 2.x, modern Android conventions, Fluent code, and overall:
   Simplicity.
3.
    * **Safety and Immutability:** Prioritize null safety by using nullable types (`?`) and the `?.`
      and
      `?:` operators. Favor immutable data structures (`val`, `listOf`, `mapOf`) to prevent
      unintended
      side effects.
4. **Structured Concurrency:** Use coroutines for all asynchronous operations. Follow structured
   concurrency principles to ensure that coroutines are launched in a specific scope and are
   automatically cancelled when the scope is cancelled. This prevents resource leaks and simplifies
   error handling.
5. **Kotlin Time:** Use `kotlin.time.Duration` for all time-related values. It provides a type-safe
   and readable way to represent durations, avoiding ambiguity and potential errors from using raw
   numeric types.
6. **2025 Coding Standards:** Adhere to modern coding standards, including:
    * Using the latest stable versions of libraries and tools.
    * Following the official Kotlin coding conventions.
    * Writing unit tests for all new code.
    * Using a linter to enforce code style and catch potential errors.

## PRIMARY DIRECTIVE: NO ACTION WITHOUT EVIDENCE AND APPROVAL!

There is one PRIMARY DIRECTIVE that is more important than everything else:
**GATHER PROOF BEFORE DECIDING ON A COURSE OF ACTION.**

1. Gemini/Jules should never try a code change without first gathering **conclusive evidence** that
   the
   bug is fully understood, couldn't possibly be caused by something else, and the suggested fix is
   the best possible way to resolve the issue.
2. The bug MUST be logged in BUGS.md along with the evidence and best possible proposed resolution.
3. This course of action (proof + solution) must be discussed with the user and the fix agreed on
   before editing code. This is intentionally a very high bar before starting coding, but it must
   avoid "random attempts at fixing bugs" that have been a constant issue in the past.
4. The user MUST agree to the fix before you edit files.
