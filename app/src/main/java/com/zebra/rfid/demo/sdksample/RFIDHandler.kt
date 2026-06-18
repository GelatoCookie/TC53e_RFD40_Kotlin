package com.zebra.rfid.demo.sdksample

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zebra.rfid.api3.*
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RFIDHandler : Readers.RFIDReaderEventHandler {
    companion object {
        private const val READER_DISCOVERY_RETRY_DELAY_MS = 0L
        private const val READER_CONNECT_DELAY_MS = 0L
        private const val READER_APPEAR_DELAY_MS = 0L
        private const val READER_CONFIG_BEEP_DELAY_MS = 500L
        private const val MAX_DISCOVERY_RETRIES = 4
        private const val DEBUG_TAG_READ_LOGS = false
        private const val DEBUG_TAG_SAMPLE_COUNT = 5
        private const val READER_LIST_LOG_SEPARATOR = "******************************************************************"
        private const val TAG = "RFID_SAMPLE"
    }

    private var readers: Readers? = null
    private var availableRFIDReaderList: ArrayList<ReaderDevice>? = null
    private var reader: RFIDReader? = null
    private var currentReaderDevice: ReaderDevice? = null
    private var eventHandler: EventHandler? = null
    private var appContext: Context? = null
    private var responseHandler: ResponseHandlerInterface? = null
    private val toneG = ToneGenerator(AudioManager.STREAM_ALARM, 100)
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduledExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    
    @Volatile
    private var initializationInProgress = false
    
    @Volatile
    private var connectionInProgress = false
    
    @Volatile
    private var resumeRequested = false
    
    @Volatile
    private var readersAttached = false
    
    @Volatile
    private var detachedEventInProgress = false
    
    @Volatile
    private var skipTc53eReaderSelection = false
    private var lastConnectTime: Long = -1

    fun onCreate(context: Context, handler: ResponseHandlerInterface) {
        appContext = context
        responseHandler = handler
        initSdk()
    }

    fun isReaderConnected(): Boolean {
        return if (reader != null && reader!!.isConnected) {
            true
        } else {
            Log.d(TAG, "reader is not connected")
            false
        }
    }

    fun isConnectionBusy(): Boolean {
        return initializationInProgress || connectionInProgress
    }

    fun setSkipTc53eReaderSelection(skip: Boolean) {
        skipTc53eReaderSelection = skip
    }

    fun onResume(): String {
        Log.d(TAG, "STEP: RFIDHandler onResume: connecting")
        resumeRequested = true
        if (isReaderConnected()) {
            return "Connected: " + reader!!.hostName + (if (lastConnectTime > 0) " ($lastConnectTime ms)" else "")
        }
        if (readers == null) {
            Log.d(TAG, "STEP: RFIDHandler onResume initSdk() ")
            initSdk()
            return if (initializationInProgress) "Initializing reader..." else "Preparing reader..."
        }
        Log.d(TAG, "STEP: RFIDHandler onResume connectReader() ")
        connectReader()
        return if (connectionInProgress) "Connecting..." else "Preparing connection..."
    }

    fun onPause() {
        Log.d(TAG, "STEP: RFIDHandler onPause: disconnecting")
        resumeRequested = false
        connectionInProgress = false
        disconnect()
    }

    fun onDestroy() {
        resumeRequested = false
        dispose()
    }

    private fun initSdk() {
        Log.d(TAG, "STEP: InitSDK")
        if (readers == null) {
            if (initializationInProgress) {
                Log.d(TAG, "STEP: Skip Duplicated InitSDK")
                return
            }
            Log.d(TAG, "STEP: InitSDK initializationInProgress, createInstanceAndConnect")
            initializationInProgress = true
            runOnBackground { createInstanceAndConnect() }
        } else if (resumeRequested) {
            connectReader()
        }
    }

    private fun createInstanceAndConnect() {
        var invalidUsageException: InvalidUsageException? = null
        Log.d(TAG, "CreateInstanceTask")
        try {
            readers = Readers(appContext, ENUM_TRANSPORT.ALL)
            availableRFIDReaderList = readers!!.GetAvailableRFIDReaderList()
            val availableCount = availableRFIDReaderList?.size ?: 0
            Log.d(TAG, "ECRT: Reader available in ALL Transport, size=$availableCount")
            if (availableCount == 0) {
                Log.d(TAG, "ECRT: Reader not available in ALL Transport trying with SERVICE_USB transport")
                scheduleReaderDiscoveryRetry(0)
                return
            }
            Log.d(TAG, "ECRT: #### Reader available in SERVICE_USB Transport")
            if (!availableRFIDReaderList.isNullOrEmpty()) {
                Log.d(TAG, "ECRT: #### SERVICE_USB list0 = " + availableRFIDReaderList!![0].name)
            }
        } catch (e: InvalidUsageException) {
            invalidUsageException = e
            e.printStackTrace()
        }
        val error = invalidUsageException
        initializationInProgress = false
        runOnMain {
            if (error != null) {
                responseHandler?.sendToast("Failed to get Available Readers\n" + error.info)
                readers = null
                readersAttached = false
            } else if (availableRFIDReaderList.isNullOrEmpty()) {
                responseHandler?.sendToast("No Available Readers to proceed")
                readers = null
                readersAttached = false
            } else if (resumeRequested) {
                connectReader()
            }
        }
    }

    @Synchronized
    private fun connectReader() {
        if (!resumeRequested || initializationInProgress || connectionInProgress || isReaderConnected()) {
            Log.d(TAG, "STEP: skip duplicated connectReader")
            return
        }
        Log.d(TAG, "STEP: connectReader connectionInProgress")
        connectionInProgress = true
        runOnBackground {
            Log.d(TAG, "ConnectionTask")
            if (!resumeRequested) {
                finishConnectionAttempt("")
                return@runOnBackground
            }
            Log.d(TAG, "STEP: getAvailableReader")
            getAvailableReader()
            if (reader == null) {
                finishConnectionAttempt("Failed to find or connect reader")
                return@runOnBackground
            }
            val scheduled = scheduleOnBackground({
                if (!resumeRequested) {
                    finishConnectionAttempt("")
                    return@scheduleOnBackground
                }
                Log.d(TAG, "STEP: finishConnectionAttempt(connect())")
                finishConnectionAttempt(connect())
            }, READER_CONNECT_DELAY_MS)
            if (!scheduled) {
                finishConnectionAttempt("Connection scheduling failed")
            }
        }
    }

    private fun finishConnectionAttempt(result: String) {
        connectionInProgress = false
        runOnMain {
            if (result.isNotEmpty()) {
                responseHandler?.onReaderStatusUpdate(result)
            }
        }
    }

    private fun scheduleReaderDiscoveryRetry(attempt: Int) {
        if (!resumeRequested) {
            initializationInProgress = false
            return
        }
        if (attempt >= MAX_DISCOVERY_RETRIES) {
            initializationInProgress = false
            availableRFIDReaderList = ArrayList()
            runOnMain {
                responseHandler?.sendToast("No Available Readers to proceed")
                readers = null
                readersAttached = false
            }
            return
        }
        val scheduled = scheduleOnBackground({
            try {
                Log.d(TAG, "ECRT: Reader not available wait loop=$attempt")
                Log.d(TAG, "ECRT: Reader not available in ALL Transport trying with RE_USB transport $attempt")
                readers!!.setTransport(ENUM_TRANSPORT.RE_USB)
                availableRFIDReaderList = readers!!.GetAvailableRFIDReaderList()
                if (!availableRFIDReaderList.isNullOrEmpty()) {
                    Log.d(TAG, "ECRT: #### Reader available in SERVICE_USB Transport")
                    Log.d(TAG, "ECRT: #### SERVICE_USB list0 = " + availableRFIDReaderList!![0].name)
                    initializationInProgress = false
                    runOnMain {
                        if (resumeRequested) {
                            connectReader()
                        }
                    }
                } else {
                    scheduleReaderDiscoveryRetry(attempt + 1)
                }
            } catch (e: InvalidUsageException) {
                initializationInProgress = false
                runOnMain {
                    responseHandler?.sendToast("Failed to get Available Readers\n" + e.info)
                    readers = null
                    readersAttached = false
                }
            }
        }, READER_DISCOVERY_RETRY_DELAY_MS)
        if (!scheduled) {
            initializationInProgress = false
            runOnMain { responseHandler?.sendToast("Reader discovery scheduling failed") }
        }
    }

    @Synchronized
    private fun getAvailableReader() {
        Log.d(TAG, "GetAvailableReader")
        if (readers != null) {
            if (!readersAttached) {
                Readers.attach(this)
                readersAttached = true
            }
            try {
                val availableReaders = readers!!.GetAvailableRFIDReaderList()
                if (!availableReaders.isNullOrEmpty()) {
                    availableRFIDReaderList = availableReaders
                    Log.d(TAG, "Found RFID Readers Size = " + availableRFIDReaderList!!.size)
                    val size = availableRFIDReaderList!!.size
                    Log.d(TAG, READER_LIST_LOG_SEPARATOR)
                    for (i in 0 until size) {
                        Log.d(TAG, "Available reader: " + availableRFIDReaderList!![i].name)
                    }
                    Log.d(TAG, READER_LIST_LOG_SEPARATOR)
                    if (skipTc53eReaderSelection) {
                        val externalIndex = findPreferredExternalReaderIndex(availableRFIDReaderList!!)
                        if (externalIndex < 0) {
                            Log.d(TAG, "Skipping host reader selection while power connected")
                            reader = null
                            currentReaderDevice = null
                            responseHandler?.onReaderSelectionInfo("Readers=$size | Skip RFIDTC53E while power connected")
                            return
                        }
                        val externalReaderDevice = availableRFIDReaderList!![externalIndex]
                        reader = externalReaderDevice.rfidReader
                        currentReaderDevice = externalReaderDevice
                        val externalSelection = ReaderSelection(externalIndex, "Skipped host RFIDTC53E while power connected", "EXTERNAL")
                        logSelectedReader(externalSelection, size)
                        return
                    }
                    val selection = selectReader(availableRFIDReaderList!!)
                    Log.d(TAG, READER_LIST_LOG_SEPARATOR)
                    val readerDevice = availableRFIDReaderList!![selection.index]
                    reader = readerDevice.rfidReader
                    currentReaderDevice = readerDevice
                    logSelectedReader(selection, size)
                }
            } catch (ie: InvalidUsageException) {
                ie.printStackTrace()
            }
        }
    }

    private fun selectReader(readers: ArrayList<ReaderDevice>): ReaderSelection {
        val eConnexIndex = findReaderIndex(readers, "RFD403", "RFD4030", "RFD4031", "RFD4031G")
        if (eConnexIndex >= 0) {
            return ReaderSelection(eConnexIndex, "eConnex reader fallback", "ECONNEX")
        }
        val bluetoothIndex = findReaderIndex(readers, "RFD40+", "RFD40P", "+")
        if (bluetoothIndex >= 0) {
            return ReaderSelection(bluetoothIndex, "Bluetooth sled fallback", "BLUETOOTH")
        }
        return ReaderSelection(0, "Defaulted to first available reader", "DEFAULT")
    }

    private fun findPreferredExternalReaderIndex(readers: ArrayList<ReaderDevice>): Int {
        val eConnexIndex = findReaderIndex(readers, "RFD403", "RFD4030", "RFD4031", "RFD4031G")
        if (eConnexIndex >= 0) {
            return eConnexIndex
        }
        val bluetoothIndex = findReaderIndex(readers, "RFD40+", "RFD40P", "+")
        if (bluetoothIndex >= 0) {
            return bluetoothIndex
        }
        for (i in readers.indices) {
            val readerName = readers[i].name ?: continue
            if (!readerName.contains("RFIDTC53E")) {
                return i
            }
        }
        return -1
    }

    private fun findReaderIndex(readers: ArrayList<ReaderDevice>, vararg nameHints: String): Int {
        for (i in readers.indices) {
            val readerName = readers[i].name ?: continue
            for (hint in nameHints) {
                if (readerName.contains(hint)) {
                    return i
                }
            }
        }
        return -1
    }

    private fun logSelectedReader(selection: ReaderSelection, totalReaders: Int) {
        val selectedName = availableRFIDReaderList!![selection.index].name
        Log.d(TAG, "Selected reader idx=" + selection.index + ", transport=" + selection.transport + ", reason=" + selection.reason + ", name=" + selectedName)
        responseHandler?.onReaderSelectionInfo("Readers=$totalReaders | Selected " + selection.transport + " idx=" + selection.index + " | " + selectedName)
    }

    private class ReaderSelection(val index: Int, val reason: String, val transport: String)

    fun isTC22R(): Boolean {
        return reader != null && reader!!.hostName.contains("TC22R")
    }

    override fun RFIDReaderAppeared(readerDevice: ReaderDevice) {
        Log.d(TAG, "RFIDReaderAppeared " + readerDevice.name)
        responseHandler?.sendToast("Reader attached: " + readerDevice.name)
        beepAppear()
        scheduleOnBackground({ connectReader() }, READER_APPEAR_DELAY_MS)
    }

    override fun RFIDReaderDisappeared(readerDevice: ReaderDevice) {
        Log.d(TAG, "RFIDReaderDisappeared " + readerDevice.name)
        detachedEventInProgress = true
        responseHandler?.sendToast("Reader detached: " + readerDevice.name)
        disconnect()
    }

    @Synchronized
    private fun connect(): String {
        if (!resumeRequested) {
            Log.d(TAG, "STEP: connect cancelled because resumeRequested=false")
            return ""
        }
        if (reader != null) {
            try {
                if (!reader!!.isConnected) {
                    if (!resumeRequested) {
                        Log.d(TAG, "STEP: connect cancelled before reader.connect because resumeRequested=false")
                        return ""
                    }
                    Log.d(TAG, "connect " + reader!!.hostName)
                    beep()
                    val startTime = System.currentTimeMillis()
                    reader!!.connect()
                    lastConnectTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "STEP: Reader Connected in " + lastConnectTime + "ms")
                    configureReader()
                    if (reader!!.isConnected) {
                        return "RFID Connected\r\n" + reader!!.hostName + " (" + lastConnectTime + " ms)"
                    }
                }
            } catch (e: InvalidUsageException) {
                Log.d(TAG, "STEP: connect API InvalidUsageException: " + e.vendorMessage + " " + e.info)
                e.printStackTrace()
            } catch (e: OperationFailureException) {
                e.printStackTrace()
                if (!resumeRequested) {
                    Log.d(TAG, "STEP: connect cancelled after pause/power-connected; ignoring failure " + e.results.toString())
                    return ""
                }
                Log.d(TAG, "STEP: connect API OperationFailureException: " + e.vendorMessage + " " + e.results.toString())
                val des = e.results.toString()
                return "Connection failed: $des"
            }
        }
        return ""
    }

    private fun configureReader() {
        Log.d(TAG, "ConfigureReader " + reader!!.hostName)
        IRFIDLogger.getLogger("SDKSAmpleApp").EnableDebugLogs(true)
        if (reader!!.isConnected) {
            try {
                if (eventHandler == null) eventHandler = EventHandler()
                reader!!.Events.addEventsListener(eventHandler)
                reader!!.Events.setHandheldEvent(true)
                reader!!.Events.setTagReadEvent(true)
                reader!!.Events.setAttachTagDataWithReadEvent(false)
                reader!!.Events.setInventoryStartEvent(true)
                reader!!.Events.setInventoryStopEvent(true)
                reader!!.Events.setReaderDisconnectEvent(true)
                val sdkVersion = com.zebra.rfid.api3.BuildConfig.VERSION_NAME
                Log.d(TAG, "ECRT: SDK version $sdkVersion")
                responseHandler?.onSdkVersionDetected(sdkVersion)
                scheduleOnBackground({ beep() }, READER_CONFIG_BEEP_DELAY_MS)
            } catch (e: InvalidUsageException) {
                e.printStackTrace()
            } catch (e: OperationFailureException) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    private fun disconnect() {
        Log.d(TAG, "ECRT: Disconnect")
        beep()
        val shouldReportDisconnected = !detachedEventInProgress
        try {
            if (reader != null) {
                if (eventHandler != null) {
                    reader!!.Events.removeEventsListener(eventHandler)
                }
                reader!!.disconnect()
                reader = null
                if (shouldReportDisconnected) {
                    responseHandler?.sendToast("Disconnected")
                }
            } else if (shouldReportDisconnected) {
                responseHandler?.sendToast("Disconnected")
            }
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            readersAttached = false
            detachedEventInProgress = false
        }
    }

    @Synchronized
    fun performInventory() {
        if (reader == null) return
        if (!reader!!.isConnected) return
        try {
            reader!!.Actions.Inventory.perform()
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun stopInventory() {
        if (reader == null) return
        if (!reader!!.isConnected) return
        try {
            reader!!.Actions.Inventory.stop()
        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        }
    }

    inner class EventHandler : RfidEventsListener {
        override fun eventReadNotify(e: RfidReadEvents) {
            if (reader == null || !reader!!.isConnected) {
                return
            }
            val myTags = reader!!.Actions.getReadTags(100)
            if (myTags != null) {
                if (DEBUG_TAG_READ_LOGS) {
                    val sampleCount = Math.min(myTags.size, DEBUG_TAG_SAMPLE_COUNT)
                    for (index in 0 until sampleCount) {
                        Log.d(TAG, "Tag ID=" + myTags[index].tagID + ", RSSI=" + myTags[index].peakRSSI)
                    }
                    if (myTags.size > sampleCount) {
                        Log.d(TAG, "Tag batch size=" + myTags.size + ", logged=$sampleCount")
                    }
                }
                responseHandler?.handleTagdata(myTags)
            }
        }

        override fun eventStatusNotify(rfidStatusEvents: RfidStatusEvents) {
            Log.d(TAG, "Status Notification: " + rfidStatusEvents.StatusEventData.statusEventType)
            if (rfidStatusEvents.StatusEventData.statusEventType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                    runOnBackground {
                        Log.d(TAG, "HANDHELD_TRIGGER_PRESSED")
                        responseHandler?.handleTriggerPress(true)
                    }
                }
                if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                    runOnBackground {
                        responseHandler?.handleTriggerPress(false)
                        Log.d(TAG, "HANDHELD_TRIGGER_RELEASED")
                    }
                }
            }
            if (rfidStatusEvents.StatusEventData.statusEventType == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                Log.d(TAG, "DISCONNECTION_EVENT: reader lost — cleaning up and attempting reconnect if active")
                handleDisconnectionEvent()
            }
        }

        private fun handleDisconnectionEvent() {
            runOnBackground {
                disconnect()
                runOnMain {
                    if (resumeRequested) {
                        Log.d(TAG, "DISCONNECTION_EVENT: resumeRequested=true, scheduling reconnect")
                        connectReader()
                    } else {
                        Log.d(TAG, "DISCONNECTION_EVENT: resumeRequested=false, staying disconnected")
                    }
                }
            }
        }
    }

    private fun runOnBackground(task: Runnable) {
        try {
            backgroundExecutor.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Background executor rejected task", e)
        }
    }

    private fun runOnMain(task: Runnable) {
        mainThreadHandler.post(task)
    }

    private fun scheduleOnBackground(task: Runnable, delayMs: Long): Boolean {
        try {
            scheduledExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            return true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Scheduled executor rejected task", e)
            return false
        }
    }

    private fun shutdownExecutors() {
        backgroundExecutor.shutdownNow()
        scheduledExecutor.shutdownNow()
    }

    @Synchronized
    private fun dispose() {
        disconnect()
        shutdownExecutors()
        try {
            toneG.release()
            if (readers != null) {
                readers!!.Dispose()
                readers = null
                readersAttached = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    interface ResponseHandlerInterface {
        fun handleTagdata(tagData: Array<TagData>)
        fun handleTriggerPress(pressed: Boolean)
        fun barcodeData(valData: String)
        fun sendToast(valData: String)
        fun onReaderStatusUpdate(status: String)
        fun onReaderSelectionInfo(info: String)
        fun onSdkVersionDetected(sdkVersion: String)
        fun onDeviceSerialNumberDetected(androidSerialNumber: String, readerSerialNumber: String)
    }

    private fun beep() {
        Log.d(TAG, "beep")
        runOnBackground { toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200) }
    }

    private fun beepAppear() {
        Log.d(TAG, "beepAppear")
        runOnBackground { toneG.startTone(ToneGenerator.TONE_DTMF_0, 200) }
    }

    fun getAndroidDeviceSerialNumber(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (appContext!!.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    val serial = Build.getSerial()
                    if (serial != null && !serial.equals("unknown", ignoreCase = true) && serial.isNotEmpty()) {
                        Log.d(TAG, "Got device serial from Build.getSerial(): $serial")
                        return serial
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Build.getSerial() failed: " + e.message)
            }
        }
        try {
            val serial = Build.SERIAL
            if (serial != null && !serial.equals("unknown", ignoreCase = true) && serial.isNotEmpty()) {
                Log.d(TAG, "Got device serial from Build.SERIAL: $serial")
                return serial
            }
        } catch (e: Exception) {
            Log.w(TAG, "Build.SERIAL failed: " + e.message)
        }
        val identifier = Build.DEVICE + "_" + Build.MANUFACTURER + "_" + Build.MODEL
        Log.d(TAG, "Using device identifier: $identifier")
        return identifier
    }

    fun getReaderSerialNumber(): String {
        if (reader != null && reader!!.isConnected) {
            try {
                val hostname = reader!!.hostName
                if (hostname != null && hostname.isNotEmpty()) {
                    return hostname
                }
            } catch (e1: Exception) {
                Log.w(TAG, "Failed to get hostname: " + e1.message)
            }
            if (currentReaderDevice != null) {
                try {
                    val deviceName = currentReaderDevice!!.name
                    if (deviceName != null && deviceName.isNotEmpty()) {
                        return deviceName
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to get name from ReaderDevice: " + e2.message)
                }
            }
            return "Connected"
        }
        return "Reader not connected"
    }

    fun fetchAndReportDeviceSerialNumbers() {
        runOnBackground {
            val androidSerial = getAndroidDeviceSerialNumber()
            val readerSerial = getReaderSerialNumber()
            Log.d(TAG, "Device serials - Android: $androidSerial, Reader: $readerSerial")
            runOnMain {
                responseHandler?.onDeviceSerialNumberDetected(androidSerial, readerSerial)
            }
        }
    }
}
