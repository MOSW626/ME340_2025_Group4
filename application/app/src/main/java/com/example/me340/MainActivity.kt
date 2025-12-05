package com.example.me340

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.me340.ui.theme.ME340Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ME340Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HealthMonitorScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun HealthMonitorScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sensorReadings by remember { mutableStateOf(listOf<SensorReading>()) }
    var dangerStatus by remember { mutableStateOf(DangerStatus(isDangerous = false, reason = "")) }
    var songList by remember { mutableStateOf<List<Song>>(emptyList()) }
    var currentScreen by remember { mutableStateOf("dashboard") } // "dashboard" or "settings"

    // WiFi Connection State
    var isConnecting by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var espIpAddress by remember { mutableStateOf("192.168.4.1") } // Default IP for AP Mode
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    var socket by remember { mutableStateOf<Socket?>(null) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.entries.all { it.value }) {
            songList = getAudioFiles(context)
        } else {
            Log.d("HealthMonitorScreen", "Permissions denied.")
        }
    }

    LaunchedEffect(Unit) {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
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

    fun connectToEsp(ip: String) {
        if (isConnected || isConnecting) return
        isConnecting = true
        connectionJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val newSocket = Socket(ip, 8888) // Assuming port 8888
                socket = newSocket
                withContext(Dispatchers.Main) {
                    isConnected = true
                    isConnecting = false
                }

                val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                while (isConnected && !newSocket.isClosed) {
                    val line = reader.readLine() ?: break // Connection closed
                    val parts = line.split(",")
                    if (parts.size == 8) {
                        try {
                            val reading = SensorReading(
                                accX = parts[0].toFloat(), accY = parts[1].toFloat(), accZ = parts[2].toFloat(),
                                gyroX = parts[3].toFloat(), gyroY = parts[4].toFloat(), gyroZ = parts[5].toFloat(),
                                temperature = parts[6].toFloat(), ambientTemp = parts[7].toFloat()
                            )
                            withContext(Dispatchers.Main) {
                                sensorReadings = (sensorReadings + reading).takeLast(30)
                                dangerStatus = checkDanger(reading)
                            }
                        } catch (e: NumberFormatException) {
                            Log.e("WiFiConnection", "Failed to parse sensor data: $line", e)
                        }
                    } else {
                        Log.w("WiFiConnection", "Received malformed data line: $line")
                    }
                }
            } catch (e: Exception) {
                Log.e("WiFiConnection", "Connection failed", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isConnected = false
                    isConnecting = false
                    socket?.close()
                    socket = null
                }
            }
        }
    }

    fun disconnectFromEsp() {
        if (!isConnected && !isConnecting) return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e("WiFiConnection", "Error closing socket", e)
            }
        }
        connectionJob?.cancel()
        socket = null
        isConnected = false
        isConnecting = false
    }

    DisposableEffect(Unit) {
        onDispose {
            disconnectFromEsp()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentScreen == "dashboard") "ME340 prototype" else "연결 설정") },
                navigationIcon = {
                    if (currentScreen == "settings") {
                        IconButton(onClick = { currentScreen = "dashboard" }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (currentScreen == "dashboard") {
                        IconButton(onClick = { currentScreen = "settings" }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                "dashboard" -> DashboardContent(
                    readings = sensorReadings,
                    dangerStatus = dangerStatus,
                    songs = songList
                )
                "settings" -> SettingsContent(
                    connected = isConnected,
                    ipAddress = espIpAddress,
                    onIpAddressChange = { espIpAddress = it },
                    onConnectClick = { connectToEsp(espIpAddress) },
                    onDisconnect = { disconnectFromEsp() }
                )
            }

            if (isConnecting) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center){
                    CircularProgressIndicator()
                    Text("연결 중...", color = Color.White, modifier = Modifier.padding(top = 60.dp))
                }
            }
        }
    }
}

@Composable
fun DashboardContent(
    readings: List<SensorReading>,
    dangerStatus: DangerStatus,
    songs: List<Song>
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (dangerStatus.isDangerous) {
            AlertPanel(reason = dangerStatus.reason)
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }
        SensorDataPanel(reading = currentReading, accelRMS = accelRMS)
        Spacer(Modifier.height(16.dp))
        SensorDataChart(data = readings)
        Spacer(Modifier.height(16.dp))

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
    }
}

@Composable
fun SettingsContent(
    connected: Boolean,
    ipAddress: String,
    onIpAddressChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EspConnectionControl(
            connected = connected,
            ipAddress = ipAddress,
            onIpAddressChange = onIpAddressChange,
            onConnectClick = onConnectClick,
            onDisconnect = onDisconnect
        )
        Spacer(modifier = Modifier.height(16.dp))
        SpeakerConnectionGuide()
    }
}

@Composable
fun EspConnectionControl(
    connected: Boolean,
    ipAddress: String,
    onIpAddressChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text("ESP32 센서 연결 (Wi-Fi)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
            if (connected) {
                Text(
                    text = "센서가 연결되었습니다. (IP: $ipAddress)",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("ESP32 연결 해제") }
            } else {
                Text(
                    text = "ESP32의 Wi-Fi AP에 먼저 연결한 후, IP 주소를 입력하고 센서 연결 버튼을 눌러주세요.",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = onIpAddressChange,
                    label = { Text("ESP32 IP 주소") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("센서 연결")
                    }
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Wi-Fi 설정")
                    }
                }
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
            Text("재생중", style = MaterialTheme.typography.titleMedium)
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
    val sdf = SimpleDateFormat("yyyy년 MM월 dd일", Locale.KOREA)
    val currentDate = sdf.format(Date())
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = currentDate,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ){
                DataColumn("체온", "${reading?.temperature ?: "--"}°C")
                DataColumn("주변 온도", "${reading?.ambientTemp ?: "--"}°C")
                DataColumn("충격량(RMS)", String.format("%.2f", accelRMS ?: 0f))
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
fun SensorDataChart(data: List<SensorReading>) {
    val tempColor = Color(0xFFE57373) // Red 300
    val ambientTempColor = Color(0xFFFFB74D) // Orange 300
    val shockColor = Color(0xFF64B5F6) // Blue 300

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("실시간 센서 그래프", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("■", color = tempColor, modifier = Modifier.padding(end = 4.dp))
            Text("체온", modifier = Modifier.padding(end = 12.dp))
            Text("■", color = ambientTempColor, modifier = Modifier.padding(end = 4.dp))
            Text("주변온도", modifier = Modifier.padding(end = 12.dp))
            Text("■", color = shockColor, modifier = Modifier.padding(end = 4.dp))
            Text("충격량")
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color.LightGray.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (data.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stepX = if (data.size > 1) size.width / (data.size - 1) else 0f

                    // Temperature data normalization values
                    val allTemperatures = data.flatMap { listOf(it.temperature, it.ambientTemp) }
                    val maxTemp = (allTemperatures.maxOrNull() ?: 40f).coerceAtLeast(38f)
                    val minTemp = (allTemperatures.minOrNull() ?: 35f).coerceAtMost(36f)
                    val tempRange = (maxTemp - minTemp).takeIf { it > 0 } ?: 1f

                    // Shock data normalization values
                    val shocks = data.map { reading ->
                        sqrt((reading.accX * reading.accX + reading.accY * reading.accY + reading.accZ * reading.accZ) / 3f)
                    }
                    val maxShock = (shocks.maxOrNull() ?: 20f).coerceAtLeast(15f)
                    val minShock = (shocks.minOrNull() ?: 0f)
                    val shockRange = (maxShock - minShock).takeIf { it > 0 } ?: 1f


                    if (data.size > 1) {
                        for (i in 0 until data.size - 1) {
                            // Body Temperature
                            val temp1 = data[i].temperature
                            val temp2 = data[i+1].temperature
                            val p1yTemp = size.height * (1 - ((temp1 - minTemp) / tempRange).coerceIn(0f, 1f))
                            val p2yTemp = size.height * (1 - ((temp2 - minTemp) / tempRange).coerceIn(0f, 1f))
                            drawLine(color = tempColor, start = Offset(i * stepX, p1yTemp), end = Offset((i + 1) * stepX, p2yTemp), strokeWidth = 4f)

                            // Ambient Temperature
                            val ambient1 = data[i].ambientTemp
                            val ambient2 = data[i+1].ambientTemp
                            val p1yAmbient = size.height * (1 - ((ambient1 - minTemp) / tempRange).coerceIn(0f, 1f))
                            val p2yAmbient = size.height * (1 - ((ambient2 - minTemp) / tempRange).coerceIn(0f, 1f))
                            drawLine(color = ambientTempColor, start = Offset(i * stepX, p1yAmbient), end = Offset((i + 1) * stepX, p2yAmbient), strokeWidth = 4f)

                            // Shock
                            val shock1 = shocks[i]
                            val shock2 = shocks[i+1]
                            val p1yShock = size.height * (1 - ((shock1 - minShock) / shockRange).coerceIn(0f, 1f))
                            val p2yShock = size.height * (1 - ((shock2 - minShock) / shockRange).coerceIn(0f, 1f))
                            drawLine(color = shockColor, start = Offset(i * stepX, p1yShock), end = Offset((i + 1) * stepX, p2yShock), strokeWidth = 4f)
                        }
                    } else if (data.isNotEmpty()) {
                        val reading = data.first()
                        val p1yTemp = size.height * (1 - ((reading.temperature - minTemp) / tempRange).coerceIn(0f, 1f))
                        drawCircle(tempColor, radius = 8f, center = Offset(0f, p1yTemp))

                        val p1yAmbient = size.height * (1 - ((reading.ambientTemp - minTemp) / tempRange).coerceIn(0f, 1f))
                        drawCircle(ambientTempColor, radius = 8f, center = Offset(0f, p1yAmbient))

                        val p1yShock = size.height * (1 - ((shocks.first() - minShock) / shockRange).coerceIn(0f, 1f))
                        drawCircle(shockColor, radius = 8f, center = Offset(0f, p1yShock))
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
