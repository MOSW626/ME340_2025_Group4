package com.example.me340

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.me340.ui.theme.ME340Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import kotlin.math.sqrt

// ESP32 펌웨어에 설정된 UUID 값들
// 서비스 UUID
private val SENSOR_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
// 데이터 수신을 위한 특성(Characteristic) UUID (ESP32의 TX)
private val SENSOR_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
// 알림(Notification) 활성화를 위한 CCCD(Client Characteristic Configuration Descriptor) UUID (표준 값)
private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")


data class SensorReading(
    val accX: Float,
    val accY: Float,
    val accZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val temperature: Float, // Object Temperature
    val ambientTemp: Float
)
data class DangerStatus(val isDangerous: Boolean, val reason: String)
data class Song(val id: Long, val title: String, val artist: String, val contentUri: Uri)

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ME340Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val btAdapter = bluetoothAdapter
                    if (btAdapter != null) {
                        HealthMonitorScreen(btAdapter)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("This device does not support Bluetooth")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun HealthMonitorScreen(bluetoothAdapter: BluetoothAdapter) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var discoveredDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var isScanning by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var connectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var bluetoothGatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var sensorReadings by remember { mutableStateOf(listOf<SensorReading>()) }
    var dangerStatus by remember { mutableStateOf(DangerStatus(isDangerous = false, reason = "")) }
    var songList by remember { mutableStateOf<List<Song>>(emptyList()) }
    var lineBuffer by remember { mutableStateOf("") }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.entries.all { it.value }) {
            songList = getAudioFiles(context)
        } else {
            Log.d("HealthMonitorScreen", "Permissions denied.")
        }
    }

    LaunchedEffect(Unit) {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            songList = getAudioFiles(context)
        }
    }

    fun checkDanger(reading: SensorReading): DangerStatus {
        if (reading.temperature >= 38.0f) return DangerStatus(true, "고열 감지 (38°C 이상)")
        if (reading.temperature <= 35.0f) return DangerStatus(true, "저체온 감지 (35°C 이하)")
        val accelRMS = sqrt((reading.accX * reading.accX + reading.accY * reading.accY + reading.accZ * reading.accZ) / 3f)
        if (accelRMS > 15.0f) return DangerStatus(true, "급격한 충격 감지 (낙상 의심)")
        if (accelRMS < 1.2f && sensorReadings.size > 5) return DangerStatus(true, "움직임 없음 (의식 불명 의심)")
        return DangerStatus(false, "")
    }

    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("GattCallback", "Connected to GATT server.")
                    coroutineScope.launch(Dispatchers.Main) {
                        connectedDevice = gatt.device
                        isConnecting = false
                        bluetoothGatt = gatt
                    }
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("GattCallback", "Disconnected from GATT server.")
                    coroutineScope.launch(Dispatchers.Main) {
                        connectedDevice = null
                        isConnecting = false
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SENSOR_SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(SENSOR_CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        Log.d("GattCallback", "Subscribed to notifications")
                    } else {
                        Log.w("GattCallback", "Sensor characteristic not found")
                    }
                } else {
                    Log.w("GattCallback", "onServicesDiscovered received: $status")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value
                val receivedText = String(data, Charsets.UTF_8)
                lineBuffer += receivedText

                var newlineIndex: Int
                while (lineBuffer.indexOf('\n').also { newlineIndex = it } != -1) {
                    val line = lineBuffer.substring(0, newlineIndex).trim()
                    lineBuffer = lineBuffer.substring(newlineIndex + 1)

                    if (line.isNotBlank()) {
                        val parts = line.split(",")
                        if (parts.size == 8) {
                            try {
                                val reading = SensorReading(
                                    accX = parts[0].toFloat(), accY = parts[1].toFloat(), accZ = parts[2].toFloat(),
                                    gyroX = parts[3].toFloat(), gyroY = parts[4].toFloat(), gyroZ = parts[5].toFloat(),
                                    temperature = parts[6].toFloat(), ambientTemp = parts[7].toFloat()
                                )
                                coroutineScope.launch(Dispatchers.Main) {
                                    sensorReadings = (sensorReadings + reading).takeLast(30)
                                    dangerStatus = checkDanger(reading)
                                }
                            } catch (e: NumberFormatException) {
                                Log.e("GattCallback", "Failed to parse sensor data: $line", e)
                            }
                        } else {
                            Log.w("GattCallback", "Received malformed data line: $line")
                        }
                    }
                }
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        isConnecting = true
        showDeviceDialog = false
        device.connectGatt(context, false, gattCallback)
    }

    val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                if (!discoveredDevices.any { it.address == result.device.address }) {
                    discoveredDevices = discoveredDevices + result.device
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e("ScanCallback", "onScanFailed: code $errorCode")
                isScanning = false
            }
        }
    }

    fun startBleScan() {
        if (!bluetoothAdapter.isEnabled) {
            (context as? Activity)?.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (isScanning) return

        discoveredDevices = emptyList()
        isScanning = true
        val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SENSOR_SERVICE_UUID)).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        coroutineScope.launch {
            delay(10000) // Stop scan after 10 seconds
            if (isScanning) {
                bluetoothLeScanner.stopScan(scanCallback)
                isScanning = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            bluetoothLeScanner.stopScan(scanCallback)
            bluetoothGatt?.close()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ME340 prototype") }) }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            HealthDashboardUI(
                connected = connectedDevice != null,
                readings = sensorReadings,
                dangerStatus = dangerStatus,
                songs = songList,
                onConnectClick = {
                    startBleScan()
                    showDeviceDialog = true
                },
                onDisconnect = {
                    bluetoothGatt?.disconnect()
                    // gattCallback will handle state changes
                }
            )

            if (showDeviceDialog) {
                DeviceDiscoveryDialog(
                    devices = discoveredDevices,
                    isScanning = isScanning,
                    onDismiss = {
                        bluetoothLeScanner.stopScan(scanCallback)
                        isScanning = false
                        showDeviceDialog = false
                    },
                    onDeviceClick = { device ->
                        bluetoothLeScanner.stopScan(scanCallback)
                        isScanning = false
                        connectToDevice(device)
                    }
                )
            }

            if (isConnecting) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center){
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
fun HealthDashboardUI(
    connected: Boolean,
    readings: List<SensorReading>,
    dangerStatus: DangerStatus,
    songs: List<Song>,
    onConnectClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    val currentReading = readings.lastOrNull()
    val accelRMS = currentReading?.let {
        sqrt((it.accX * it.accX + it.accY * it.accY + it.accZ * it.accZ) / 3f)
    }

    if (dangerStatus.isDangerous) {
        LaunchedEffect(dangerStatus) {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
            toneGen.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (dangerStatus.isDangerous) {
                AlertPanel(reason = dangerStatus.reason)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
            SensorDataPanel(reading = currentReading, accelRMS = accelRMS)
            Spacer(Modifier.height(16.dp))
            TemperatureChart(data = readings)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (songs.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("음악 파일을 찾을 수 없습니다", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("\n- 저장 공간 접근 권한을 허용했는지 확인해주세요.\n- 'Music' 폴더에 MP3 파일이 있는지 확인해주세요.", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            } else {
                MusicPlayerUI(songs = songs)
            }
            Spacer(modifier = Modifier.height(16.dp))
            SpeakerConnectionGuide()
            Spacer(modifier = Modifier.height(16.dp))
            if (connected) {
                Button(onClick = onDisconnect) { Text("ESP32 연결 해제") }
            } else {
                Button(onClick = onConnectClick) { Text("ESP32 센서 연결") }
            }
        }
    }
}

@Composable
fun SpeakerConnectionGuide() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("스피커 연결 안내", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                text = "MH-M38 스피커는 스마트폰의 블루투스 설정에서 직접 페어링하고 연결해주세요. 한번 연결해두면 앱에서 음악 재생 시 자동으로 소리가 나옵니다.",
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
            Button(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            }) {
                Text("블루투스 설정으로 이동")
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiscoveryDialog(
    devices: List<BluetoothDevice>,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("주변 기기 검색") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (isScanning) {
                    CircularProgressIndicator()
                    Text("ESP32 검색 중...", modifier = Modifier.padding(top = 8.dp))
                }
                if (devices.isEmpty() && !isScanning) {
                    Text("검색된 기기가 없습니다.", modifier = Modifier.padding(vertical = 16.dp))
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(devices) { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onDeviceClick(device) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(device.name ?: "Unknown BLE Device", modifier = Modifier.weight(1f))
                            Text(device.address, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
fun MusicPlayerUI(songs: List<Song>) {
    val context = LocalContext.current
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    val mediaPlayer = remember { MediaPlayer() }

    fun playSong(song: Song) {
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(context, song.contentUri)
            mediaPlayer.prepare()
            mediaPlayer.start()
            isPlaying = true
            currentSong = song
        } catch (e: Exception) {
            Log.e("MusicPlayerUI", "Error playing song", e)
        }
    }

    fun findNextSong(): Song? {
        if (songs.isEmpty()) return null
        val currentIndex = songs.indexOf(currentSong)
        return if (currentIndex != -1 && currentIndex < songs.size - 1) songs[currentIndex + 1] else songs.first()
    }

    fun findPreviousSong(): Song? {
        if (songs.isEmpty()) return null
        val currentIndex = songs.indexOf(currentSong)
        return if (currentIndex != -1 && currentIndex > 0) songs[currentIndex - 1] else songs.last()
    }

    mediaPlayer.setOnCompletionListener {
        findNextSong()?.let { playSong(it) }
    }

    DisposableEffect(songs) {
        if (currentSong == null) {
            currentSong = songs.firstOrNull()
        }
        onDispose {
            mediaPlayer.release()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Now Playing", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(currentSong?.title ?: "선택된 노래 없음", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(currentSong?.artist ?: "", fontSize = 14.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { findPreviousSong()?.let { playSong(it) } }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(onClick = {
                    if(currentSong == null) {
                        songs.firstOrNull()?.let { playSong(it) }
                    } else {
                        if (isPlaying) mediaPlayer.pause() else mediaPlayer.start()
                        isPlaying = !isPlaying
                    }
                }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { findNextSong()?.let { playSong(it) } }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 100.dp)) {
                items(songs) { song ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { playSong(song) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(
                                text = song.title,
                                fontWeight = if (song == currentSong) FontWeight.Bold else FontWeight.Normal,
                                color = if (song == currentSong) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(song.artist, fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlertPanel(reason: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.8f)),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("위험 감지!", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(reason, color = Color.White, fontSize = 16.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
fun SensorDataPanel(reading: SensorReading?, accelRMS: Float?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ){
                DataColumn("체온", "${reading?.temperature ?: "--"}°C")
                DataColumn("충격량(RMS)", String.format("%.2f", accelRMS ?: 0f))
            }

            Spacer(Modifier.height(16.dp))

            Text("가속도 (X, Y, Z)", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DataColumn("X", String.format("%.2f", reading?.accX ?: 0f))
                DataColumn("Y", String.format("%.2f", reading?.accY ?: 0f))
                DataColumn("Z", String.format("%.2f", reading?.accZ ?: 0f))
            }
        }
    }
}

@Composable
fun DataColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 14.sp, color = Color.Gray)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TemperatureChart(data: List<SensorReading>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("실시간 체온 그래프", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(150.dp).background(Color.LightGray.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
        ) {
            if (data.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val temperatures = data.map { it.temperature }
                    val maxTemp = (temperatures.maxOrNull() ?: 40f).coerceAtLeast(38f)
                    val minTemp = (temperatures.minOrNull() ?: 35f).coerceAtMost(36f)
                    val tempRange = (maxTemp - minTemp).takeIf { it > 0 } ?: 1f
                    val stepX = if (data.size > 1) size.width / (data.size - 1) else 0f

                    if (data.size > 1) {
                        for (i in 0 until data.size - 1) {
                            val p1y = size.height * (1 - ((temperatures[i] - minTemp) / tempRange).coerceIn(0f, 1f))
                            val p2y = size.height * (1 - ((temperatures[i+1] - minTemp) / tempRange).coerceIn(0f, 1f))
                            drawLine(
                                color = Color.Blue,
                                start = Offset(i * stepX, p1y),
                                end = Offset((i + 1) * stepX, p2y),
                                strokeWidth = 4f
                            )
                        }
                    } else {
                        val p1y = size.height * (1 - ((temperatures[0] - minTemp) / tempRange).coerceIn(0f, 1f))
                        drawCircle(Color.Blue, radius=8f, center=Offset(0f, p1y))
                    }
                }
            }
        }
    }
}

fun getAudioFiles(context: Context): List<Song> {
    val songList = mutableListOf<Song>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

    try {
        context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                val artist = cursor.getString(artistColumn)
                val contentUri: Uri = Uri.withAppendedPath(collection, id.toString())
                songList.add(Song(id, title, artist, contentUri))
            }
        }
    } catch(e: Exception) {
        Log.e("getAudioFiles", "Error querying media store", e)
    }
    return songList
}
