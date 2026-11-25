package it.amico.audita

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID

/**
 * Audita: the control page (assets/index.html) in a WebView; its /api/... requests travel over Bluetooth LE.
 *
 * Protocol (firmware ble.c / api.c): service f9850001-..., RX f9850002 (write without response),
 * TX f9850003 (notify). Every packet: 1 flag byte (0x80 first, 0x40 last) + data.
 * Request "<id> <path>?<query>\n" + optional body; response "<id> <status>\n" + JSON.
 * One request at a time: the next one leaves when the answer arrives (or the wait expires).
 */
class MainActivity : Activity() {
    companion object {
        val SVC: UUID = UUID.fromString("f9850001-ea54-a88c-b14f-10ded3bd6d44")
        val RX: UUID = UUID.fromString("f9850002-ea54-a88c-b14f-10ded3bd6d44")
        val TX: UUID = UUID.fromString("f9850003-ea54-a88c-b14f-10ded3bd6d44")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME = "Audita"
        const val REQ_PERM = 1
        const val REQ_FILE = 2
        const val ST_OFF = 0
        const val ST_BUSY = 1
        const val ST_READY = 2
    }

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private val bt = HandlerThread("ble").apply { start() }
    private val h = Handler(bt.looper)               // all Bluetooth work on one thread

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var state = ST_OFF
    private var scanning = false
    private var wantConnected = true
    private var deviceName = DEVICE_NAME

    private class Req(val id: Int, val data: ByteArray, val timeoutMs: Long)
    private val queue = ArrayDeque<Req>()
    private var cur: Req? = null
    private var chunks: List<ByteArray> = emptyList()
    private var chunkIdx = 0
    private val resp = ByteArrayOutputStream()
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private lateinit var autoEq: AutoEq

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        applyBars(isNight())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                return try { startActivityForResult(Intent.createChooser(i, "Choose a ParametricEQ.txt file"), REQ_FILE); true }
                catch (e: Exception) { fileCb = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "AuditaBridge")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        autoEq = AutoEq(this) { code -> js(code) }
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @Deprecated("system API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val u = if (resultCode == RESULT_OK) data?.data else null
            fileCb?.onReceiveValue(if (u != null) arrayOf(u) else null)
            fileCb = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = isNight()
        applyBars(night)
        js("window.auTheme && window.auTheme($night)")
    }

    override fun onDestroy() {
        wantConnected = false
        h.post { stopScan(); closeGatt() }
        bt.quitSafely()
        autoEq.shutdown()
        super.onDestroy()
    }

    private fun isNight() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /* ---------------- bridge to the page ---------------- */
    inner class Bridge {
        @JavascriptInterface fun ready() { ui.post { ensurePermsAndConnect() } }
        @JavascriptInterface fun night(): Boolean = isNight()
        @JavascriptInterface fun connect() { ui.post { wantConnected = true; ensurePermsAndConnect() } }
        @JavascriptInterface fun request(id: Int, path: String, b64: String) {
            val body = if (b64.isEmpty()) ByteArray(0) else Base64.decode(b64, Base64.DEFAULT)
            val head = "$id $path\n".toByteArray(Charsets.UTF_8)
            val data = ByteArray(head.size + body.size)
            System.arraycopy(head, 0, data, 0, head.size)
            System.arraycopy(body, 0, data, head.size, body.size)
            h.post {
                if (state != ST_READY) { fail(id, "not connected"); return@post }
                queue.add(Req(id, data, if (body.isNotEmpty()) 30000L else 6000L))
                pump()
            }
        }
        /* AutoEq headphone database */
        @JavascriptInterface fun eqSearch(id: Int, query: String) = autoEq.search(id, query)
        @JavascriptInterface fun eqProfile(id: Int, path: String, name: String) = autoEq.profile(id, path, name)
        @JavascriptInterface fun eqIndexInfo(id: Int, refresh: Boolean) = autoEq.info(id, refresh)
    }

    @Suppress("DEPRECATION")
    private fun applyBars(dark: Boolean) {
        val c = if (dark) 0xFF0F1517.toInt() else 0xFFF4F2EC.toInt()
        window.statusBarColor = c
        window.navigationBarColor = c
        web.setBackgroundColor(c)
        var f = window.decorView.systemUiVisibility
        val light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        f = if (dark) f and light.inv() else f or light
        window.decorView.systemUiVisibility = f
    }

    private fun js(code: String) = ui.post { web.evaluateJavascript(code, null) }
    private fun status(st: Int, txt: String) { state = st; js("window.auStatus && window.auStatus($st, ${JSONObject.quote(txt)})") }
    private fun fail(id: Int, why: String) = js("window.auFail && window.auFail($id, ${JSONObject.quote(why)})")

    /* ---------------- permissions ---------------- */
    private fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermsAndConnect() {
        val missing = perms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), REQ_PERM); return }
        if (adapter?.isEnabled != true) {
            status(ST_OFF, "Bluetooth is off")
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: SecurityException) { }
            return
        }
        h.post { startScan() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) ensurePermsAndConnect()
            else status(ST_OFF, "Bluetooth permission denied")
        }
    }

    /* ---------------- scan and connect ---------------- */
    private val seen = HashSet<String>()
    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            h.post {
                if (!scanning) return@post
                val rec = result.scanRecord
                val name = rec?.deviceName ?: try { result.device.name } catch (e: SecurityException) { null }
                val hasSvc = rec?.serviceUuids?.any { it.uuid == SVC } == true
                if (seen.add(result.device.address)) status(ST_BUSY, "Searching for Audita… (${seen.size} Bluetooth devices seen)")
                if (!hasSvc && name != DEVICE_NAME) return@post
                deviceName = name ?: DEVICE_NAME
                stopScan()
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) { h.post { scanning = false; status(ST_OFF, "Scan failed ($errorCode)") } }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (gatt != null || scanning) return
        val sc = adapter?.bluetoothLeScanner ?: run { status(ST_OFF, "Bluetooth not available"); return }
        scanning = true
        seen.clear()
        status(ST_BUSY, "Searching for Audita…")
        /* no hardware filter (on some phones 128-bit UUID filters never match): checked in the callback */
        val s = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { sc.startScan(null, s, scanCb) } catch (e: SecurityException) { scanning = false; status(ST_OFF, "Bluetooth permission denied"); return }
        h.postDelayed({
            if (scanning) {
                stopScan()
                status(ST_OFF, if (seen.isEmpty()) "No Bluetooth devices seen: turn on Location on the phone and retry"
                               else "Audita not found among ${seen.size} devices: is the Audita firmware running?")
            }
        }, 15000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        status(ST_BUSY, "Connecting…")
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        gatt = null
        rx = null
    }

    private val timeoutToken = Any()

    private fun dropAll(why: String) {
        cur?.let { fail(it.id, why) }
        cur = null
        while (queue.isNotEmpty()) fail(queue.poll()!!.id, why)
        h.removeCallbacksAndMessages(timeoutToken)
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            h.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    /* balanced priority: very fast connection events would take processor time from the audio */
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    if (!g.requestMtu(247)) g.discoverServices()
                } else {
                    closeGatt()
                    dropAll("connection lost")
                    status(ST_OFF, "Disconnected")
                    if (wantConnected) h.postDelayed({ startScan() }, 2000)
                }
            }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) {
            h.post { if (st == BluetoothGatt.GATT_SUCCESS) mtu = m; g.discoverServices() }
        }
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            h.post {
                val s = g.getService(SVC)
                val r = s?.getCharacteristic(RX)
                val t = s?.getCharacteristic(TX)
                if (r == null || t == null) { status(ST_OFF, "Audita service missing: wrong firmware?"); closeGatt(); return@post }
                rx = r
                g.setCharacteristicNotification(t, true)
                val d = t.getDescriptor(CCCD)
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) } }
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            h.post {
                if (st == BluetoothGatt.GATT_SUCCESS) { status(ST_READY, "Connected to $deviceName"); pump() }
                else status(ST_OFF, "Notifications not enabled ($st)")
            }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            h.post {
                if (st != BluetoothGatt.GATT_SUCCESS) { cur?.let { fail(it.id, "write failed") }; cur = null; pump(); return@post }
                chunkIdx++
                writeNext()
            }
        }
        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value ?: return
            val copy = v.copyOf()
            h.post { onPacket(copy) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            h.post { onPacket(copy) }
        }
    }

    /* ---------------- requests ---------------- */
    private fun pump() {
        if (cur != null || state != ST_READY || queue.isEmpty()) return
        val r = queue.poll() ?: return
        cur = r
        val size = (mtu - 3 - 1).coerceAtLeast(20)
        val list = ArrayList<ByteArray>()
        var o = 0
        do {
            val k = minOf(size, r.data.size - o)
            val p = ByteArray(k + 1)
            p[0] = ((if (o == 0) 0x80 else 0) or (if (o + k >= r.data.size) 0x40 else 0)).toByte()
            System.arraycopy(r.data, o, p, 1, k)
            list.add(p)
            o += k
        } while (o < r.data.size)
        chunks = list
        chunkIdx = 0
        resp.reset()
        h.postAtTime({ if (cur === r) { fail(r.id, "no answer"); cur = null; pump() } }, timeoutToken, SystemClock.uptimeMillis() + r.timeoutMs)
        writeNext()
    }

    @SuppressLint("MissingPermission")
    private fun writeNext() {
        val g = gatt ?: return
        val c = rx ?: return
        if (cur == null || chunkIdx >= chunks.size) return
        val p = chunks[chunkIdx]
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = p
                g.writeCharacteristic(c)
            }
        }
        if (!ok) h.postDelayed({ writeNext() }, 15)          // system queue full: retry
    }

    private fun onPacket(p: ByteArray) {
        if (p.isEmpty()) return
        val f = p[0].toInt() and 0xFF
        if (f and 0x80 != 0) resp.reset()
        resp.write(p, 1, p.size - 1)
        if (f and 0x40 == 0) return
        val all = resp.toByteArray()
        resp.reset()
        val nl = all.indexOf('\n'.code.toByte())
        val head = String(all, 0, if (nl < 0) all.size else nl, Charsets.UTF_8)
        val body = if (nl < 0) "" else String(all, nl + 1, all.size - nl - 1, Charsets.UTF_8)
        val parts = head.trim().split(" ")
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return
        val code = parts.getOrNull(1)?.toIntOrNull() ?: 500
        js("window.auResp && window.auResp($id, $code, ${JSONObject.quote(body)})")
        if (cur?.id == id) {
            cur = null
            h.removeCallbacksAndMessages(timeoutToken)
            pump()
        }
    }
}
