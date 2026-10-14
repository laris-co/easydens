package co.laris.easydens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun f32bytes(v: Float): ByteArray =
    byteArrayOf((v.toBits() and 0xff).toByte(), ((v.toBits() shr 8) and 0xff).toByte(),
        ((v.toBits() shr 16) and 0xff).toByte(), ((v.toBits() shr 24) and 0xff).toByte())

// EasyDens ED2 BLE protocol — reverse-engineered from Brew Meister 6.7.0 and
// verified against a live HCI capture. See laris-co/easydens PROTOCOL.md.
private val ED2_SVC: UUID = UUID.fromString("41500000-d278-11eb-b8bc-0242ac130003")
private val CH_DENSITY: UUID = UUID.fromString("41500001-d278-11eb-b8bc-0242ac130003")
private val CH_TEMP: UUID = UUID.fromString("41500002-d278-11eb-b8bc-0242ac130003")
private val CH_RPC_REQ: UUID = UUID.fromString("41500003-d278-11eb-b8bc-0242ac130003")
private val CH_RPC_REP: UUID = UUID.fromString("41500004-d278-11eb-b8bc-0242ac130003")
private val CH_FIFTH: UUID = UUID.fromString("41500005-d278-11eb-b8bc-0242ac130003")
private val CH_SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private fun aes(data: ByteArray, key: ByteArray, decrypt: Boolean): ByteArray? = try {
    val c = Cipher.getInstance("AES/CBC/PKCS7Padding")
    c.init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
        SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
    c.doFinal(data)
} catch (e: Exception) { null }

private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
private fun f32le(b: ByteArray) =
    if (b.size >= 4) Float.fromBits((b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
        ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)) else null

class MainActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { connect() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Screen() } }
        ask.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
    }

    // ---- observable state ----
    private val mut = mutableStateOf("idle")
    private val mSerial = mutableStateOf("—")
    private val mDensity = mutableStateOf("—")
    private val mTemp = mutableStateOf("—")
    private val mBatt = mutableStateOf("—")
    private val mReply = mutableStateOf("—")
    private var gatt: BluetoothGatt? = null
    private var rpcReq: BluetoothGattCharacteristic? = null
    private val aesKey: ByteArray get() = mSerial.value.repeat(4).toByteArray(Charsets.UTF_8)
    private val queue = java.util.ArrayDeque<BluetoothGattCharacteristic>()

    private fun log(s: String) { runOnUiThread { mut.value = s } }
    private val densBuf = mutableListOf<Float>()
    private val tempBuf = mutableListOf<Float>()
    private var calPending = false

    // Kell (1975) water density in g/cm³ — matches Anton Paar's reference to <1e-5 near 30 °C.
    private fun waterAt(t: Double): Double {
        val num = 999.83952 + 16.945176*t - 7.9870401e-3*t*t - 46.170461e-6*t*t*t +
            105.56302e-9*t*t*t*t - 280.54253e-12*t*t*t*t*t
        return num / (1.0 + 16.879850e-3*t) / 1000.0
    }

    fun calibrate() {
        if (densBuf.size < 5 || tempBuf.size < 5) { log("need 5+ readings first"); return }
        val md = densBuf.takeLast(5).average()
        val mt = tempBuf.takeLast(5).average()
        log("cal: meas=%.5f @ %.2f°C, water=%.5f".format(md, mt, waterAt(mt)))
        calPending = true
        send(byteArrayOf(0x11))   // fetch stored offset first, finish in the reply handler
    }

    private fun connect() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val ad: BluetoothAdapter = bm.adapter ?: run { log("no bluetooth"); return }
        if (!ad.isEnabled) { log("bluetooth off"); return }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            log("scan permission denied"); return
        }
        log("scanning…")
        ad.bluetoothLeScanner.startScan(
            listOf<ScanFilter>(),   // wide: log every named device until the EasyDens appears
            ScanSettings.Builder().build(), object : ScanCallback() {
                override fun onScanResult(t: Int, r: ScanResult) {
                    val n = r.device.name ?: return
                    log("seen: $n ${r.rssi}")
                    if (!n.all { it.isDigit() }) return   // EasyDens advertises its serial
                    ad.bluetoothLeScanner.stopScan(this)
                    val dev: BluetoothDevice = r.device
                    log("connect ${dev.name ?: "?"}")
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED)
                        dev.connectGatt(this@MainActivity, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                }
            })
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            when (ns) {
                BluetoothProfile.STATE_CONNECTED -> { gatt = g; log("connected"); g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> log("disconnected")
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val s = g.getService(ED2_SVC) ?: run { log("no ED2 service"); return }
            rpcReq = s.getCharacteristic(CH_RPC_REQ)
            // Android GATT allows ONE operation at a time: arm notifications one by
            // one, then read the serial last (Device Information service 0x180A).
            queue.clear()
            listOf(CH_DENSITY, CH_TEMP, CH_RPC_REP, CH_FIFTH).forEach { u ->
                s.getCharacteristic(u)?.let { queue.add(it) }
            }
            step(g)
        }

        private fun step(g: BluetoothGatt) {
            val c = queue.poll() ?: run {
                g.getService(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"))
                    ?.getCharacteristic(CH_SERIAL)?.let { g.readCharacteristic(it) }
                return
            }
            g.setCharacteristicNotification(c, true)
            c.getDescriptor(CCCD)?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            } ?: step(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { step(g) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (c.uuid == CH_SERIAL) {
                val v = c.value?.toString(Charsets.UTF_8)?.trim()
                if (!v.isNullOrEmpty()) {
                    mSerial.value = v; log("serial $v")
                    android.os.Handler(android.os.Looper.getMainLooper()).apply {
                        post { send(byteArrayOf(0x0f)) }
                        postDelayed({ send(byteArrayOf(0x10)) }, 400)
                        postDelayed({ send(byteArrayOf(1, 0)) }, 800)
                    }
                }
            }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val d = c.value ?: return
            when (c.uuid) {
                CH_DENSITY -> aes(d, aesKey, true)?.let { p -> f32le(p)?.let {
                    val sg = it.toDouble() / waterAt(tempBuf.lastOrNull()?.toDouble() ?: 30.0)
                    mDensity.value = "%.5f g/cm³ · SG %.4f".format(it, sg)
                    densBuf.add(it); if (densBuf.size > 20) densBuf.removeAt(0)
                } }
                CH_TEMP -> aes(d, aesKey, true)?.let { p -> f32le(p)?.let {
                    mTemp.value = "%.2f °C".format(it)
                    tempBuf.add(it); if (tempBuf.size > 20) tempBuf.removeAt(0)
                } }
                CH_FIFTH -> f32le(d)?.let { mBatt.value = "%.3f V".format(it) }
                CH_RPC_REP -> aes(d.drop(2).toByteArray(), aesKey, true)?.let { p ->
                    mReply.value = "op=%d st=%d %s".format(p[0].toInt() and 0xff, p.getOrElse(1){255.toByte()}.toInt() and 0xff,
                        hex(p.drop(2).toByteArray()))
                    val op = p[0].toInt() and 0xff
                    if (op == 16 && p.size >= 4)
                        mReply.value += " standby=${(p[2].toInt() and 0xff) or ((p[3].toInt() and 0xff) shl 8)} min"
                    if (op == 17 && p.size >= 10) {
                        val off = f32le(p.copyOfRange(2, 6)) ?: 0f
                        val gain = f32le(p.copyOfRange(6, 10)) ?: 1f
                        mReply.value += " | offset=%.5f gain=%.3f".format(off, gain)
                        if (calPending) {
                            // reply [17, st, f32 offset, f32 gain] — Brew Meister: new = stored + (water − measured)
                            val md = densBuf.takeLast(5).average().toDouble()
                            val mt = tempBuf.takeLast(5).average().toDouble()
                            val newOff = off + (waterAt(mt) - md)
                            log("cal: stored=%.5f new=%.5f".format(off, newOff))
                            calPending = false
                            android.os.Handler(android.os.Looper.getMainLooper()).apply {
                                postDelayed({ send(byteArrayOf(8) + f32bytes(newOff.toFloat()) + f32bytes(1.0f)) }, 400)
                                postDelayed({ send(byteArrayOf(0x11)) }, 900)   // read back to confirm
                            }
                        }
                    }
                }
            }
        }
    }

    fun send(cmd: ByteArray) {
        val ct = aes(cmd, aesKey, false) ?: run { log("encrypt fail"); return }
        val frame = byteArrayOf(0x80.toByte(), ct.size.toByte()) + ct
        val c = rpcReq ?: run { log("not ready"); return }
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = frame
        gatt?.writeCharacteristic(c)
    }

    @Composable
    fun Screen() {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("EasyDens Ctrl", fontSize = 24.sp)
                Row { Text(mut.value, fontFamily = FontFamily.Monospace, color = Color(0xff80cbc4)) }
                listOf("serial" to mSerial.value, "density" to mDensity.value,
                    "temperature" to mTemp.value, "battery" to mBatt.value,
                    "reply" to mReply.value).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = Color.Gray); Text(v, fontFamily = FontFamily.Monospace)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { connect() }) { Text("Scan") }
                    Button(onClick = { send(byteArrayOf(1, 0)) }) { Text("Start") }
                    Button(onClick = { send(byteArrayOf(2)) }) { Text("Stop") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { send(byteArrayOf(0x0f)) }) { Text("LED") }
                    Button(onClick = { send(byteArrayOf(0x10)) }) { Text("Standby") }
                    Button(onClick = { send(byteArrayOf(0x11)) }) { Text("ZeroAdj") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { calibrate() }) { Text("Calibrate with water") }
                }
                Text("key = name×4 · AES-256-CBC · laris-co/easydens", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}
