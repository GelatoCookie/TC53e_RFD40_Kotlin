package com.zebra.rfid.demo.sdksample

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.zebra.rfid.api3.TagData
import java.util.*

class MainActivity : AppCompatActivity(), RFIDHandler.ResponseHandlerInterface {

    companion object {
        private const val TAGUSB = "RFID_SAMPLE_USB"
        private const val TAG = "RFID_SAMPLE"
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val EXTRA_USB_CONNECTED = "connected"
        private const val EXTRA_USB_CONFIGURED = "configured"
        private const val EXTRA_USB_MTP = "mtp"
        private const val EXTRA_USB_PTP = "ptp"
        private const val EXTRA_USB_MASS_STORAGE = "mass_storage"
        private const val EXTRA_USB_ADB = "adb"
        private const val EXTRA_USB_RNDIS = "rndis"
        private const val STATUS_CONNECTED_KEYWORD = "connected"
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 100
        private const val MAX_TAG_LINES = 200
        private const val TAG_LIST_REFRESH_DELAY_MS = 100L
        private const val READER_RESUME_DEBOUNCE_MS = 1200L
        private const val POWER_CONNECTED_EVENT_DEBOUNCE_MS = 2000L
        private val POWER_RECONNECT_ATTEMPT_DELAYS_MS = longArrayOf(500L, 1200L, 2500L)
        private const val POWER_RECONNECT_SUPPRESSION_TIMEOUT_MS = 11000L
        private const val POWER_RECONNECT_BUSY_RETRY_DELAY_MS = 400L
        private const val CARD_STAGGER_DELAY_MS = 80L
    }

    var statusTextViewRFID: TextView? = null
    var textrfid: ListView? = null
    var scanResult: TextView? = null
    var textTitle: TextView? = null
    private var statusMetaTextView: TextView? = null
    private var inventoryMetaTextView: TextView? = null
    private var inventoryControlsTitleTextView: TextView? = null
    private var tagReadsMetaTextView: TextView? = null
    private var tagListHeader: View? = null
    private var tagEmptyState: View? = null
    private var connectedFlashOverlay: View? = null
    private var toolbar: Toolbar? = null
    private var startInventoryButton: MaterialButton? = null
    private var stopInventoryButton: MaterialButton? = null
    private var heroCard: CardView? = null
    private var actionsCard: CardView? = null
    private var tagsCard: CardView? = null
    private var activeSnackbar: Snackbar? = null
    private var connectingDialog: AlertDialog? = null
    private var latestConnectedReaderName: String? = null
    private var latestSdkVersion: String? = null
    private var connectedDialogShown = false
    private var connectedFlashShown = false
    var rfidHandler: RFIDHandler? = null
    private var isBatteryReceiverRegistered = false
    private var rfidHandlerInitialized = false
    private var powerReconnectWindowActive = false
    private var powerReconnectAttemptIndex = 0
    private var powerReconnectStartedAtMs = 0L
    private val tagListLock = Any()
    private val displayedTagMap = LinkedHashMap<String, TagRowModel>()
    private val displayedTagLines = ArrayList<TagRowModel>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastReaderResumeRequestAtMs = 0L
    private var lastPowerConnectedHandledAtMs = 0L
    private var powerConnectedLatched = false
    private var suppressReconnectStatusUntilConnected = false
    private var usbConnected = false
    private var usbConfigured = false
    private var usbFileTransferModeActive = false
    private var tagLineAdapter: TagLineAdapter? = null
    private var tagListRefreshScheduled = false

    private val tagListRefreshRunnable = Runnable {
        val updatedLines: ArrayList<TagRowModel>
        synchronized(tagListLock) {
            updatedLines = ArrayList(displayedTagMap.values)
            tagListRefreshScheduled = false
        }
        displayedTagLines.clear()
        displayedTagLines.addAll(updatedLines)
        tagLineAdapter?.notifyDataSetChanged()
        updateTagReadSummary(updatedLines.size)
        if ((tagLineAdapter?.count ?: 0) > 0) {
            textrfid?.setSelection(tagLineAdapter!!.count - 1)
        }
    }

    private val powerReconnectAttemptRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!powerReconnectWindowActive) return
            if (rfidHandler == null || (rfidHandlerInitialized && rfidHandler!!.isReaderConnected())) {
                stopPowerReconnectWindow()
                return
            }
            if (rfidHandlerInitialized && rfidHandler!!.isConnectionBusy()) {
                Log.d(TAGUSB, "Power reconnect deferred: handler busy")
                mainHandler.postDelayed(this, POWER_RECONNECT_BUSY_RETRY_DELAY_MS)
                return
            }
            val attemptNumber = powerReconnectAttemptIndex + 1
            Log.d(TAGUSB, "Power reconnect attempt $attemptNumber/${POWER_RECONNECT_ATTEMPT_DELAYS_MS.size}")
            requestReaderResumeDebounced("power_disconnected_retry_$attemptNumber", true)
            powerReconnectAttemptIndex++
            if (powerReconnectAttemptIndex < POWER_RECONNECT_ATTEMPT_DELAYS_MS.size) {
                mainHandler.postDelayed(this, POWER_RECONNECT_ATTEMPT_DELAYS_MS[powerReconnectAttemptIndex])
            }
        }
    }

    private val powerReconnectTimeoutRunnable = Runnable {
        if (!powerReconnectWindowActive) return@Runnable
        val elapsedMs = if (powerReconnectStartedAtMs > 0L)
            SystemClock.elapsedRealtime() - powerReconnectStartedAtMs
        else
            POWER_RECONNECT_SUPPRESSION_TIMEOUT_MS
        powerReconnectWindowActive = false
        suppressReconnectStatusUntilConnected = false
        mainHandler.removeCallbacks(powerReconnectAttemptRunnable)
        powerReconnectStartedAtMs = 0L
        Log.d(TAGUSB, "Power reconnect timed out after ${elapsedMs}ms")
        if (rfidHandler != null && rfidHandlerInitialized && rfidHandler!!.isReaderConnected()) {
            return@Runnable
        }
        val timeoutStatus = "USB in used!!!\r\nUnplug the USB cable for reconnect"
        applyReaderStatus(timeoutStatus)
        showModernPopup(timeoutStatus)
    }

    private val mBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != null) Log.d(TAGUSB, "USB Client action: $action")

            if (ACTION_USB_STATE == action) {
                updateUsbModeFromIntent(intent, "broadcast")
                return
            }

            if (Intent.ACTION_POWER_CONNECTED == action) {
                refreshUsbModeFromStickyState()
                if (powerConnectedLatched) {
                    Log.d(TAGUSB, "STEP: Skip ACTION_POWER_CONNECTED because latch is active (waiting for disconnect)")
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastPowerConnectedHandledAtMs < POWER_CONNECTED_EVENT_DEBOUNCE_MS) {
                    Log.d(TAGUSB, "STEP: Skip duplicate ACTION_POWER_CONNECTED within debounce window")
                    return
                }
                lastPowerConnectedHandledAtMs = now
                powerConnectedLatched = true

                Log.d(TAGUSB, "STEP: ACTION_POWER_CONNECTED")
                Log.d(TAGUSB, "ACTION_POWER_CONNECTED usbState: connected=$usbConnected, configured=$usbConfigured, fileTransfer=$usbFileTransferModeActive")
                val handlerBusy = rfidHandlerInitialized && rfidHandler != null && rfidHandler!!.isConnectionBusy()
                Log.d(TAGUSB, "ACTION_POWER_CONNECTED state: reconnectWindowActive=$powerReconnectWindowActive, handlerBusy=$handlerBusy")

                if (powerReconnectWindowActive) {
                    Log.d(TAGUSB, "STEP: ACTION_POWER_CONNECTED during reconnect window -> cancel reconnect flow")
                }
                stopPowerReconnectWindow()
                suppressReconnectStatusUntilConnected = false

                if (!rfidHandlerInitialized || rfidHandler == null) return

                if (rfidHandler!!.isTC22R()) {
                    Log.d(TAGUSB, "STEP: TC22R, ignore ACTION_POWER_CONNECTED")
                    onReaderStatusUpdate("Connected to TC22R")
                    return
                }

                if (usbFileTransferModeActive) {
                    rfidHandler!!.setSkipTc53eReaderSelection(false)
                    Log.d(TAGUSB, "STEP: ACTION_POWER_CONNECTED in file-transfer mode -> keep RFID connected until reader disappears")
                    sendToast("USB file transfer active or debug mode\r\nRFID Disconnected\r\nWait for USB cable unplug for reconnect")
                    return
                }

                rfidHandler!!.setSkipTc53eReaderSelection(true)
                Log.d(TAGUSB, "STEP: ACTION_POWER_CONNECTED power-only -> disconnect RFID")
                rfidHandler!!.onPause()
                sendToast("RFID disconnected while USB power cable connected")
            }

            if (Intent.ACTION_POWER_DISCONNECTED == action) {
                Log.d(TAGUSB, "ACTION_POWER_DISCONNECTED")
                powerConnectedLatched = false
                usbConnected = false
                usbConfigured = false
                usbFileTransferModeActive = false
                if (rfidHandler == null) return
                rfidHandler!!.setSkipTc53eReaderSelection(false)
                if (rfidHandler!!.isReaderConnected()) {
                    stopPowerReconnectWindow()
                    Log.d(TAG, "Reader already connected")
                    sendToast("USB unplugged. Reader Connected")
                    return
                }
                startPowerReconnectWindow()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        statusTextViewRFID = findViewById(R.id.textViewStatusrfid)
        textrfid = findViewById(R.id.edittextrfid)
        textTitle = findViewById(R.id.textrfid)
        scanResult = findViewById(R.id.scanResult)
        statusMetaTextView = findViewById(R.id.statusMeta)
        inventoryMetaTextView = findViewById(R.id.inventoryMeta)
        inventoryControlsTitleTextView = findViewById(R.id.inventoryControlsTitle)
        tagReadsMetaTextView = findViewById(R.id.tagReadsMeta)
        tagListHeader = findViewById(R.id.layout2)
        tagEmptyState = findViewById(R.id.tagEmptyState)
        connectedFlashOverlay = findViewById(R.id.connectedFlashOverlay)
        startInventoryButton = findViewById(R.id.TestButton)
        stopInventoryButton = findViewById(R.id.TestButton2)
        heroCard = findViewById(R.id.heroCard)
        actionsCard = findViewById(R.id.actionsCard)
        tagsCard = findViewById(R.id.tagsCard)
        applyDynamicVersionTitle()
        applyReaderStatus(getString(R.string.status_initializing))
        setInventoryActive(false)
        animateCardsIn()
        tagLineAdapter = TagLineAdapter(this, displayedTagLines)
        textrfid?.adapter = tagLineAdapter
        textrfid?.emptyView = tagEmptyState

        val filter1 = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(ACTION_USB_STATE)
        }
        registerReceiver(mBatteryReceiver, filter1)
        isBatteryReceiverRegistered = true

        Log.d(TAG, "Step 1: Init RFIDHandler Class")
        rfidHandler = RFIDHandler()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBluetoothRuntimePermissions()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    BLUETOOTH_PERMISSION_REQUEST_CODE
                )
            } else {
                initializeRfidHandlerIfPermitted()
            }
        } else {
            initializeRfidHandlerIfPermitted()
        }
    }

    private fun refreshUsbModeFromStickyState() {
        try {
            val stickyUsbState = registerReceiver(null, IntentFilter(ACTION_USB_STATE))
            if (stickyUsbState != null) {
                updateUsbModeFromIntent(stickyUsbState, "sticky")
            } else {
                Log.d(TAGUSB, "USB_STATE sticky intent unavailable")
            }
        } catch (e: Exception) {
            Log.w(TAGUSB, "Failed to refresh USB state from sticky intent", e)
        }
    }

    private fun updateUsbModeFromIntent(usbStateIntent: Intent, source: String) {
        usbConnected = usbStateIntent.getBooleanExtra(EXTRA_USB_CONNECTED, false)
        usbConfigured = usbStateIntent.getBooleanExtra(EXTRA_USB_CONFIGURED, false)

        val hasMtp = usbStateIntent.hasExtra(EXTRA_USB_MTP)
        val hasPtp = usbStateIntent.hasExtra(EXTRA_USB_PTP)
        val hasMassStorage = usbStateIntent.hasExtra(EXTRA_USB_MASS_STORAGE)
        val hasAdb = usbStateIntent.hasExtra(EXTRA_USB_ADB)
        val hasRndis = usbStateIntent.hasExtra(EXTRA_USB_RNDIS)

        val mtp = usbStateIntent.getBooleanExtra(EXTRA_USB_MTP, false)
        val ptp = usbStateIntent.getBooleanExtra(EXTRA_USB_PTP, false)
        val massStorage = usbStateIntent.getBooleanExtra(EXTRA_USB_MASS_STORAGE, false)
        val adb = usbStateIntent.getBooleanExtra(EXTRA_USB_ADB, false)
        val rndis = usbStateIntent.getBooleanExtra(EXTRA_USB_RNDIS, false)

        val explicitDataModeKnown = hasMtp || hasPtp || hasMassStorage || hasAdb || hasRndis
        usbFileTransferModeActive = if (explicitDataModeKnown) {
            mtp || ptp || massStorage || adb || rndis
        } else {
            usbConnected && usbConfigured
        }

        Log.d(TAGUSB, "USB_STATE[$source] connected=$usbConnected, configured=$usbConfigured, mtp=$mtp, ptp=$ptp, mass_storage=$massStorage, adb=$adb, rndis=$rndis, explicitDataModeKnown=$explicitDataModeKnown, fileTransfer=$usbFileTransferModeActive")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            val permissionResults = HashMap<String, Int>()
            val permissionCount = minOf(permissions.size, grantResults.size)
            for (i in 0 until permissionCount) {
                permissionResults[permissions[i]] = grantResults[i]
            }

            val scanGranted = permissionResults[Manifest.permission.BLUETOOTH_SCAN] == PackageManager.PERMISSION_GRANTED
            val connectGranted = permissionResults[Manifest.permission.BLUETOOTH_CONNECT] == PackageManager.PERMISSION_GRANTED

            if (scanGranted && connectGranted) {
                suppressReconnectStatusUntilConnected = false
                initializeRfidHandlerIfPermitted()
                requestReaderResumeDebounced("permission_granted", true)
            } else {
                applyReaderStatus(getString(R.string.status_permission_required))
                showModernPopup(getString(R.string.status_permission_required))
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.rfid_connect -> {
                if (rfidHandler == null) return false
                if (rfidHandler!!.isReaderConnected()) {
                    Log.d(TAG, "Reader already connected")
                    sendToast("Already Connected")
                    return true
                }
                suppressReconnectStatusUntilConnected = false
                requestReaderResumeDebounced("menu_connect", true)
                true
            }
            R.id.rfid_disconnect -> {
                if (rfidHandlerInitialized) {
                    rfidHandler!!.onPause()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        stopPowerReconnectWindow()
        if (rfidHandlerInitialized) {
            Log.d(TAG, "STEP: onPause, disconnect")
            rfidHandler!!.onPause()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        Log.d(TAG, "STEP: onPostResume requestReaderResumeDebounced")
        requestReaderResumeDebounced("activity_post_resume", false)
    }

    override fun onDestroy() {
        stopPowerReconnectWindow()
        dismissConnectingDialog()
        Log.d(TAG, "STEP: onDestroy clean up, exit")
        if (isBatteryReceiverRegistered) {
            unregisterReceiver(mBatteryReceiver)
            isBatteryReceiverRegistered = false
        }
        super.onDestroy()
        if (rfidHandlerInitialized) {
            rfidHandler!!.onDestroy()
        }
    }

    private fun hasBluetoothRuntimePermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun canUseRfidHandler(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetoothRuntimePermissions()
    }

    private fun initializeRfidHandlerIfPermitted() {
        if (!canUseRfidHandler() || rfidHandlerInitialized) return
        Log.d(TAG, "Step 2: rfidHandler.onCreate for this MainActivity with UI Response Interface")
        rfidHandler!!.onCreate(this, this)
        rfidHandlerInitialized = true
    }

    fun StartInventory(view: View) {
        clearTagLines()
        setInventoryActive(true)
        rfidHandler!!.performInventory()
    }

    fun scanCode(view: View) {}

    fun StopInventory(view: View) {
        setInventoryActive(false)
        rfidHandler!!.stopInventory()
    }

    override fun handleTagdata(tagData: Array<TagData>) {
        synchronized(tagListLock) {
            for (data in tagData) {
                val tagId = data.tagID
                if (tagId.isNullOrEmpty()) continue
                displayedTagMap[tagId] = TagRowModel(tagId, data.peakRSSI.toString())
            }
            while (displayedTagMap.size > MAX_TAG_LINES) {
                val keyIterator = displayedTagMap.keys.iterator()
                if (keyIterator.hasNext()) {
                    displayedTagMap.remove(keyIterator.next())
                }
            }
            if (tagListRefreshScheduled) return
            tagListRefreshScheduled = true
        }
        mainHandler.postDelayed(tagListRefreshRunnable, TAG_LIST_REFRESH_DELAY_MS)
    }

    override fun handleTriggerPress(pressed: Boolean) {
        if (pressed) {
            runOnUiThread {
                clearTagLines()
                setInventoryActive(true)
            }
            rfidHandler!!.performInventory()
        } else {
            runOnUiThread {
                setInventoryActive(false)
            }
            rfidHandler!!.stopInventory()
        }
    }

    override fun barcodeData(valData: String) {
        runOnUiThread {
            scanResult?.text = getString(R.string.scan_result_value, valData)
            scanResult?.alpha = 0.6f
            scanResult?.animate()?.alpha(1f)?.setDuration(220L)?.start()
        }
    }

    override fun sendToast(valData: String) {
        runOnUiThread {
            if (shouldSuppressReconnectStatus(valData)) return@runOnUiThread
            applyReaderStatus(valData)
            showModernPopup(valData)
        }
    }

    fun updateRfidTitle(valData: String) {
        runOnUiThread {
            textTitle?.text = valData
        }
    }

    fun updateAppTitleWithSdkVersion(sdkVersion: String) {
        runOnUiThread {
            val appVersion = getAppVersionName()
            title = getString(R.string.app_name_with_version_and_sdk, appVersion, sdkVersion)
            latestSdkVersion = sdkVersion
            maybeShowConnectedDialog()
        }
    }

    override fun onReaderStatusUpdate(status: String) {
        runOnUiThread {
            if (shouldSuppressReconnectStatus(status)) return@runOnUiThread
            applyReaderStatus(status)
            val normalizedStatus = status.lowercase()
            if (normalizedStatus.contains(STATUS_CONNECTED_KEYWORD) && rfidHandlerInitialized) {
                rfidHandler!!.fetchAndReportDeviceSerialNumbers()
            }
        }
    }

    override fun onReaderSelectionInfo(info: String) {
        updateRfidTitle(info)
    }

    override fun onSdkVersionDetected(sdkVersion: String) {
        updateAppTitleWithSdkVersion(sdkVersion)
    }

    override fun onDeviceSerialNumberDetected(androidSerialNumber: String, readerSerialNumber: String) {
        runOnUiThread {
            Log.d(TAG, "Device Serial Numbers - Android: $androidSerialNumber, Reader: $readerSerialNumber")
            val serialInfo = "Android: $androidSerialNumber\nReader: $readerSerialNumber"
            statusMetaTextView?.text = serialInfo
            Log.d(TAG, serialInfo)
        }
    }

    private fun clearTagLines() {
        mainHandler.removeCallbacks(tagListRefreshRunnable)
        synchronized(tagListLock) {
            displayedTagMap.clear()
            tagListRefreshScheduled = false
        }
        displayedTagLines.clear()
        tagLineAdapter?.notifyDataSetChanged()
        updateTagReadSummary(0)
    }

    private fun requestReaderResume() {
        if (!rfidHandlerInitialized) {
            initializeRfidHandlerIfPermitted()
        }
        if (!rfidHandlerInitialized) {
            applyReaderStatus(getString(R.string.status_permission_required))
            return
        }
        val result = rfidHandler!!.onResume()
        if (result.isNotEmpty() && !shouldSuppressReconnectStatus(result)) {
            applyReaderStatus(result)
        }
    }

    private fun shouldSuppressReconnectStatus(status: String?): Boolean {
        if (!suppressReconnectStatusUntilConnected) return false
        val normalizedStatus = status?.lowercase() ?: ""
        if (normalizedStatus.contains(STATUS_CONNECTED_KEYWORD)) {
            logPowerReconnectSuccessIfActive()
            stopPowerReconnectWindow()
            suppressReconnectStatusUntilConnected = false
            return false
        }
        Log.d(TAGUSB, "Suppressing reconnect status until connected: $status")
        return true
    }

    private fun requestReaderResumeDebounced(source: String, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReaderResumeRequestAtMs < READER_RESUME_DEBOUNCE_MS) {
            Log.d(TAGUSB, "Skipping reconnect request due to debounce window. source=$source")
            return
        }
        lastReaderResumeRequestAtMs = now
        requestReaderResume()
    }

    private fun startPowerReconnectWindow() {
        stopPowerReconnectWindow()
        if (rfidHandler == null) return
        powerReconnectWindowActive = true
        powerReconnectAttemptIndex = 0
        powerReconnectStartedAtMs = SystemClock.elapsedRealtime()
        suppressReconnectStatusUntilConnected = true
        Log.d(TAGUSB, "Starting power-unplug reconnect window")
        mainHandler.postDelayed(powerReconnectAttemptRunnable, POWER_RECONNECT_ATTEMPT_DELAYS_MS[0])
        mainHandler.postDelayed(powerReconnectTimeoutRunnable, POWER_RECONNECT_SUPPRESSION_TIMEOUT_MS)
    }

    private fun stopPowerReconnectWindow() {
        powerReconnectWindowActive = false
        powerReconnectAttemptIndex = 0
        powerReconnectStartedAtMs = 0L
        mainHandler.removeCallbacks(powerReconnectAttemptRunnable)
        mainHandler.removeCallbacks(powerReconnectTimeoutRunnable)
    }

    private fun logPowerReconnectSuccessIfActive() {
        if (!powerReconnectWindowActive || powerReconnectStartedAtMs <= 0L) return
        val elapsedMs = SystemClock.elapsedRealtime() - powerReconnectStartedAtMs
        Log.d(TAGUSB, "Power reconnect succeeded in ${elapsedMs}ms after unplug")
    }

    private fun applyDynamicVersionTitle() {
        val version = getAppVersionName()
        title = getString(R.string.app_name_with_version, version)
        textTitle?.text = getString(R.string.rfid_status_title_with_version, version)
    }

    private fun getAppVersionName(): String {
        return try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    private fun applyReaderStatus(status: String) {
        statusTextViewRFID?.text = status
        val normalizedStatus = status.lowercase()
        if (isConnectingStatus(normalizedStatus)) {
            showConnectingDialog()
        } else {
            dismissConnectingDialog()
        }

        val backgroundResId: Int
        val metaResId: Int
        val titleColorResId: Int
        val inventoryTitleColorResId: Int
        val inventoryCardColorResId: Int

        when {
            normalizedStatus.contains(STATUS_CONNECTED_KEYWORD) -> {
                backgroundResId = R.drawable.bg_status_badge_connected
                metaResId = R.string.status_meta_connected
                titleColorResId = R.color.actionConnect
                inventoryTitleColorResId = R.color.actionConnect
                inventoryCardColorResId = R.color.inventoryControlsConnectedBackground
                latestConnectedReaderName = extractReaderName(status)
                maybeShowConnectedDialog()
                if (!connectedFlashShown) {
                    playConnectedFlashAnimation()
                    connectedFlashShown = true
                }
            }
            normalizedStatus.contains("detached") -> {
                backgroundResId = R.drawable.bg_status_badge_detached
                metaResId = R.string.status_meta_detached
                titleColorResId = R.color.textPrimary
                inventoryTitleColorResId = R.color.inventoryControlsDisconnected
                inventoryCardColorResId = R.color.surfaceColor
                connectedDialogShown = false
                connectedFlashShown = false
            }
            normalizedStatus.contains("attached") -> {
                backgroundResId = R.drawable.bg_status_badge_attached
                metaResId = R.string.status_meta_attached
                titleColorResId = R.color.textPrimary
                inventoryTitleColorResId = R.color.inventoryControlsDisconnected
                inventoryCardColorResId = R.color.surfaceColor
            }
            normalizedStatus.contains("disconnect") || normalizedStatus.contains("failed") || normalizedStatus.contains("permission") -> {
                backgroundResId = R.drawable.bg_status_badge_disconnected
                metaResId = R.string.status_meta_disconnected
                titleColorResId = R.color.appTitleDisconnected
                inventoryTitleColorResId = R.color.inventoryControlsDisconnected
                inventoryCardColorResId = R.color.surfaceColor
                connectedDialogShown = false
                connectedFlashShown = false
            }
            else -> {
                backgroundResId = R.drawable.bg_status_badge_pending
                metaResId = R.string.status_meta_connecting
                titleColorResId = R.color.textPrimary
                inventoryTitleColorResId = R.color.inventoryControlsDisconnected
                inventoryCardColorResId = R.color.surfaceColor
            }
        }

        statusTextViewRFID?.setBackgroundResource(backgroundResId)
        statusMetaTextView?.setText(metaResId)
        toolbar?.setTitleTextColor(ContextCompat.getColor(this, titleColorResId))
        inventoryControlsTitleTextView?.setTextColor(ContextCompat.getColor(this, inventoryTitleColorResId))
        actionsCard?.setCardBackgroundColor(ContextCompat.getColor(this, inventoryCardColorResId))
        pulseStatusBadge()
    }

    private fun setInventoryActive(isActive: Boolean) {
        inventoryMetaTextView?.setText(if (isActive) R.string.inventory_live else R.string.inventory_idle)
        startInventoryButton?.isEnabled = !isActive
        stopInventoryButton?.isEnabled = isActive
        startInventoryButton?.alpha = if (isActive) 0.72f else 1f
        stopInventoryButton?.alpha = if (isActive) 1f else 0.72f
    }

    private fun updateTagReadSummary(count: Int) {
        if (count <= 0) {
            tagReadsMetaTextView?.setText(R.string.tag_reads_empty_state)
            tagListHeader?.visibility = View.GONE
            tagEmptyState?.visibility = View.VISIBLE
            return
        }
        tagReadsMetaTextView?.text = getString(R.string.tag_reads_count, count)
        tagListHeader?.visibility = View.VISIBLE
        tagEmptyState?.visibility = View.GONE
    }

    private fun animateCardsIn() {
        animateCard(heroCard, 0L)
        animateCard(actionsCard, CARD_STAGGER_DELAY_MS)
        animateCard(tagsCard, CARD_STAGGER_DELAY_MS * 2L)
    }

    private fun animateCard(cardView: CardView?, startDelayMs: Long) {
        cardView?.let {
            it.alpha = 0f
            it.translationY = 36f
            it.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelayMs)
                .setDuration(320L)
                .start()
        }
    }

    private fun pulseStatusBadge() {
        statusTextViewRFID?.animate()?.cancel()
        statusTextViewRFID?.scaleX = 0.96f
        statusTextViewRFID?.scaleY = 0.96f
        statusTextViewRFID?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(180L)
            ?.start()
    }

    private fun showModernPopup(message: String) {
        val rootView = findViewById<View>(android.R.id.content) ?: return
        if (activeSnackbar?.isShown == true) {
            activeSnackbar?.dismiss()
        }
        activeSnackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT)
            .setAction(R.string.popup_dismiss) {
                activeSnackbar?.dismiss()
            }
        activeSnackbar?.apply {
            setBackgroundTint(ContextCompat.getColor(this@MainActivity, R.color.colorPrimaryDark))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            setActionTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorAccent))
            show()
        }
    }

    private fun isConnectingStatus(normalizedStatus: String): Boolean {
        return normalizedStatus.contains("connecting")
                || normalizedStatus.contains("preparing")
                || normalizedStatus.contains("initializing")
    }

    private fun showConnectingDialog() {
        if (isFinishing || isDestroyed) return
        if (connectingDialog?.isShowing == true) return

        val dialogContent = createFancyPopupContent(
            R.drawable.ic_connect,
            R.color.colorAccent,
            getString(R.string.dialog_connecting_title),
            getString(R.string.dialog_connecting_subtitle),
            getString(R.string.dialog_connecting_detail),
            true
        )

        connectingDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogContent)
            .setCancelable(false)
            .create()
        connectingDialog?.show()
    }

    private fun dismissConnectingDialog() {
        connectingDialog?.dismiss()
        connectingDialog = null
    }

    private class TagRowModel(val epc: String, val rssi: String)

    private class TagLineAdapter(context: Context, items: ArrayList<TagRowModel>) :
        ArrayAdapter<TagRowModel>(context, 0, items) {
        private val inflater: LayoutInflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var rowView = convertView
            if (rowView == null) {
                rowView = inflater.inflate(R.layout.list_item_tag, parent, false)
            }
            val item = getItem(position) ?: return rowView!!
            val epcView = rowView!!.findViewById<TextView>(R.id.tagEpcValue)
            val rssiView = rowView.findViewById<TextView>(R.id.tagRssiValue)
            epcView.text = item.epc
            rssiView.text = item.rssi
            return rowView
        }
    }

    private fun extractReaderName(status: String?): String? {
        if (status == null || !status.contains(":")) return null
        val parts = status.split(":", limit = 2)
        if (parts.size < 2) return null
        val readerName = parts[1].trim()
        return if (readerName.isEmpty()) null else readerName
    }

    private fun maybeShowConnectedDialog() {
        if (connectedDialogShown || latestConnectedReaderName == null || latestSdkVersion == null) return
        connectedDialogShown = true
        val dialogContent = createFancyPopupContent(
            R.drawable.ic_reader_status,
            R.color.actionConnect,
            getString(R.string.dialog_reader_connected_title),
            getString(R.string.dialog_reader_connected_subtitle),
            getString(R.string.dialog_reader_connected_detail, latestConnectedReaderName, latestSdkVersion),
            false
        )
        MaterialAlertDialogBuilder(this)
            .setView(dialogContent)
            .setPositiveButton(R.string.dialog_popup_action_got_it, null)
            .show()
    }

    private fun createFancyPopupContent(
        iconResId: Int,
        iconTintColorResId: Int,
        title: String,
        subtitle: String,
        detail: String,
        showProgress: Boolean
    ): View {
        val contentView = LayoutInflater.from(this).inflate(R.layout.dialog_status_popup, null, false)
        val iconView = contentView.findViewById<ImageView>(R.id.popupIcon)
        val titleView = contentView.findViewById<TextView>(R.id.popupTitle)
        val subtitleView = contentView.findViewById<TextView>(R.id.popupSubtitle)
        val detailView = contentView.findViewById<TextView>(R.id.popupDetail)
        val progressBar = contentView.findViewById<ProgressBar>(R.id.popupProgress)
        val chipView = contentView.findViewById<TextView>(R.id.popupChip)

        iconView.setImageResource(iconResId)
        iconView.setColorFilter(ContextCompat.getColor(this, iconTintColorResId))
        titleView.text = title
        subtitleView.text = subtitle
        detailView.text = detail

        if (showProgress) {
            chipView.setText(R.string.dialog_connecting_chip)
            progressBar.visibility = View.VISIBLE
            progressBar.indeterminateTintList = ContextCompat.getColorStateList(this, R.color.colorAccent)
            iconView.animate()
                .rotationBy(360f)
                .setDuration(900L)
                .setInterpolator(android.view.animation.LinearInterpolator())
                .withEndAction {
                    iconView.rotation = 0f
                }
                .start()
        } else {
            chipView.setText(R.string.dialog_connected_chip)
            progressBar.visibility = View.GONE
        }
        return contentView
    }

    private fun playConnectedFlashAnimation() {
        connectedFlashOverlay?.let { overlay ->
            overlay.animate().cancel()
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate()
                .alpha(0.28f)
                .setDuration(120L)
                .withEndAction {
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(130L)
                        .withEndAction {
                            overlay.animate()
                                .alpha(0.28f)
                                .setDuration(120L)
                                .withEndAction {
                                    overlay.animate()
                                        .alpha(0f)
                                        .setDuration(130L)
                                        .withEndAction {
                                            overlay.visibility = View.GONE
                                        }
                                        .start()
                                }
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }
}
