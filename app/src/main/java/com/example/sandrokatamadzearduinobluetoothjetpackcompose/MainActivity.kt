package com.example.sandrokatamadzearduinobluetoothjetpackcompose

import android.Manifest
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: java.io.InputStream? = null

    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var connectedDeviceName by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Log.e("Bluetooth", "Some permissions were denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        permissionLauncher.launch(permissions.toTypedArray())

        setContent {
            MaterialTheme {
                BluetoothCarUI(
                    bluetoothAdapter = bluetoothAdapter,
                    onConnect = { device -> connectToDevice(device) },
                    onSend = { command -> sendCommand(command) },
                    connectedDeviceName = connectedDeviceName
                )
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.cancelDiscovery()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    withContext(Dispatchers.Main) {
                        connectedDeviceName = "❌ No Bluetooth permission"
                    }
                    return@launch
                }

                closeConnection()

                val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    device.name ?: "Unknown Device"
                } else {
                    "Unknown Device"
                }

                withContext(Dispatchers.Main) {
                    connectedDeviceName = "🔄 Connecting to $deviceName..."
                }

                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                inputStream = bluetoothSocket?.inputStream

                withContext(Dispatchers.Main) {
                    connectedDeviceName = "✅ Connected to $deviceName"
                }

                startListeningForData()

            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    connectedDeviceName = "❌ Connection failed: ${e.message}"
                    closeConnection()
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    connectedDeviceName = "❌ Permission denied"
                }
            }
        }
    }

    private fun startListeningForData() {
        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            while (bluetoothSocket?.isConnected == true) {
                try {
                    val bytes = inputStream?.read(buffer) ?: break
                    if (bytes > 0) {
                        val receivedData = String(buffer, 0, bytes)
                        Log.d("Bluetooth", "Received: $receivedData")
                    }
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Error reading data", e)
                    break
                }
            }
        }
    }

    private fun sendCommand(command: String) {
        try {
            outputStream?.write(command.toByteArray())
            outputStream?.flush()
            Log.d("Bluetooth", "Sent: $command")
        } catch (e: IOException) {
            e.printStackTrace()
            runOnUiThread {
                connectedDeviceName = "❌ Send failed - reconnect"
            }
        }
    }

    private fun closeConnection() {
        try {
            outputStream?.close()
            inputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            outputStream = null
            inputStream = null
            bluetoothSocket = null
        }
    }

    override fun onDestroy() {
        closeConnection()
        super.onDestroy()
    }
}

@Composable
fun BluetoothCarUI(
    bluetoothAdapter: BluetoothAdapter,
    onConnect: (BluetoothDevice) -> Unit,
    onSend: (String) -> Unit,
    connectedDeviceName: String?
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var isBluetoothEnabled by remember { mutableStateOf(true) }
    var isScanning by remember { mutableStateOf(false) }

    // Function to load paired devices
    fun loadPairedDevices() {
        try {
            if (bluetoothAdapter == null) {
                return
            }

            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermission) {
                devices = bluetoothAdapter.bondedDevices.toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Function to scan for devices
    fun scanForDevices() {
        if (bluetoothAdapter == null) return

        isScanning = true
        loadPairedDevices()

        Handler(Looper.getMainLooper()).postDelayed({
            isScanning = false
        }, 2000)
    }

    // Check if Bluetooth is enabled
    LaunchedEffect(Unit) {
        isBluetoothEnabled = bluetoothAdapter?.isEnabled == true
    }

    // Load paired devices on start
    LaunchedEffect(Unit) {
        loadPairedDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            "🚗 RC CAR CONTROLLER",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    connectedDeviceName?.startsWith("✅") == true ->
                        MaterialTheme.colorScheme.primaryContainer
                    connectedDeviceName?.startsWith("❌") == true ->
                        MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                }
            )
        ) {
            Text(
                text = connectedDeviceName ?: "⚡ Not Connected",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bluetooth status
        if (!isBluetoothEnabled) {
            Button(
                onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔘 Enable Bluetooth")
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Scan button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { loadPairedDevices() },
                modifier = Modifier.weight(1f).padding(end = 4.dp)
            ) {
                Text("🔄 Refresh")
            }

            Button(
                onClick = { scanForDevices() },
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                enabled = !isScanning
            ) {
                Text(if (isScanning) "⏳ Scanning..." else "📡 Scan")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            "📱 Paired Devices (${devices.size})",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(5.dp))

        // Device list
        if (devices.isEmpty()) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📭 No paired devices found")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Please pair your HC-06 in Android Settings first",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(devices) { device ->
                    val deviceName = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ActivityCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                device.name ?: "Unknown"
                            } else "Unknown Device"
                        } else {
                            device.name ?: "Unknown"
                        }
                    } catch (e: SecurityException) {
                        "Unknown Device"
                    }

                    val deviceAddress = device.address

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        onClick = { onConnect(device) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = deviceName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = deviceAddress,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = if (connectedDeviceName?.contains(deviceName) == true)
                                    Icons.Default.CheckCircle
                                else
                                    Icons.Default.Bluetooth,
                                contentDescription = null
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Controls (only show if connected)
        if (connectedDeviceName?.startsWith("✅") == true) {
            Text(
                "🎮 CONTROLS",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            ControlButton("F", onSend)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                ControlButton("L", onSend)
                ControlButton("S", onSend)
                ControlButton("R", onSend)
            }

            Spacer(modifier = Modifier.height(10.dp))

            ControlButton("B", onSend)

            Spacer(modifier = Modifier.height(15.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                ControlButton("N", onSend)
                ControlButton("H", onSend)
            }
        } else {
            Spacer(modifier = Modifier.height(50.dp))
            Text(
                "⚠️ Connect to a device to enable controls",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ControlButton(label: String, onSend: (String) -> Unit) {
    Button(
        onClick = { onSend(label) },
        modifier = Modifier.size(90.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when (label) {
                "F", "B", "L", "R" -> MaterialTheme.colorScheme.primary
                "N" -> MaterialTheme.colorScheme.secondary
                "H" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.tertiary
            }
        )
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge
        )
    }
}