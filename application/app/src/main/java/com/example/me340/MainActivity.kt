package com.example.me340

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.io.InputStream
import java.util.UUID
import kotlin.math.sqrt
//수정본

private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class SensorReading(val temperature: Float, val accX: Float, val accY: Float, val accZ: Float)
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
    var bluetoothSocket by remember { mutableStateOf<BluetoothSocket?>(null) }
    var sensorReadings by remember { mutableStateOf(listOf<SensorReading>()) }
    var dangerStatus by remember { mutableStateOf(DangerStatus(isDangerous = false, reason = "")) }
    var songList by remember { mutableStateOf<List<Song>>(emptyList()) }

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
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION)
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
        val totalAccel = sqrt(reading.accX * reading.accX + reading.accY * reading.accY + reading.accZ * reading.accZ)
        if (totalAccel > 25.0f) return DangerStatus(true, "급격한 움직임 감지 (낙상 의심)")
        if (totalAccel < 2.0f && sensorReadings.size > 5) return DangerStatus(true, "움직임 없음 (의식 불명 의심)")
        return DangerStatus(false, "")
    }

    fun listenForData(socket: BluetoothSocket) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val inputStream: InputStream = socket.inputStream
                val buffer = ByteArray(1024)
                while (socket.isConnected) {
                    val bytes = inputStream.read(buffer)
                    val incomingMessage = String(buffer, 0, bytes)
                    incomingMessage.split('\n').forEach { line ->
                        if (line.isNotBlank()) {
                            val parts = line.split(",").map { it.split(":") }.associate { if(it.size == 2) it[0] to it[1].toFloatOrNull() else "" to null }
                            val temp = parts["T"]
                            val ax = parts["AX"]
                            val ay = parts["AY"]
                            val az = parts["AZ"]
                            if (temp != null && ax != null && ay != null && az != null) {
                                val reading = SensorReading(temp, ax, ay, az)
                                launch(Dispatchers.Main) {
                                    sensorReadings = (sensorReadings + reading).takeLast(30)
                                    dangerStatus = checkDanger(reading)
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                 launch(Dispatchers.Main) {
                    Log.d("HealthMonitorScreen", "Connection lost.")
                    connectedDevice = null
                 }
            }
        }
    }
    
    fun doConnect(device: BluetoothDevice) {
        isConnecting = true
        showDeviceDialog = false
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                delay(500)
                socket.connect()
                launch(Dispatchers.Main) {
                    connectedDevice = device
                    bluetoothSocket = socket
                    isConnecting = false
                    listenForData(socket)
                }
            } catch (e: IOException) {
                Log.e("HealthMonitorScreen", "Connection failed", e)
                launch(Dispatchers.Main) { isConnecting = false }
            }
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    fun startDiscovery() {
        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (isScanning) bluetoothAdapter.cancelDiscovery()
        discoveredDevices = emptyList()
        bluetoothAdapter.startDiscovery()
        isScanning = true
    }

    val discoveryReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when(intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let {
                            if (!discoveredDevices.any { d -> d.address == it.address }) {
                                discoveredDevices = discoveredDevices + it
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> isScanning = false
                }
            }
        }
    }

    val bondStateReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    if (device?.bondState == BluetoothDevice.BOND_BONDED) {
                        doConnect(device)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val discoveryFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        val bondFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(discoveryReceiver, discoveryFilter)
        context.registerReceiver(bondStateReceiver, bondFilter)

        onDispose {
            context.unregisterReceiver(discoveryReceiver)
            context.unregisterReceiver(bondStateReceiver)
            bluetoothSocket?.close()
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
                    startDiscovery()
                    showDeviceDialog = true
                },
                onDisconnect = {
                    try { bluetoothSocket?.close() } catch (e: IOException) {}
                    connectedDevice = null
                    sensorReadings = emptyList()
                    dangerStatus = DangerStatus(false, "")
                }
            )

            if (showDeviceDialog) {
                DeviceDiscoveryDialog(
                    devices = discoveredDevices,
                    isScanning = isScanning,
                    onDismiss = { showDeviceDialog = false },
                    onDeviceClick = { device ->
                        if (device.bondState == BluetoothDevice.BOND_BONDED) {
                            doConnect(device)
                        } else {
                            device.createBond()
                        }
                    }
                )
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
            SensorDataPanel(reading = currentReading)
            Spacer(Modifier.height(16.dp))
            TemperatureChart(data = readings)
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            MusicPlayerUI(songs = songs)
            Spacer(modifier = Modifier.height(16.dp))
            if (connected) {
                Button(onClick = onDisconnect) { Text("Disconnect") }
            } else {
                Button(onClick = onConnectClick) { Text("블루투스 연결") }
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
                    Text("검색 중...", modifier = Modifier.padding(top = 8.dp))
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
                            Text(device.name ?: "Unnamed Device", modifier = Modifier.weight(1f))
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
fun SensorDataPanel(reading: SensorReading?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DataColumn("체온", "${reading?.temperature ?: "--"}°C")
            DataColumn("X", reading?.accX?.toString() ?: "--")
            DataColumn("Y", reading?.accY?.toString() ?: "--")
            DataColumn("Z", reading?.accZ?.toString() ?: "--")
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