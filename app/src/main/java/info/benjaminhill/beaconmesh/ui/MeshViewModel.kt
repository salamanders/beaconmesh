package info.benjaminhill.beaconmesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import info.benjaminhill.beaconmesh.data.BeaconManager
import info.benjaminhill.beaconmesh.data.MeshRepository
import info.benjaminhill.beaconmesh.domain.model.Packet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.random.Random

class MeshViewModel(
    private val meshRepository: MeshRepository,
    private val beaconManager: BeaconManager
) : ViewModel() {

    val messages: StateFlow<List<Packet>> = meshRepository.messages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentStatus: StateFlow<Packet?> = meshRepository.currentStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Start listening to discovered packets from BeaconManager
        viewModelScope.launch {
            beaconManager.discoveredPackets.collect { packet ->
                meshRepository.processIncomingPacket(packet)
            }
        }

        // Start listening for relay requests
        viewModelScope.launch {
            meshRepository.relayQueue.collect { packet ->
                val jitter = Random.nextLong(500, 3000)
                Timber.d("Relaying packet: ${packet.sequence} with jitter: ${jitter}ms")
                delay(jitter)
                beaconManager.advertisePacket(packet)
            }
        }

        // Start Scanning immediately (permissions handled in UI)
        // Actually, we should wait for permissions. We'll expose a method.
    }

    fun startRadio() {
        beaconManager.startScanning()
    }

    fun stopRadio() {
        beaconManager.stopAll()
    }

    fun updateStatus(text: String) {
        meshRepository.updateStatus(text)
        // When status updates, we advertise it
        meshRepository.currentStatus.value?.let {
            beaconManager.advertisePacket(it)
        }
    }

    // Factory for manual DI
    class Factory(
        private val meshRepository: MeshRepository,
        private val beaconManager: BeaconManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MeshViewModel(meshRepository, beaconManager) as T
        }
    }
}
