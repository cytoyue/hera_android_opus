package com.example.hera_app_dev

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.hera_app_dev.ui.theme.Hera_app_devTheme
import io.github.jaredmdobson.concentus.OpusDecoder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

class MainActivity : ComponentActivity() {
    private val logs = mutableStateListOf<String>()
    private var currentGatt: BluetoothGatt? = null
    private var currentDeviceAddress: String? = null
    private var autoReconnectEnabled by mutableStateOf(true)
    private var isConnecting = false

    private var connectionState by mutableStateOf("IDLE")
    private var captureEnabled by mutableStateOf(false)
    private var decodeEnabled by mutableStateOf(false)
    private var notifyPacketCount by mutableStateOf(0L)
    private var nonZeroMicPacketCount by mutableStateOf(0L)
    private var nonZeroDecPacketCount by mutableStateOf(0L)
    private var decodeUseDecFallbackCount by mutableStateOf(0L)
    private var discoveredServiceCount by mutableStateOf(0)
    private var decodeErrorCount by mutableStateOf(0L)
    private var seqLossCount by mutableStateOf(0L)
    private var lastSeq by mutableStateOf(-1)
    private var lastVad by mutableStateOf(-1)
    private var lastMicLen by mutableStateOf(0)
    private var lastDecLen by mutableStateOf(0)
    private var unexpectedPacketCount by mutableStateOf(0L)
    private var lastPacketLen by mutableStateOf(0)
    private var latestPacketHex by mutableStateOf("")
    private var subscribeOkCount by mutableStateOf(0L)
    private var subscribeFailCount by mutableStateOf(0L)
    private var subscribeTryCount by mutableStateOf(0L)
    private var isSubscribed by mutableStateOf(false)
    private var serviceDiscoverRetry by mutableStateOf(0)
    private var scanResultCount by mutableStateOf(0L)
    private var lastAutoConnectAttemptMs = 0L
    private var preferredTargetAddress: String? = null
    private val scanCandidates = ConcurrentHashMap<String, ScanCandidate>()

    private var packetFileStream: FileOutputStream? = null
    private var micFileStream: FileOutputStream? = null
    private val decodeQueue = LinkedBlockingQueue<ByteArray>(AE04_DECODE_QUEUE_LIMIT)
    private var decodeThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var opusDecoder: OpusDecoder? = null
    private var decodedFrameCount by mutableStateOf(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val subscribeRetryRunnable = Runnable {
        if (connectionState == "CONNECTED" && discoveredServiceCount > 0 && notifyPacketCount == 0L && !isSubscribed) {
            appendLog("Auto retry subscribe...")
            subscribeNotify()
            scheduleSubscribeRetry()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) {
                appendLog("Permissions granted")
                startScan()
            } else {
                appendLog("Permissions denied: ${denied.joinToString()}")
            }
        }

    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val devName = result.device.name.orEmpty()
            val advName = result.scanRecord?.deviceName.orEmpty()
            val merged = listOf(advName, devName).firstOrNull { it.isNotBlank() } ?: "Unknown"
            val addr = result.device.address ?: return
            scanResultCount++
            scanCandidates[addr] = ScanCandidate(
                address = addr,
                advName = advName,
                devName = devName,
                rssi = result.rssi,
                lastSeenMs = System.currentTimeMillis()
            )
            if (scanResultCount <= 6 || scanResultCount % 80L == 0L) {
                appendLog("Scan hit: $merged [$addr] rssi=${result.rssi}")
            }
            maybeAutoConnect(result.device, advName, devName, result.rssi)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        isConnecting = false
                        isSubscribed = false
                        serviceDiscoverRetry = 0
                        connectionState = "CONNECTED"
                        appendLog("Connected: ${gatt.device.address}, status=$status")
                        checkConnectPermissionAndRun {
                            val mtuReq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                gatt.requestMtu(247)
                            } else {
                                false
                            }
                            appendLog("requestMtu(247)=$mtuReq")
                            if (!mtuReq) {
                                // Some stacks don't support MTU request flow; discover services directly.
                                mainHandler.postDelayed({ gatt.discoverServices() }, 120)
                            }
                        }
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        isConnecting = false
                        isSubscribed = false
                        mainHandler.removeCallbacks(subscribeRetryRunnable)
                        connectionState = "DISCONNECTED"
                        appendLog("Disconnected: ${gatt.device.address}, status=$status")
                        checkConnectPermissionAndRun { gatt.close() }
                        if (currentGatt == gatt) {
                            currentGatt = null
                            currentDeviceAddress = null
                        }
                        if (autoReconnectEnabled) {
                            appendLog("Auto reconnect: restart scan for $AUTO_TARGET_NAME")
                            startScan()
                        }
                    }
                    else -> {
                        connectionState = "STATE=$newState"
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                appendLog("Services discovered, status=$status")
                dumpGattSummary(gatt.services)
                if (status == BluetoothGatt.GATT_SUCCESS && gatt.services.isNotEmpty()) {
                    val hasTarget = hasAe04Target(gatt.services)
                    if (!hasTarget) {
                        appendLog("Not target device (AE00/AE04 missing), disconnect and rescan")
                        disconnect()
                        if (autoReconnectEnabled) {
                            startScan()
                        }
                        return@runOnUiThread
                    }
                    preferredTargetAddress = gatt.device.address
                    subscribeNotify()
                    scheduleSubscribeRetry()
                } else {
                    maybeRetryServiceDiscovery(gatt, "status=$status services=${gatt.services.size}")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runOnUiThread {
                appendLog("MTU changed: mtu=$mtu status=$status")
                // Subscribe only after services are discovered. Do not subscribe in MTU callback.
                checkConnectPermissionAndRun {
                    mainHandler.postDelayed({ gatt.discoverServices() }, 120)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    subscribeOkCount++
                    isSubscribed = true
                    appendLog("Descriptor write OK: ${descriptor.uuid}")
                } else {
                    subscribeFailCount++
                    appendLog("Descriptor write FAIL: ${descriptor.uuid}, status=$status")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleAe04Packet(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleAe04Packet(value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Hera_app_devTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BleDebugScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(subscribeRetryRunnable)
        stopCapture()
        stopDecodePlayback()
        stopScan()
        disconnect()
        super.onDestroy()
    }

    @Composable
    private fun BleDebugScreen(modifier: Modifier = Modifier) {
        LaunchedEffect(Unit) {
            appendLog("Ready. Auto reconnect to $AUTO_TARGET_NAME is ON")
            if (hasRequiredPermissions()) {
                startScan()
            }
        }
        val bg = Brush.verticalGradient(
            colors = listOf(Color(0xFFF4F8F2), Color(0xFFE9F0FA))
        )
        val connColor = when (connectionState) {
            "CONNECTED" -> Color(0xFF1F8A4C)
            "CONNECTING" -> Color(0xFFC27A00)
            else -> Color(0xFFB23B3B)
        }
        val btnShape: Shape = RoundedCornerShape(12.dp)
        val logListState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                logListState.scrollToItem(logs.lastIndex)
            }
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(bg)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFDF8))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Hera BLE Debug", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text("Target: $AUTO_TARGET_NAME", fontFamily = FontFamily.Monospace)
            Text("Device: ${currentDeviceAddress ?: "-"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("Subscribed: $isSubscribed   Try:$subscribeTryCount", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Surface(color = connColor.copy(alpha = 0.15f)) {
                        Text(
                            "Connection: $connectionState",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = connColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FCFA))) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Notify", fontSize = 12.sp)
                        Text("$notifyPacketCount", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FCFA))) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Frames", fontSize = 12.sp)
                        Text("$decodedFrameCount", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFAF7))) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Errors", fontSize = 12.sp)
                        Text("$decodeErrorCount", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB33A3A))
                    }
                }
            }

            Text(
                "Sub OK/FAIL: $subscribeOkCount/$subscribeFailCount   Len:$lastPacketLen   MIC:$lastMicLen DEC:$lastDecLen   SEQ loss:$seqLossCount",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { requestPermissions() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = btnShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) { Text("Grant Perms", fontSize = 10.sp) }
                Button(
                    onClick = { startScan() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = btnShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) { Text("Start Scan", fontSize = 10.sp) }
                Button(onClick = {
                    connectBestCandidate()
                },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = btnShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00695C)
                    )
                ) { Text("Connect", fontSize = 10.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { subscribeNotify() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = btnShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00838F))
                ) { Text("Subscribe", fontSize = 10.sp) }
                Button(
                    onClick = { disconnect() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = btnShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA))
                ) { Text("Disconnect", fontSize = 10.sp) }
                Button(
                    onClick = { if (decodeEnabled) stopDecodePlayback() else startDecodePlayback() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = btnShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00))
                ) {
                    Text(if (decodeEnabled) "Stop Decode" else "Start Decode", fontSize = 10.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    autoReconnectEnabled = !autoReconnectEnabled
                    appendLog("Auto reconnect=${if (autoReconnectEnabled) "ON" else "OFF"}")
                    if (autoReconnectEnabled) startScan()
                },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = btnShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (autoReconnectEnabled) Color(0xFF2D6A4F) else Color(0xFF6D4C41)
                    )
                ) { Text(if (autoReconnectEnabled) "Auto Reconnect: ON" else "Auto Reconnect: OFF", fontSize = 10.sp) }
            }

            Text("Latest HEX: ${if (latestPacketHex.isBlank()) "-" else latestPacketHex}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10161C))
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    state = logListState
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFBFE6FF),
                            fontSize = 12.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val needed = requiredPermissions().filterNot { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            appendLog("Permissions already granted")
            return
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startScan() {
        if (!hasRequiredPermissions()) {
            appendLog("Missing permissions. Click Grant Perms")
            return
        }
        if (connectionState == "CONNECTED" || isConnecting) {
            appendLog("Skip scan: state=$connectionState")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            appendLog("Bluetooth is disabled")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        checkScanPermissionAndRun {
            scanner?.stopScan(scanCallback)
            scanner?.startScan(null, settings, scanCallback)
            appendLog("Scan started")
        }
    }

    private fun stopScan() {
        checkScanPermissionAndRun {
            scanner?.stopScan(scanCallback)
            appendLog("Scan stopped")
        }
    }

    private fun connectToAddress(address: String, reason: String) {
        if (!hasRequiredPermissions()) {
            appendLog("Missing permissions. Click Grant Perms")
            return
        }
        if (isConnecting || connectionState == "CONNECTED") {
            return
        }
        stopScan()
        disconnect()
        val adapter = bluetoothAdapter ?: run {
            appendLog("Bluetooth adapter unavailable")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            appendLog("Invalid address: $address")
            return
        }
        isConnecting = true
        connectionState = "CONNECTING"
        currentDeviceAddress = address
        appendLog("Connecting to $address ($reason)")
        currentGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkConnectPermissionAndRunWithResult {
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private fun maybeAutoConnect(device: BluetoothDevice, advName: String, devName: String, rssi: Int) {
        if (!autoReconnectEnabled) return
        val name = when {
            advName.isNotBlank() -> advName
            devName.isNotBlank() -> devName
            else -> device.name.orEmpty()
        }
        val preferred = preferredTargetAddress
        val isPreferredAddress = preferred != null && preferred.equals(device.address, ignoreCase = true)
        val isTargetByName = name.equals(AUTO_TARGET_NAME, ignoreCase = true)
        if (!isPreferredAddress && !isTargetByName) return
        if (connectionState == "CONNECTED" || isConnecting) return
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAttemptMs < 1600L) return
        lastAutoConnectAttemptMs = now
        val address = device.address ?: return
        connectToAddress(address, "auto:$name rssi=$rssi")
    }

    private fun connectBestCandidate() {
        if (connectionState == "CONNECTED" || isConnecting) {
            appendLog("Connect blocked: state=$connectionState")
            return
        }
        val now = System.currentTimeMillis()
        val best = scanCandidates.values
            .asSequence()
            .filter { now - it.lastSeenMs <= 15000L }
            .filter { it.isPreferred(preferredTargetAddress) || it.isStrictHera() }
            .sortedWith(
                compareByDescending<ScanCandidate> { it.isPreferred(preferredTargetAddress) }
                    .thenByDescending { it.isNameMatched() }
                    .thenByDescending { it.rssi }
            )
            .firstOrNull()
        if (best == null) {
            appendLog("Connect blocked: no scan candidate yet, scanning...")
            startScan()
            return
        }
        val displayName = best.bestName().ifBlank { "Unknown" }
        appendLog("Manual connect -> $displayName [${best.address}] rssi=${best.rssi}")
        connectToAddress(best.address, "manual:$displayName")
    }

    private fun subscribeNotify() {
        val gatt = currentGatt ?: run {
            appendLog("Subscribe blocked: no active GATT connection")
            return
        }
        if (connectionState != "CONNECTED") {
            appendLog("Subscribe blocked: connectionState=$connectionState")
            return
        }
        subscribeTryCount++
        val characteristic =
            gatt.getService(AE04_SERVICE_UUID)?.getCharacteristic(AE04_CHAR_UUID)
                ?: gatt.services
                    .asSequence()
                    .flatMap { it.characteristics.asSequence() }
                    .firstOrNull { it.uuid.toString().contains("ae04", ignoreCase = true) }

        if (characteristic == null) {
            appendLog("Characteristic not found: $AE04_CHAR_UUID (fallback also failed)")
            dumpGattSummary(gatt.services)
            return
        }

        checkConnectPermissionAndRun {
            val supportsNotify = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val supportsIndicate = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            appendLog(
                "Subscribe target=${characteristic.uuid}, props=0x${characteristic.properties.toString(16)}, notify=$supportsNotify, indicate=$supportsIndicate"
            )
            if (!supportsNotify && !supportsIndicate) {
                subscribeFailCount++
                appendLog("Characteristic does not support notify/indicate")
                return@checkConnectPermissionAndRun
            }
            val ok = gatt.setCharacteristicNotification(characteristic, true)
            appendLog("setCharacteristicNotification=$ok")
            val cccd = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
            if (cccd == null) {
                appendLog("CCCD not found (UUID 0x2902)")
                subscribeFailCount++
                return@checkConnectPermissionAndRun
            }
            cccd.value =
                if (supportsNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val writeOk = gatt.writeDescriptor(cccd)
            appendLog("writeDescriptor(CCCD)=$writeOk")
            if (!writeOk) {
                subscribeFailCount++
                isSubscribed = false
            }
            if (!decodeEnabled) {
                startDecodePlayback()
            }
        }
    }

    private fun scheduleSubscribeRetry() {
        mainHandler.removeCallbacks(subscribeRetryRunnable)
        mainHandler.postDelayed(subscribeRetryRunnable, 1500)
    }

    private fun maybeRetryServiceDiscovery(gatt: BluetoothGatt, reason: String) {
        if (serviceDiscoverRetry < 2) {
            serviceDiscoverRetry++
            appendLog("Retry discoverServices($serviceDiscoverRetry): $reason")
            checkConnectPermissionAndRun {
                mainHandler.postDelayed({ gatt.discoverServices() }, 400)
            }
            return
        }
        appendLog("Service discovery failed after retries, reconnecting...")
        disconnect()
        if (autoReconnectEnabled) {
            startScan()
        }
    }

    private fun disconnect() {
        mainHandler.removeCallbacks(subscribeRetryRunnable)
        val gatt = currentGatt ?: return
        checkConnectPermissionAndRun {
            gatt.disconnect()
            gatt.close()
        }
        currentGatt = null
        currentDeviceAddress = null
        connectionState = "IDLE"
        appendLog("Disconnected and closed GATT")
    }

    private fun startCapture() {
        if (captureEnabled) {
            return
        }
        val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(null), "captures")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val packetFile = File(dir, "ae04_packets_$now.bin")
        val micFile = File(dir, "ae04_mic_$now.opus")
        packetFileStream = FileOutputStream(packetFile)
        micFileStream = FileOutputStream(micFile)
        captureEnabled = true
        appendLog("Capture started: ${packetFile.absolutePath}")
        appendLog("Mic opus file: ${micFile.absolutePath}")
    }

    private fun stopCapture() {
        if (!captureEnabled) {
            return
        }
        captureEnabled = false
        packetFileStream?.flush()
        packetFileStream?.close()
        packetFileStream = null
        micFileStream?.flush()
        micFileStream?.close()
        micFileStream = null
        appendLog("Capture stopped")
    }

    private fun startDecodePlayback() {
        if (decodeEnabled) {
            return
        }
        try {
            val sampleRate = 16000
            opusDecoder = OpusDecoder(sampleRate, 1)
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    (minBuffer * 2).coerceAtLeast(4096),
                    AudioTrack.MODE_STREAM
                )
            }
            audioTrack?.play()
            decodedFrameCount = 0
            decodeEnabled = true
            decodeThread = Thread {
                decodeLoop()
            }.apply {
                name = "opus-decode-play-thread"
                start()
            }
            appendLog("Decode+play started")
        } catch (e: Exception) {
            appendLog("Decode start failed: ${e.message}")
            decodeEnabled = false
        }
    }

    private fun stopDecodePlayback() {
        if (!decodeEnabled && decodeThread == null) {
            return
        }
        decodeEnabled = false
        decodeThread?.interrupt()
        decodeThread = null
        decodeQueue.clear()
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
        opusDecoder = null
        appendLog("Decode+play stopped")
    }

    private fun decodeLoop() {
        val pcm = ShortArray(960)
        while (decodeEnabled) {
            try {
                val opusFrame = decodeQueue.take()
                if (isAllZero(opusFrame)) {
                    continue
                }
                val decoder = opusDecoder ?: continue
                val decodedSamples = decoder.decode(opusFrame, 0, opusFrame.size, pcm, 0, 960, false)
                if (decodedSamples > 0) {
                    audioTrack?.write(pcm, 0, decodedSamples)
                    decodedFrameCount++
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                decodeErrorCount++
                appendLog("Decode err: ${e.message}")
            }
        }
    }

    private fun handleAe04Packet(packet: ByteArray) {
        notifyPacketCount++
        if (notifyPacketCount <= 5 || notifyPacketCount % 100L == 0L) {
            appendLog("Notify packet #$notifyPacketCount len=${packet.size}")
        }
        if (notifyPacketCount == 1L) {
            isSubscribed = true
            mainHandler.removeCallbacks(subscribeRetryRunnable)
        }
        lastPacketLen = packet.size
        latestPacketHex = packet.toHex(40)
        if (captureEnabled) {
            try {
                packetFileStream?.write(packet)
            } catch (e: Exception) {
                appendLog("Write packet file failed: ${e.message}")
            }
        }
        if (packet.size < AE04_PACKET_SIZE_MIN) {
            unexpectedPacketCount++
            return
        }
        val isFullPacket = packet.size >= AE04_PACKET_SIZE_FULL
        val seqIndex = if (isFullPacket) AE04_SEQ_INDEX_FULL else AE04_SEQ_INDEX_MIC_ONLY
        val micLenIndex = if (isFullPacket) AE04_MIC_LEN_INDEX_FULL else AE04_MIC_LEN_INDEX_MIC_ONLY
        val decLenIndex = if (isFullPacket) AE04_DEC_LEN_INDEX_FULL else AE04_DEC_LEN_INDEX_MIC_ONLY

        val vad = packet[0].toInt() and 0xFF
        val seq = packet[seqIndex].toInt() and 0xFF
        val micLenRaw = packet[micLenIndex].toInt() and 0xFF
        val decLenRaw = packet[decLenIndex].toInt() and 0xFF
        val micLen = micLenRaw.coerceIn(0, AE04_MIC_FRAME_SIZE)
        lastVad = vad
        lastMicLen = micLenRaw
        lastDecLen = decLenRaw
        if (lastSeq >= 0) {
            val expected = (lastSeq + 1) and 0xFF
            if (seq != expected) {
                seqLossCount++
            }
        }
        lastSeq = seq
        var effectiveMicLen = micLen
        if (effectiveMicLen == 0 && packet.size >= AE04_PACKET_SIZE_MIN) {
            // 兜底：部分固件可能未填mic_len，按固定40字节尝试解码
            effectiveMicLen = AE04_MIC_FRAME_SIZE
        }
        if (effectiveMicLen == 0) return
        val micFrame = packet.copyOfRange(1, 1 + effectiveMicLen)
        if (!isAllZero(micFrame)) {
            nonZeroMicPacketCount++
        }
        if (isFullPacket && packet.size >= (1 + AE04_MIC_FRAME_SIZE + AE04_DEC_FRAME_SIZE)) {
            val decFrame = packet.copyOfRange(1 + AE04_MIC_FRAME_SIZE, 1 + AE04_MIC_FRAME_SIZE + AE04_DEC_FRAME_SIZE)
            val decIsZero = isAllZero(decFrame)
            if (!decIsZero) {
                nonZeroDecPacketCount++
            }
            if (notifyPacketCount % 500L == 0L) {
                appendLog("Frame stat micNZ=$nonZeroMicPacketCount decNZ=$nonZeroDecPacketCount decFallback=$decodeUseDecFallbackCount")
            }
        }
        if (captureEnabled) {
            try {
                micFileStream?.write(micFrame)
            } catch (e: Exception) {
                appendLog("Write mic file failed: ${e.message}")
            }
        }
        if (decodeEnabled) {
            while (decodeQueue.size >= AE04_DECODE_QUEUE_TARGET) {
                decodeQueue.poll()
            }
            if (!decodeQueue.offer(micFrame)) {
                decodeQueue.poll()
                decodeQueue.offer(micFrame)
            }
        }
    }

    private fun isAllZero(data: ByteArray): Boolean {
        for (value in data) {
            if (value.toInt() != 0) {
                return false
            }
        }
        return true
    }

    private fun dumpGattSummary(services: List<BluetoothGattService>) {
        discoveredServiceCount = services.size
        if (services.isEmpty()) {
            appendLog("No GATT services discovered")
            return
        }
        appendLog("GATT summary:")
        services.forEach { service ->
            appendLog("  SVC ${service.uuid}")
            service.characteristics.forEach { ch ->
                appendLog("    CH  ${ch.uuid} props=0x${ch.properties.toString(16)}")
            }
        }
    }

    private fun hasAe04Target(services: List<BluetoothGattService>): Boolean {
        val svc = services.firstOrNull { it.uuid == AE04_SERVICE_UUID } ?: return false
        return svc.characteristics.any { it.uuid == AE04_CHAR_UUID }
    }

    private fun appendLog(message: String) {
        val ts = timeFormat.format(Date())
        Log.d("HeraBleDebug", message)
        runOnUiThread {
            logs.add("[$ts] $message")
            if (logs.size > 300) {
                logs.removeAt(0)
            }
        }
    }

    private fun checkScanPermissionAndRun(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("BLUETOOTH_SCAN not granted")
            return
        }
        block()
    }

    private fun checkConnectPermissionAndRun(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("BLUETOOTH_CONNECT not granted")
            return
        }
        block()
    }

    private fun <T> checkConnectPermissionAndRunWithResult(block: () -> T?): T? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("BLUETOOTH_CONNECT not granted")
            return null
        }
        return block()
    }

    companion object {
        private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        private val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val AE04_SERVICE_UUID: UUID =
            UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        private val AE04_CHAR_UUID: UUID =
            UUID.fromString("0000ae04-0000-1000-8000-00805f9b34fb")
        private const val AE04_PACKET_SIZE_MIN = 44
        private const val AE04_PACKET_SIZE_FULL = 84
        private const val AE04_MIC_FRAME_SIZE = 40
        private const val AE04_DEC_FRAME_SIZE = 40
        private const val AE04_DECODE_QUEUE_LIMIT = 12
        private const val AE04_DECODE_QUEUE_TARGET = 6
        private const val AE04_SEQ_INDEX_FULL = 81
        private const val AE04_MIC_LEN_INDEX_FULL = 82
        private const val AE04_DEC_LEN_INDEX_FULL = 83
        private const val AE04_SEQ_INDEX_MIC_ONLY = 41
        private const val AE04_MIC_LEN_INDEX_MIC_ONLY = 42
        private const val AE04_DEC_LEN_INDEX_MIC_ONLY = 43
        private const val AUTO_TARGET_NAME = "Hera"
        private const val LOG_LINES_ON_SCREEN = 24
    }
}

private data class ScanCandidate(
    val address: String,
    val advName: String,
    val devName: String,
    val rssi: Int,
    val lastSeenMs: Long
) {
    fun bestName(): String = if (advName.isNotBlank()) advName else devName
    fun isNameMatched(): Boolean = bestName().equals("Hera", ignoreCase = true)
    fun isStrictHera(): Boolean = bestName().equals("Hera", ignoreCase = true)
    fun isPreferred(preferredAddress: String?): Boolean =
        preferredAddress != null && preferredAddress.equals(address, ignoreCase = true)
}

private fun ByteArray.toHex(limit: Int = size): String {
    val end = if (size < limit) size else limit
    val body = (0 until end).joinToString(" ") { idx -> "%02X".format(this[idx]) }
    return if (size > end) "$body ..." else body
}
