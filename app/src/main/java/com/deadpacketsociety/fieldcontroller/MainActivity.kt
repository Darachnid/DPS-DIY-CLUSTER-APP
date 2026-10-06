package com.deadpacketsociety.fieldcontroller

import android.Manifest
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileOutputStream


class MainActivity : ComponentActivity(), BleController.Events {


    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var connectionText: TextView
    private lateinit var captureText: TextView
    private lateinit var gpsText: TextView
    private lateinit var nodeText: TextView
    private lateinit var observationText: TextView
    private lateinit var recentText: TextView
    private lateinit var recentContainer: LinearLayout
    private lateinit var mapView: MapView
    private lateinit var ble: BleController

    private lateinit var windowRoot: FrameLayout
    private lateinit var stealthOverlay: FrameLayout
    private lateinit var stealthWifiText: TextView
    private lateinit var stealthBleText: TextView
    private lateinit var stealthCellText: TextView



    private lateinit var wigleName: EditText
    private lateinit var wigleToken: EditText
    private lateinit var wdgWarsKey: EditText
    private lateinit var wardriftUsername: EditText
    private lateinit var wardriftPassword: EditText
    private lateinit var controllerName: EditText
    private lateinit var controllerPassword: EditText
    private lateinit var prefs: android.content.SharedPreferences

    private val seenBssids = LinkedHashSet<String>()
    private val seenBleMacs = LinkedHashSet<String>()
    private val seenCellKeys = LinkedHashSet<String>()
    private val excludedMacs = LinkedHashSet<String>()

    private data class NodeState(
        val slot: Int,
        var nodeId: String,
        var online: Boolean = false,
        var lastHeartbeat: String = "",
        var wifiMac: String = "",
        var bleMac: String = ""
    )

    private val displayedNodeStates =
        Array(10) { slot ->
            NodeState(
                slot = slot + 1,
                nodeId = "DPS-%02d".format(slot + 1)
            )
        }

    private val latestNodeStates =
        Array(10) { slot ->
            NodeState(
                slot = slot + 1,
                nodeId = "DPS-%02d".format(slot + 1)
            )
        }

    private val nodeMissStreak = HashMap<Int, Int>()

    private var nodeStatusUpdateCount = 0

    private var showingNodesPage = false

    private val NODE_SLOT_COUNT = 10
    private val NODE_OFFLINE_DEBOUNCE = 30

    private val CELL_TYPES =
        setOf("GSM", "WCDMA", "LTE", "NR", "CDMA")

    private val recent = ArrayDeque<String>()
    private val logs = ArrayDeque<String>()
    private val observationMarkers = ArrayDeque<Marker>()

    private data class CaptureRecord(
        val type: String,
        val mac: String,
        val name: String,
        val authMode: String,
        val channel: String,
        val frequency: String,
        val rssi: String,
        val firstSeenUtc: String,
        val latitude: String,
        val longitude: String,
        val altitude: String,
        val accuracy: String,
        val rcois: String,
        val mfgrId: String
    )

    private data class SavedCsv(
        val fileName: String,
        val data: ByteArray
    )

    private val captureRecords = ArrayList<CaptureRecord>()
    private val trackPoints = ArrayList<GeoPoint>()
    private var trackLine: Polyline? = null

    private var scanning = false
    private lateinit var captureButton: Button
    private var observations = 0
    private var onlineNodes = 0

    private val receivedNodeStatuses = mutableSetOf<Int>()
    private var totalNodes = NODE_SLOT_COUNT
    private var lastLocation: Location? = null
    private var currentPositionMarker: Marker? = null

    private val dateFormat =
        SimpleDateFormat("HH:mm:ss", Locale.US)

    private val exportDateFormat =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.US
        ).apply {
            timeZone =
                java.util.TimeZone.getTimeZone("UTC")
        }

    private lateinit var locationManager: LocationManager
    private lateinit var telephonyManager: TelephonyManager

    private val cellScanHandler =
        Handler(Looper.getMainLooper())

    private var cellScanRunnable: Runnable? = null

    private val cellScanIntervalMs = 4000L

    private lateinit var fileLogger: FileLogger

    private val locationListener =
        object : LocationListener {

            override fun onLocationChanged(
                location: Location
            ) {
                lastLocation = location

                updateGpsUi(location)
                updatePositionMarker(location)
                updateTravelPath(location)
            }

            override fun onProviderEnabled(
                provider: String
            ) {
            }

            override fun onProviderDisabled(
                provider: String
            ) {
            }
        }

    private val cBg by lazy {
        Color.rgb(5, 7, 10)
    }

    private val cPanel by lazy {
        Color.rgb(12, 19, 27)
    }

    private val cPanelSoft by lazy {
        Color.rgb(10, 16, 22)
    }

    private val cLine by lazy {
        Color.rgb(27, 39, 50)
    }

    private val cLineSoft by lazy {
        Color.rgb(19, 28, 36)
    }

    private val cGreen by lazy {
        color("#39FFB0")
    }

    private val cGreenDim by lazy {
        color("#1A6B52")
    }

    private val cBlue by lazy {
        color("#5EA3FF")
    }

    private val cBlueDim by lazy {
        color("#2A4A75")
    }

    private val cPurple by lazy {
        color("#A36BFF")
    }

    private val cPurpleDim by lazy {
        color("#5B3F94")
    }

    private val cRed by lazy {
        color("#FF4D5E")
    }

    private val cRedDim by lazy {
        color("#5A1F27")
    }

    private val cText0 by lazy {
        color("#EAF2F0")
    }

    private val cText1 by lazy {
        color("#8FA3A8")
    }

    private val cText2 by lazy {
        color("#546570")
    }
    private data class NodeCardViews(
        val card: LinearLayout,
        val statusDot: TextView,
        val slotText: TextView,
        val nodeIdText: TextView,
        val statusText: TextView,
        val connectionText: TextView,
        val heartbeatText: TextView
    )

    private val nodeCardViews =
        arrayOfNulls<NodeCardViews>(
            NODE_SLOT_COUNT
        )

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.statusBarColor =
            Color.rgb(5, 7, 10)

        window.navigationBarColor =
            Color.rgb(5, 7, 10)

        Configuration.getInstance()
            .userAgentValue =
            packageName

        locationManager =
            getSystemService(
                Context.LOCATION_SERVICE
            ) as LocationManager

        telephonyManager =
            getSystemService(
                Context.TELEPHONY_SERVICE
            ) as TelephonyManager

        prefs =
            getSharedPreferences(
                "dps_prefs",
                Context.MODE_PRIVATE
            )

        fileLogger =
            FileLogger(this)

        fileLogger.write(
            "=== APP START (DPS Field Controller) ==="
        )

        buildDashboard()

        ble =
            BleController(
                this,
                this
            )

        requestPermissionsIfNeeded()
    }

    private fun buildDashboard() {
        root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(cBg)

                setPadding(
                    dp(10),
                    dp(8),
                    dp(10),
                    dp(8)
                )
            }

        ViewCompat.setOnApplyWindowInsetsListener(
            root
        ) { view, insets ->

            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )

            view.setPadding(
                dp(10) + bars.left,
                dp(8) + bars.top,
                dp(10) + bars.right,
                dp(8) + bars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(root)

        windowRoot =
            FrameLayout(this)

        windowRoot.setBackgroundColor(cBg)

        windowRoot.addView(
            root,
            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )

        setContentView(windowRoot)

        mapView =
            MapView(this).apply {
                setTileSource(
                    TileSourceFactory.MAPNIK
                )

                setMultiTouchControls(true)

                controller.setZoom(16.0)

                setBackgroundColor(
                    Color.rgb(6, 12, 16)
                )
            }

        root.addView(header())
        root.addView(statusStrip())

        content =
            FrameLayout(this)

        root.addView(
            content,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        showDashboardPage()
        buildStealthOverlay()
    }

    private fun buildStealthOverlay() {
        stealthOverlay =
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)

                isClickable = true
                isFocusable = true

                setOnClickListener {
                    exitStealthMode()
                }

                visibility =
                    View.GONE
            }

        val counters =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER
            }

        val mutedWhite =
            color("#B8C0C2")

        stealthWifiText =
            stealthCounterText(mutedWhite)

        stealthBleText =
            stealthCounterText(mutedWhite)

        stealthCellText =
            stealthCounterText(mutedWhite)

        counters.addView(
            stealthWifiText
        )

        counters.addView(
            stealthBleText
        )

        counters.addView(
            stealthCellText
        )

        stealthOverlay.addView(
            counters,
            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )

        windowRoot.addView(
            stealthOverlay,
            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )
    }

    private fun pref(key: String): String =
        prefs.getString(key, "")?.trim().orEmpty()

    private fun signInToWardrift() {
        val username = pref("wardrift_username")
        val password = pref("wardrift_password")

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(
                this,
                "Enter your Wardrift username and password first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Thread {
            try {
                val token =
                    signInAndGetWardriftToken(
                        username,
                        password
                    )

                prefs.edit()
                    .putString(
                        "wardrift_session_token",
                        token
                    )
                    .apply()

                runOnUiThread {
                    addLog("Wardrift sign-in successful; session token saved")
                    Toast.makeText(
                        this,
                        "Signed in to Wardrift",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                prefs.edit()
                    .remove("wardrift_session_token")
                    .apply()

                runOnUiThread {
                    addLog(
                        "Wardrift sign-in failed: ${e.message ?: "unknown error"}"
                    )
                    Toast.makeText(
                        this,
                        "Wardrift sign-in failed — see Monitor log",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }


    private fun stealthCounterText(
        textColor: Int
    ): TextView {
        return TextView(this).apply {
            textSize = 24f

            setTextColor(textColor)

            typeface =
                Typeface.MONOSPACE

            gravity =
                Gravity.CENTER

            includeFontPadding = false

            setPadding(
                0,
                dp(6),
                0,
                dp(6)
            )
        }
    }

    private fun updateStealthCounters() {
        if (!::stealthWifiText.isInitialized) {
            return
        }

        stealthWifiText.text =
            "W: ${seenBssids.size}"

        stealthBleText.text =
            "B: ${seenBleMacs.size}"

        stealthCellText.text =
            "C: ${seenCellKeys.size}"
    }

    private fun enterStealthMode() {
        updateStealthCounters()

        stealthOverlay.visibility =
            View.VISIBLE

        window.statusBarColor =
            Color.BLACK

        window.navigationBarColor =
            Color.BLACK
    }

    private fun exitStealthMode() {
        stealthOverlay.visibility =
            View.GONE

        window.statusBarColor =
            cBg

        window.navigationBarColor =
            cBg
    }

    private fun header(): View {
        val box =
            panel(cGreenDim).apply {
                setPadding(
                    dp(12),
                    0,
                    dp(12),
                    0
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        -1,
                        dp(88)
                    ).apply {
                        setMargins(
                            0,
                            dp(4),
                            0,
                            dp(6)
                        )
                    }
            }

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        -1,
                        dp(80)
                    )
            }

        val emblemFrame =
            FrameLayout(this).apply {
                background =
                    ovalBg(
                        cGreen,
                        Color.rgb(9, 6, 16)
                    )
            }

        val emblem =
            ImageView(this).apply {
                setImageResource(
                    R.drawable.dps_emblem
                )

                scaleType =
                    ImageView.ScaleType.FIT_CENTER

                contentDescription =
                    "Dead Packet Society"
            }

        emblemFrame.addView(
            emblem,
            FrameLayout.LayoutParams(
                dp(60),
                dp(60)
            ).apply {
                gravity =
                    Gravity.CENTER
            }
        )

        val titles =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_VERTICAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        -1,
                        1f
                    ).apply {
                        setMargins(
                            dp(12),
                            0,
                            dp(6),
                            0
                        )
                    }
            }

        val title =
            tv(
                "DEAD PACKET SOCIETY",
                16,
                cText0
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                letterSpacing = 0.05f

                setPadding(
                    0,
                    0,
                    0,
                    dp(1)
                )
            }

        titles.addView(title)

        val subtitle =
            tv(
                "NIGHTSHADE'S DPS FIELD CONTROLLER",
                10,
                cGreen
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                letterSpacing = 0.12f

                setPadding(
                    0,
                    0,
                    0,
                    dp(3)
                )
            }

        titles.addView(subtitle)

        connectionText =
            tv(
                "SYSTEM OFFLINE",
                12,
                cRed
            ).apply {
                typeface =
                    Typeface.MONOSPACE

                letterSpacing = 0.03f

                setPadding(
                    0,
                    0,
                    0,
                    0
                )
            }

        titles.addView(connectionText)

        val disconnect =
            button(
                "DISCONNECT",
                cRed
            ) {
                ble.disconnect()
            }.apply {
                textSize = 11f
            }

        row.addView(
            emblemFrame,
            LinearLayout.LayoutParams(
                dp(72),
                dp(72)
            )
        )

        row.addView(titles)

        row.addView(
            disconnect,
            LinearLayout.LayoutParams(
                dp(118),
                dp(44)
            ).apply {
                setMargins(
                    0,
                    0,
                    dp(8),
                    0
                )

                disconnect.visibility =
                    View.GONE
            }
        )

        box.addView(row)

        return box
    }

    private fun statusStrip(): View {
        val grid =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        val row1 =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        captureText =
            stat(
                "CAPTURE",
                "IDLE",
                cText2
            )

        gpsText =
            stat(
                "GPS FIX",
                "WAITING",
                cGreen
            )

        row1.addView(
            captureText,
            gridCellParams()
        )

        row1.addView(
            gpsText,
            gridCellParams()
        )

        val row2 =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        nodeText =
            stat(
                "NODES ONLINE",
                "0 / 10",
                cGreen
            )

        observationText =
            stat(
                "OBSERVATIONS",
                "0",
                cGreen
            )

        row2.addView(
            nodeText,
            gridCellParams()
        )

        row2.addView(
            observationText,
            gridCellParams()
        )

        grid.addView(
            row1,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        grid.addView(
            row2,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                setMargins(
                    0,
                    dp(6),
                    0,
                    0
                )
            }
        )

        return grid
    }

    private fun showDashboardPage() {
        showingNodesPage = false

        content.removeAllViews()

        val scroll =
            ScrollView(this)

        val page =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        scroll.addView(page)

        val controls =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        captureButton =
            button(
                if (scanning) {
                    "■  STOP"
                } else {
                    "▶  START"
                },
                if (scanning) {
                    cRed
                } else {
                    cGreen
                }
            ) {
                if (scanning) {
                    stopCapture()
                    scanning = false
                } else {
                    startCapture()
                    scanning = true
                }
            }

        if (scanning) {
            captureButton.background =
                pillButtonBg(
                    cRed,
                    false
                )
        }

        controls.addView(
            captureButton,
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            ).apply {
                setMargins(
                    dp(3),
                    dp(3),
                    dp(3),
                    dp(3)
                )
            }
        )

        page.addView(controls)

        val secondControls =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        secondControls.addView(
            button(
                "MONITOR",
                cGreen
            ) {
                showCommunicationPage()
            },
            weightParams()
        )

        secondControls.addView(
            button(
                "NODES",
                cGreen
            ) {
                showNodesPage()
            },
            weightParams()
        )

        secondControls.addView(
            button(
                "UPLOADS",
                cGreen
            ) {
                showUploadsPage()
            },
            weightParams()
        )

        secondControls.addView(
            button(
                "⚙ Settings",
                cGreen
            ) {
                showConfigPage()
            },
            weightParams()
        )

        page.addView(secondControls)



        page.addView(
            button(
                "STEALTH MODE",
                cText1
            ) {
                enterStealthMode()
            },
            LinearLayout.LayoutParams(
                -1,
                dp(48)
            ).apply {
                setMargins(
                    dp(3),
                    dp(3),
                    dp(3),
                    dp(3)
                )
            }
        )

        val saveRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER
            }

        saveRow.addView(
            button(
                "UPLOAD ALL",
                cPurple,
                dashed = false
            ) {
                uploadAll()
            },
            LinearLayout.LayoutParams(
                0,
                dp(48)
            ).apply {
                weight = 1f
                setMargins(
                    dp(3),
                    dp(6),
                    dp(3),
                    dp(3)
                )
            }
        )

        saveRow.addView(
            button(
                "↓  SAVE SESSION",
                cBlue,
                dashed = false
            ) {
                saveWigleCsvToDownloads()
            },
            LinearLayout.LayoutParams(
                0,
                dp(48)
            ).apply {
                weight = 1f
                setMargins(
                    dp(3),
                    dp(6),
                    dp(3),
                    dp(3)
                )
            }
        )

        page.addView(saveRow)

        val recentHeaderRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(8),
                    dp(14),
                    dp(8),
                    dp(6)
                )
            }

        recentHeaderRow.addView(
            tv(
                "RECENT OBSERVATIONS",
                13,
                cText0
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                letterSpacing = 0.06f

                setPadding(
                    0,
                    0,
                    0,
                    0
                )
            },
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        recentHeaderRow.addView(
            tv(
                "LIVE FEED",
                10,
                cText2
            ).apply {
                typeface =
                    Typeface.MONOSPACE

                letterSpacing = 0.05f
            }
        )

        page.addView(recentHeaderRow)

        recentContainer =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                background =
                    cardBg(
                        cLine,
                        cPanel,
                        dp(4).toFloat(),
                        dp(1)
                    )
            }

        recentText =
            tv(
                "No observations yet.",
                11,
                cText1
            ).apply {
                includeFontPadding = false

                setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
                )
            }

        recentContainer.addView(
            recentText
        )

        page.addView(
            recentContainer,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                setMargins(
                    dp(3),
                    0,
                    dp(3),
                    dp(6)
                )
            }
        )

        renderRecentObservations()

        content.addView(scroll)

        startMap()
    }

    private fun startCapture() {
        if (scanning) {
            return
        }

        seenBssids.clear()
        seenBleMacs.clear()
        seenCellKeys.clear()
        recent.clear()

        renderRecentObservations()

        captureRecords.clear()
        observations = 0

        updateObservationUi()

        clearTravelPath()

        scanning = true

        captureButton.text =
            "■  STOP"

        captureButton.setTextColor(
            cRed
        )

        captureButton.background =
            pillButtonBg(
                cRed,
                false
            )

        captureText.text =
            "CAPTURE\nCAPTURING"

        captureText.setTextColor(
            cGreen
        )

        ble.send("START_DEF")

        addLog(
            "APP -> CONTROLLER: START_DEF"
        )

        startCellScanning()
    }

    private fun stopCapture() {
        if (!scanning) {
            return
        }

        scanning = false

        captureButton.text =
            "▶  START"

        captureButton.setTextColor(
            cGreen
        )

        captureButton.background =
            pillButtonBg(
                cGreen,
                false
            )

        captureText.text =
            "CAPTURE\nIDLE"

        captureText.setTextColor(
            cText1
        )

        ble.send("STOP")

        addLog(
            "APP -> CONTROLLER: STOP"
        )

        stopCellScanning()
    }

    private fun nodeCard(
        id: Int,
        online: Boolean
    ): View {
        val box =
            panel(cLine)

        val status =
            if (online) {
                "ONLINE"
            } else {
                "OFFLINE"
            }

        val statusColor =
            if (online) {
                cGreen
            } else {
                cRed
            }

        box.addView(
            tv(
                "DPS-%02d   •   %s"
                    .format(id, status),
                14,
                statusColor
            )
        )

        box.addView(
            tv(
                "Scanner node $id",
                11,
                cText1
            )
        )

        return box
    }

    private fun showCommunicationPage() {
        showingNodesPage = false

        content.removeAllViews()

        val page =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        page.addView(
            button(
                "‹  BACK TO DASHBOARD",
                cGreen
            ) {
                showDashboardPage()
            }
        )

        page.addView(
            tv(
                "COMMUNICATION MONITOR",
                18,
                cText0
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                letterSpacing = 0.04f
            }
        )

        val logScroll =
            ScrollView(this)

        val logText =
            tv(
                logs.joinToString("\n")
                    .ifBlank {
                        "Waiting for BLE traffic…"
                    },
                12,
                cText1
            ).apply {
                typeface =
                    Typeface.MONOSPACE
            }

        logText.setPadding(
            dp(12),
            dp(12),
            dp(12),
            dp(12)
        )

        logText.setHorizontallyScrolling(false)

        logScroll.addView(logText)

        logScroll.background =
            cardBg(
                cLine,
                cPanel,
                dp(4).toFloat(),
                dp(1)
            )

        page.addView(
            logScroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            ).apply {
                setMargins(
                    dp(3),
                    dp(6),
                    dp(3),
                    dp(6)
                )
            }
        )

        page.addView(
            tv(
                "Debug log file: ${fileLogger.currentFileName()}",
                10,
                cText2
            ).apply {
                typeface =
                    Typeface.MONOSPACE

                setPadding(
                    dp(6),
                    0,
                    dp(6),
                    dp(4)
                )
            }
        )

        val buttons =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        buttons.addView(
            button(
                "COPY ALL",
                cGreen
            ) {
                copyLogs()
            },
            weightParams()
        )

        buttons.addView(
            button(
                "CLEAR",
                cRed
            ) {
                logs.clear()
                logText.text = ""
            },
            weightParams()
        )

        page.addView(buttons)

        val exportButtons =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        exportButtons.addView(
            button(
                "↓  EXPORT DEBUG LOG",
                cPurple
            ) {
                exportDebugLogToDownloads()
            },
            weightParams()
        )

        page.addView(exportButtons)

        content.addView(page)
    }

    private fun showConfigPage() {
        showingNodesPage = false

        content.removeAllViews()

        val scroll =
            ScrollView(this)

        val page =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        scroll.addView(page)

        page.addView(
            button(
                "‹  BACK TO DASHBOARD",
                cGreen
            ) {
                showDashboardPage()
            }
        )

        page.addView(
            tv(
                "CONFIGURATION",
                18,
                cText0
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                letterSpacing = 0.04f
            }
        )

        val controllerCard =
            panel(cGreenDim)

        controllerCard.addView(
            sectionLabel(
                "CONTROLLER SETTINGS",
                cGreen
            )
        )

        controllerName =
            edit("Controller Name").apply {
                persist("controller_name")
            }

        controllerPassword =
            edit("Controller Password").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD

                persist("controller_password")
            }

        controllerCard.addView(
            controllerName
        )

        controllerCard.addView(
            controllerPassword
        )

        page.addView(controllerCard)

        val connectionCard =
            panel(cBlueDim)

        connectionCard.addView(
            sectionLabel(
                "CONTROLLER CONNECTION",
                cBlue
            )
        )

        connectionCard.addView(
            button(
                "CONNECT / RECONNECT",
                cBlue
            ) {
                ble.connectBondedOrScan()
            }
        )

        connectionCard.addView(
            tv(
                "BLE TARGET: WD-Hub",
                10,
                cText1
            )
        )

        page.addView(connectionCard)

        content.addView(scroll)
    }

    private fun showUploadsPage() {
        showingNodesPage = false

        content.removeAllViews()

        val scroll =
            ScrollView(this)

        val page =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        scroll.addView(page)

        page.addView(
            button(
                "‹  BACK TO DASHBOARD",
                cGreen
            ) {
                showDashboardPage()
            }
        )

        page.addView(
            tv(
                "UPLOADS",
                18,
                cText0
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                letterSpacing = 0.04f
            }
        )

        val wigleCard =
            panel(cGreenDim)

        wigleCard.addView(
            sectionLabel(
                "WIGLE UPLOAD",
                cGreen
            )
        )

        wigleName =
            edit("WiGLE API Name").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD

                persist("wigle_name")
            }

        wigleToken =
            edit("WiGLE API Token").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD

                persist("wigle_token")
            }

        wigleCard.addView(wigleName)
        wigleCard.addView(wigleToken)

        wigleCard.addView(
            button(
                "UPLOAD TO WiGLE",
                cGreen
            ) {
                uploadToWigle()
            }
        )

        page.addView(wigleCard)

        val wdgCard =
            panel(cPurpleDim)

        wdgCard.addView(
            sectionLabel(
                "WDGWARS.PL UPLOAD",
                cPurple
            )
        )

        wdgWarsKey =
            edit("WDGWars.pl API Key").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD

                persist("wdgwars_key")
            }

        wdgCard.addView(wdgWarsKey)

        wdgCard.addView(
            button(
                "UPLOAD TO WDGWARS",
                cPurple
            ) {
                uploadToWdgWars()
            }
        )

        page.addView(wdgCard)

        val wardriftCard =
            panel(cBlueDim)

        wardriftCard.addView(
            sectionLabel(
                "WARDRIFT UPLOAD",
                cBlue
            )
        )

        wardriftUsername =
            edit("Wardrift Username").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT

                persist("wardrift_username")

                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                        ) {
                        }

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                        ) {
                            prefs.edit()
                                .remove("wardrift_session_token")
                                .apply()
                        }

                        override fun afterTextChanged(
                            s: android.text.Editable?
                        ) {
                        }
                    }
                )
            }

        wardriftPassword =
            edit("Wardrift Password").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD

                persist("wardrift_password")

                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                        ) {
                        }

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                        ) {
                            prefs.edit()
                                .remove("wardrift_session_token")
                                .apply()
                        }

                        override fun afterTextChanged(
                            s: android.text.Editable?
                        ) {
                        }
                    }
                )
            }

        wardriftCard.addView(wardriftUsername)
        wardriftCard.addView(wardriftPassword)

        wardriftCard.addView(
            button(
                "UPLOAD TO WARDRIFT",
                cBlue
            ) {
                uploadToWardrift()
            }
        )

        page.addView(wardriftCard)



        content.addView(scroll)
    }

    override fun connected(
        name: String
    ) {
        runOnUiThread {
            connectionText.text =
                "SYSTEM CONNECTED • $name"

            connectionText.setTextColor(
                cGreen
            )

            addLog(
                "BLE: CONNECTED TO $name"
            )
        }
    }

    override fun disconnected() {
        runOnUiThread {
            connectionText.text =
                "BLE OFFLINE"

            connectionText.setTextColor(
                cRed
            )

            scanning = false

            if (::captureButton.isInitialized) {
                captureButton.text =
                    "▶  START"

                captureButton.setTextColor(
                    cGreen
                )
            }

            captureText.text =
                "CAPTURE\nIDLE"

            captureText.setTextColor(
                cText1
            )

            stopCellScanning()


            nodeStatusUpdateCount = 0

            addLog(
                "BLE: DISCONNECTED"
            )
        }
    }

    override fun status(
        text: String
    ) {
        runOnUiThread {
            parseCONTROLLER(text)
        }
    }

    override fun log(
        text: String
    ) {
        runOnUiThread {
            addLog(text)
        }
    }

    private fun normalizeMac(
        value: String
    ): String =
        value.trim()
            .uppercase(Locale.US)

    private fun addExcludedMac(
        value: String
    ) {
        val mac =
            normalizeMac(value)

        if (mac.isNotEmpty()) {
            excludedMacs.add(mac)
        }
    }

    private fun collectMacField(
        json: JSONObject,
        vararg keys: String
    ) {
        for (key in keys) {
            if (json.has(key)) {
                addExcludedMac(
                    json.optString(
                        key,
                        ""
                    )
                )
            }
        }
    }

    private fun parseCONTROLLER(
        text: String
    ) {
        addLog(
            "CONTROLLER -> APP: $text"
        )

        try {
            val json =
                JSONObject(text)

            when (
                json.optString("type")
            ) {
                "wifi" ->
                    processObservation(
                        json,
                        "WIFI"
                    )

                "ble" ->
                    processObservation(
                        json,
                        "BLE"
                    )

                "status" ->
                    processStatus(json)
            }
        } catch (_: Exception) {
        }
    }

    private fun processStatus(
        json: JSONObject
    ) {
        collectMacField(
            json,
            "wifiMac",
            "wifi_mac",
            "controllerWifiMac",
            "controller_wifi_mac",
            "bleMac",
            "ble_mac",
            "controllerBleMac",
            "controller_ble_mac",
            "mac",
            "ble"
        )

        val arr =
            json.optJSONArray("nodes")

        if (arr != null) {

            val seenSlots =
                BooleanArray(
                    NODE_SLOT_COUNT
                )

            for (i in 0 until arr.length()) {
                val node =
                    arr.optJSONObject(i)
                        ?: continue

                val slot =
                    node.optInt(
                        "slot",
                        -1
                    )

                if (
                    slot < 1 ||
                    slot > NODE_SLOT_COUNT
                ) {
                    continue
                }

                nodeStatusUpdateCount =
                    nodeStatusUpdateCount or
                            (1 shl (slot - 1))

                val index =
                    slot - 1

                seenSlots[index] = true

                val controllerNodeId =
                    node.optString(
                        "id",
                        node.optString(
                            "node",
                            node.optString(
                                "node_id",
                                "DPS-%02d"
                                    .format(slot)
                            )
                        )
                    ).ifBlank {
                        "DPS-%02d"
                            .format(slot)
                    }

                val rawOnline =
                    node.optBoolean(
                        "online",
                        false
                    )

                val previousDisplayed =
                    displayedNodeStates[index].online

                if (rawOnline) {
                    nodeMissStreak[slot] = 0

                    if (!previousDisplayed) {
                        if (
                            displayedNodeStates[index]
                                .nodeId !=
                            controllerNodeId
                        ) {
                            fileLogger.write(
                                "NODE SLOT $slot IDENTIFIED AS $controllerNodeId"
                            )
                        }
                    }
                } else if (previousDisplayed) {

                    val misses =
                        (
                                nodeMissStreak[slot]
                                    ?: 0
                                ) + 1

                    nodeMissStreak[slot] =
                        misses

                    if (
                        misses <
                        NODE_OFFLINE_DEBOUNCE
                    ) {
                        fileLogger.write(
                            "NODE $controllerNodeId DROPPED " +
                                    "(slot $slot, offline for $misses consecutive status updates)"
                        )

                        addLog(
                            "NODE $controllerNodeId DROPPED"
                        )
                    } else {
                        fileLogger.write(
                            "NODE $controllerNodeId heartbeat miss " +
                                    "$misses/$NODE_OFFLINE_DEBOUNCE " +
                                    "(still shown online)"
                        )
                    }
                } else {
                    nodeMissStreak[slot] = 0
                }

                val displayedOnline =
                    if (rawOnline) {
                        true
                    } else if (
                        previousDisplayed &&
                        (
                                nodeMissStreak[slot]
                                    ?: 0
                                ) <
                        NODE_OFFLINE_DEBOUNCE
                    ) {
                        true
                    } else {
                        false
                    }

                val latest =
                    latestNodeStates[index]

                latest.nodeId =
                    controllerNodeId

                latest.online =
                    displayedOnline

                latest.lastHeartbeat =
                    node.optString(
                        "last_heartbeat",
                        ""
                    )

                latest.wifiMac =
                    normalizeMac(
                        node.optString(
                            "wifi_mac",
                            node.optString(
                                "wifiMac",
                                node.optString(
                                    "nodeWifiMac",
                                    ""
                                )
                            )
                        )
                    )

                latest.bleMac =
                    normalizeMac(
                        node.optString(
                            "ble_mac",
                            node.optString(
                                "bleMac",
                                node.optString(
                                    "nodeBleMac",
                                    ""
                                )
                            )
                        )
                    )

                collectMacField(
                    node,
                    "wifiMac",
                    "wifi_mac",
                    "nodeWifiMac",
                    "bleMac",
                    "ble_mac",
                    "nodeBleMac",
                    "mac",
                    "ble"
                )
            }

            for (
            index in 0 until NODE_SLOT_COUNT
            ) {
                if (!seenSlots[index]) {
                    val slot =
                        index + 1

                    val previousDisplayed =
                        displayedNodeStates[index].online

                    if (previousDisplayed) {
                        val misses =
                            (
                                    nodeMissStreak[slot]
                                        ?: 0
                                    ) + 1

                        nodeMissStreak[slot] =
                            misses

                        latestNodeStates[index].online =
                            misses <
                                    NODE_OFFLINE_DEBOUNCE
                    } else {
                        nodeMissStreak[slot] = 0

                        latestNodeStates[index].online =
                            false
                    }

                    latestNodeStates[index].nodeId =
                        displayedNodeStates[index]
                            .nodeId

                    latestNodeStates[index]
                        .lastHeartbeat =
                        displayedNodeStates[index]
                            .lastHeartbeat

                    latestNodeStates[index]
                        .wifiMac =
                        displayedNodeStates[index]
                            .wifiMac

                    latestNodeStates[index]
                        .bleMac =
                        displayedNodeStates[index]
                            .bleMac
                }
            }
        }

        val allNodeStatusesReceived =
            (1 shl NODE_SLOT_COUNT) - 1

        if (
            nodeStatusUpdateCount ==
            allNodeStatusesReceived
        ) {
            reconcileDisplayedNodes()

            nodeStatusUpdateCount = 0
        }
    }

    private fun reconcileDisplayedNodes() {

        for (
        index in 0 until NODE_SLOT_COUNT
        ) {
            val displayed =
                displayedNodeStates[index]

            val latest =
                latestNodeStates[index]

            displayed.nodeId =
                latest.nodeId

            displayed.online =
                latest.online

            displayed.lastHeartbeat =
                latest.lastHeartbeat

            displayed.wifiMac =
                latest.wifiMac

            displayed.bleMac =
                latest.bleMac
        }

        onlineNodes =
            displayedNodeStates.count {
                it.online
            }

        totalNodes =
            NODE_SLOT_COUNT

        if (
            ::nodeText.isInitialized
        ) {
            nodeText.text =
                "NODES ONLINE\n$onlineNodes / $totalNodes"
        }

        if (showingNodesPage) {
            updateNodesPage()
        }
    }

    private fun processObservation(
        json: JSONObject,
        type: String
    ) {
        if (scanning) {
            val now = Date()

            if (type == "WIFI") {

                val bssid =
                    normalizeMac(
                        json.optString(
                            "bssid",
                            ""
                        )
                    )

                if (
                    bssid.isNotEmpty() &&
                    excludedMacs.contains(bssid)
                ) {
                    addLog(
                        "FILTERED DPS WIFI MAC: $bssid"
                    )

                    return
                }

                if (
                    bssid.isNotEmpty() &&
                    !seenBssids.add(bssid)
                ) {
                    return
                }

            } else if (type == "BLE") {

                val mac =
                    normalizeMac(
                        json.optString(
                            "mac",
                            ""
                        )
                    )

                if (
                    mac.isNotEmpty() &&
                    excludedMacs.contains(mac)
                ) {
                    addLog(
                        "FILTERED DPS BLE MAC: $mac"
                    )

                    return
                }

                if (
                    mac.isNotEmpty() &&
                    !seenBleMacs.add(mac)
                ) {
                    return
                }
            }

            observations++

            updateObservationUi()

            val name =
                if (type == "BLE") {
                    json.optString(
                        "name",
                        json.optString(
                            "ssid",
                            json.optString(
                                "mac",
                                "Unknown"
                            )
                        )
                    )
                } else {
                    json.optString(
                        "ssid",
                        "Unknown"
                    )
                }

            val rssi =
                json.optInt(
                    "rssi",
                    0
                )

            val node =
                json.optInt(
                    "node",
                    0
                )

            recent.addLast(
                "${dateFormat.format(now)}|$type|$name|$rssi|$node"
            )

            while (recent.size > 20) {
                recent.removeFirst()
            }

            renderRecentObservations()

            if (scanning) {
                captureRecords.add(
                    makeCaptureRecord(
                        json,
                        type,
                        now
                    )
                )
            }

            addObservationMarker(
                type,
                name,
                rssi
            )
        }
    }

    private fun renderRecentObservations() {
        if (!::recentContainer.isInitialized) {
            return
        }

        recentContainer.removeAllViews()

        if (recent.isEmpty()) {

            recentText =
                tv(
                    "No observations yet.",
                    11,
                    cText1
                ).apply {
                    includeFontPadding =
                        false

                    setPadding(
                        dp(12),
                        dp(12),
                        dp(12),
                        dp(12)
                    )
                }

            recentContainer.addView(
                recentText
            )

            return
        }

        recent.forEachIndexed {
                index,
                entry ->

            val parts =
                entry.split(
                    "|",
                    limit = 5
                )

            if (parts.size < 5) {
                return@forEachIndexed
            }

            val time =
                parts[0]

            val type =
                parts[1]

            val name =
                parts[2]

            val rssi =
                parts[3]

            val node =
                parts[4]

            val isCell =
                type in CELL_TYPES

            val accent =
                when {
                    type == "BLE" ->
                        cBlue

                    isCell ->
                        cPurple

                    else ->
                        cGreen
                }

            val accentDim =
                when {
                    type == "BLE" ->
                        cBlueDim

                    isCell ->
                        cPurpleDim

                    else ->
                        cGreenDim
                }

            val row =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL

                    gravity =
                        Gravity.CENTER_VERTICAL

                    setPadding(
                        0,
                        dp(10),
                        dp(10),
                        dp(10)
                    )
                }

            row.addView(
                View(this).apply {
                    setBackgroundColor(
                        accent
                    )
                },
                LinearLayout.LayoutParams(
                    dp(2),
                    -1
                ).apply {
                    setMargins(
                        0,
                        0,
                        dp(10),
                        0
                    )
                }
            )

            val icon =
                TextView(this).apply {
                    text =
                        when {
                            type == "BLE" ->
                                "ᛒ"

                            isCell ->
                                "▲"

                            else ->
                                "⌁"
                        }

                    textSize = 15f

                    gravity =
                        Gravity.CENTER

                    setTextColor(accent)

                    background =
                        ovalBg(
                            accentDim,
                            Color.rgb(
                                9,
                                14,
                                19
                            )
                        )
                }

            row.addView(
                icon,
                LinearLayout.LayoutParams(
                    dp(32),
                    dp(32)
                ).apply {
                    setMargins(
                        0,
                        0,
                        dp(10),
                        0
                    )
                }
            )

            val details =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.VERTICAL

                    gravity =
                        Gravity.CENTER_VERTICAL
                }

            details.addView(
                tv(
                    type,
                    10,
                    accent
                ).apply {
                    typeface =
                        Typeface.create(
                            "sans-serif-condensed",
                            Typeface.BOLD
                        )

                    letterSpacing = 0.1f

                    includeFontPadding =
                        false

                    setPadding(
                        0,
                        0,
                        0,
                        dp(2)
                    )
                }
            )

            details.addView(
                tv(
                    name,
                    12,
                    cText0
                ).apply {
                    typeface =
                        Typeface.MONOSPACE

                    includeFontPadding =
                        false

                    maxLines = 1

                    ellipsize =
                        android.text.TextUtils.TruncateAt.END

                    setPadding(
                        0,
                        0,
                        0,
                        dp(2)
                    )
                }
            )

            val meta =
                if (isCell) {
                    "RSSI $rssi dBm  •  Device modem"
                } else {
                    "RSSI $rssi dBm  •  NODE $node"
                }

            details.addView(
                tv(
                    meta,
                    10,
                    cText2
                ).apply {
                    includeFontPadding =
                        false

                    setPadding(
                        0,
                        0,
                        0,
                        0
                    )
                }
            )

            row.addView(
                details,
                LinearLayout.LayoutParams(
                    0,
                    -2,
                    1f
                )
            )

            row.addView(
                tv(
                    time,
                    10,
                    cText2
                ).apply {
                    typeface =
                        Typeface.MONOSPACE

                    gravity =
                        Gravity.TOP or
                                Gravity.END

                    includeFontPadding =
                        false

                    setPadding(
                        dp(4),
                        dp(2),
                        0,
                        0
                    )
                },
                LinearLayout.LayoutParams(
                    dp(58),
                    -1
                )
            )

            recentContainer.addView(row)

            if (
                index <
                recent.size - 1
            ) {
                recentContainer.addView(
                    View(this).apply {
                        setBackgroundColor(
                            cLineSoft
                        )
                    },
                    LinearLayout.LayoutParams(
                        -1,
                        dp(1)
                    ).apply {
                        setMargins(
                            dp(24),
                            0,
                            dp(4),
                            0
                        )
                    }
                )
            }
        }
    }

    private fun showNodesPage() {
        showingNodesPage = true

        content.removeAllViews()

        val scroll =
            ScrollView(this)

        val page =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    0,
                    dp(2),
                    0,
                    dp(4)
                )
            }

        scroll.addView(page)

        page.addView(
            button(
                "‹  BACK TO DASHBOARD",
                cBlue
            ) {
                showDashboardPage()
            }
        )

        page.addView(
            tv(
                "NODES",
                18,
                cText0
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                letterSpacing = 0.04f
            }
        )

        page.addView(
            tv(
                "$onlineNodes / $NODE_SLOT_COUNT ONLINE",
                12,
                if (onlineNodes > 0) {
                    cGreen
                } else {
                    cRed
                }
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                setPadding(
                    dp(8),
                    dp(2),
                    dp(8),
                    dp(6)
                )

                tag = "NODE_ONLINE_COUNT"
            }
        )

        for (
        index in 0 until NODE_SLOT_COUNT
        ) {
            val state =
                displayedNodeStates[index]

            val cardViews =
                nodeDetailCard(state)

            nodeCardViews[index] =
                cardViews

            page.addView(
                cardViews.card
            )
        }

        content.addView(scroll)

        scroll.post {
            val maxScroll =
                (
                        page.height -
                                scroll.height
                        ).coerceAtLeast(0)

            val currentY =
                scroll.scrollY.coerceAtMost(
                    maxScroll
                )

            scroll.scrollTo(
                0,
                currentY
            )
        }
    }
    private fun updateNodesPage() {

        if (!showingNodesPage) {
            return
        }

        for (
        index in 0 until NODE_SLOT_COUNT
        ) {

            val views =
                nodeCardViews[index]
                    ?: continue

            val state =
                displayedNodeStates[index]

            val nodeAccentPalette =
                listOf(
                    cGreen,
                    cBlue,
                    color("#FFB454"),
                    cPurple,
                    color("#FF7AB8"),
                    color("#4DD0E1")
                )

            val accent =
                nodeAccentPalette[
                    (state.slot - 1)
                        .mod(
                            nodeAccentPalette.size
                        )
                ]

            val status =
                if (state.online) {
                    "ONLINE"
                } else {
                    "OFFLINE"
                }

            val statusColor =
                if (state.online) {
                    cGreen
                } else {
                    cRed
                }

            views.statusDot.setTextColor(
                statusColor
            )

            views.slotText.setTextColor(
                accent
            )

            views.nodeIdText.text =
                state.nodeId

            views.statusText.text =
                status

            views.statusText.setTextColor(
                statusColor
            )

            val connectionInfo =
                if (
                    state.wifiMac.isNotBlank() ||
                    state.bleMac.isNotBlank()
                ) {

                    val parts =
                        ArrayList<String>()

                    if (
                        state.wifiMac.isNotBlank()
                    ) {
                        parts.add(
                            "Wi-Fi ${state.wifiMac}"
                        )
                    }

                    if (
                        state.bleMac.isNotBlank()
                    ) {
                        parts.add(
                            "BLE ${state.bleMac}"
                        )
                    }

                    parts.joinToString(
                        "  •  "
                    )

                } else {
                    if (state.online) {
                        "Connected to controller"
                    } else {
                        "Waiting for connection"
                    }
                }

            views.connectionText.text =
                connectionInfo

            views.heartbeatText.text =
                if (
                    state.lastHeartbeat.isNotBlank()
                ) {
                    "LAST HEARTBEAT  ${state.lastHeartbeat}"
                } else {
                    ""
                }
        }

        val onlineHeader =
            content.findViewWithTag<TextView>(
                "NODE_ONLINE_COUNT"
            )

        onlineHeader?.apply {
            text =
                "$onlineNodes / $NODE_SLOT_COUNT ONLINE"

            setTextColor(
                if (onlineNodes > 0) {
                    cGreen
                } else {
                    cRed
                }
            )
        }
    }
    private fun nodeDetailCard(
        state: NodeState
    ): NodeCardViews {

        val nodeAccentPalette =
            listOf(
                cGreen,
                cBlue,
                color("#FFB454"),
                cPurple,
                color("#FF7AB8"),
                color("#4DD0E1")
            )

        val accent =
            nodeAccentPalette[
                (state.slot - 1)
                    .mod(
                        nodeAccentPalette.size
                    )
            ]

        val status =
            if (state.online) {
                "ONLINE"
            } else {
                "OFFLINE"
            }

        val statusColor =
            if (state.online) {
                cGreen
            } else {
                cRed
            }

        val card =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10)
                )

                background =
                    cardBg(
                        cLine,
                        cPanel,
                        dp(4).toFloat(),
                        dp(1)
                    )

                layoutParams =
                    LinearLayout.LayoutParams(
                        -1,
                        -2
                    ).apply {
                        setMargins(
                            dp(3),
                            dp(3),
                            dp(3),
                            dp(3)
                        )
                    }
            }

        val top =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val statusDot =
            tv(
                "●",
                17,
                statusColor
            ).apply {
                gravity =
                    Gravity.CENTER

                setTextColor(
                    statusColor
                )

                setPadding(
                    0,
                    0,
                    dp(6),
                    0
                )
            }

        top.addView(
            statusDot,
            LinearLayout.LayoutParams(
                dp(24),
                dp(28)
            )
        )

        val slotText =
            tv(
                "SLOT ${state.slot}",
                10,
                accent
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                includeFontPadding =
                    false

                setPadding(
                    0,
                    0,
                    dp(8),
                    0
                )
            }

        top.addView(
            slotText
        )

        val nodeIdText =
            tv(
                state.nodeId,
                15,
                cText0
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                includeFontPadding =
                    false
            }

        top.addView(
            nodeIdText,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        val statusText =
            tv(
                status,
                11,
                statusColor
            ).apply {
                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                includeFontPadding =
                    false
            }

        top.addView(
            statusText
        )

        card.addView(top)

        val connectionInfo =
            if (
                state.wifiMac.isNotBlank() ||
                state.bleMac.isNotBlank()
            ) {

                val parts =
                    ArrayList<String>()

                if (
                    state.wifiMac.isNotBlank()
                ) {
                    parts.add(
                        "Wi-Fi ${state.wifiMac}"
                    )
                }

                if (
                    state.bleMac.isNotBlank()
                ) {
                    parts.add(
                        "BLE ${state.bleMac}"
                    )
                }

                parts.joinToString(
                    "  •  "
                )

            } else {
                if (state.online) {
                    "Connected to controller"
                } else {
                    "Waiting for connection"
                }
            }

        val connectionText =
            tv(
                connectionInfo,
                10,
                cText1
            ).apply {
                includeFontPadding =
                    false

                typeface =
                    Typeface.MONOSPACE

                setPadding(
                    dp(30),
                    dp(2),
                    0,
                    0
                )
            }

        card.addView(
            connectionText
        )

        val heartbeatText =
            tv(
                if (
                    state.lastHeartbeat.isNotBlank()
                ) {
                    "LAST HEARTBEAT  ${state.lastHeartbeat}"
                } else {
                    ""
                },
                9,
                cText2
            ).apply {
                includeFontPadding =
                    false

                typeface =
                    Typeface.MONOSPACE

                setPadding(
                    dp(30),
                    dp(3),
                    0,
                    0
                )
            }

        card.addView(
            heartbeatText
        )

        return NodeCardViews(
            card = card,
            statusDot = statusDot,
            slotText = slotText,
            nodeIdText = nodeIdText,
            statusText = statusText,
            connectionText = connectionText,
            heartbeatText = heartbeatText
        )
    }


    private fun makeCaptureRecord(
        json: JSONObject,
        type: String,
        timestamp: Date
    ): CaptureRecord {

        val location =
            lastLocation

        val mac =
            if (type == "BLE") {
                json.optString(
                    "mac",
                    ""
                )
                    .trim()
                    .uppercase(Locale.US)
            } else {
                json.optString(
                    "bssid",
                    ""
                )
                    .trim()
                    .uppercase(Locale.US)
            }

        val name =
            if (type == "BLE") {
                json.optString(
                    "name",
                    json.optString(
                        "ssid",
                        mac
                    )
                )
            } else {
                json.optString(
                    "ssid",
                    ""
                )
            }

        val auth =
            if (type == "BLE") {
                json.optString(
                    "auth",
                    "[BLE]"
                )
            } else {
                json.optString(
                    "auth",
                    json.optString(
                        "capabilities",
                        ""
                    )
                )
            }

        val channel =
            json.optString(
                "channel",
                ""
            )

        val frequency =
            json.optString(
                "frequency",
                ""
            )

        val rssi =
            json.optString(
                "rssi",
                ""
            )

        val altitude =
            location?.altitude
                ?.toString()
                ?: ""

        val accuracy =
            location?.accuracy
                ?.toString()
                ?: ""

        val latitude =
            location?.latitude
                ?.toString()
                ?: ""

        val longitude =
            location?.longitude
                ?.toString()
                ?: ""

        val rcois =
            json.optString(
                "rcois",
                ""
            )

        val mfgrId =
            json.optString(
                "mfgrId",
                json.optString(
                    "manufacturer_id",
                    ""
                )
            )

        return CaptureRecord(
            type = type,
            mac = mac,
            name = name,
            authMode = auth,
            channel = channel,
            frequency = frequency,
            rssi = rssi,
            firstSeenUtc =
                exportDateFormat.format(
                    timestamp
                ),
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            accuracy = accuracy,
            rcois = rcois,
            mfgrId = mfgrId
        )
    }

    private fun csv(
        value: String
    ): String =
        "\"${
            value.replace(
                "\"",
                "\"\""
            )
        }\""

    private fun buildWigleCsv(): String {
        val out =
            StringBuilder()

        out.append(
            "WigleWifi-1.6,appRelease=1.0,"
        )
            .append(
                "model=DPS Field Controller,"
            )
            .append(
                "release=1.0"
            )
            .append('\n')

        out.append(
            "MAC,SSID,AuthMode,FirstSeen,Channel," +
                    "Frequency,RSSI,CurrentLatitude," +
                    "CurrentLongitude,AltitudeMeters," +
                    "AccuracyMeters,RCOIs,MfgrId,Type\n"
        )

        for (record in captureRecords) {

            out.append(
                csv(record.mac)
            ).append(',')

            out.append(
                csv(record.name)
            ).append(',')

            out.append(
                csv(record.authMode)
            ).append(',')

            out.append(
                csv(record.firstSeenUtc)
            ).append(',')

            out.append(
                csv(record.channel)
            ).append(',')

            out.append(
                csv(record.frequency)
            ).append(',')

            out.append(
                csv(record.rssi)
            ).append(',')

            out.append(
                csv(record.latitude)
            ).append(',')

            out.append(
                csv(record.longitude)
            ).append(',')

            out.append(
                csv(record.altitude)
            ).append(',')

            out.append(
                csv(record.accuracy)
            ).append(',')

            out.append(
                csv(record.rcois)
            ).append(',')

            out.append(
                csv(record.mfgrId)
            ).append(',')

            out.append(
                record.type
            ).append('\n')
        }

        return out.toString()
    }

    private fun uploadAll() {
        val wigleNameValue = pref("wigle_name")
        val wigleTokenValue = pref("wigle_token")
        val wdgWarsKeyValue = pref("wdgwars_key")
        val wardriftUsernameValue = pref("wardrift_username")
        val wardriftPasswordValue = pref("wardrift_password")

        val wigleReady =
            wigleNameValue.isNotEmpty() &&
                    wigleTokenValue.isNotEmpty()

        val wdgWarsReady =
            wdgWarsKeyValue.isNotEmpty()

        val wardriftReady =
            wardriftUsernameValue.isNotEmpty() &&
                    wardriftPasswordValue.isNotEmpty()

        if (!wigleReady && !wdgWarsReady && !wardriftReady) {
            addLog(
                "UPLOAD ALL requested but no upload credentials are configured"
            )

            Toast.makeText(
                this,
                "Enter at least one upload service credential first",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (captureRecords.isEmpty()) {
            addLog(
                "UPLOAD ALL requested with no captured observations"
            )

            Toast.makeText(
                this,
                "No captured observations to upload",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val saved =
            saveWigleCsvToDownloads()
                ?: return

        val fileName = saved.fileName
        val data = saved.data

        addLog(
            "UPLOAD ALL started ($fileName, ${captureRecords.size} records)"
        )

        Thread {
            var uploaded = 0
            var skipped = 0

            if (wigleReady) {
                try {
                    val auth =
                        "Basic " +
                                Base64.encodeToString(
                                    "$wigleNameValue:$wigleTokenValue"
                                        .toByteArray(Charsets.UTF_8),
                                    Base64.NO_WRAP
                                )

                    val (code, body) =
                        multipartUpload(
                            urlStr =
                                "https://api.wigle.net/api/v2/file/upload",
                            headers =
                                mapOf(
                                    "Authorization" to auth,
                                    "Accept" to "application/json"
                                ),
                            fields =
                                mapOf("donate" to "false"),
                            fileFieldName = "file",
                            fileName = fileName,
                            fileBytes = data,
                            fileContentType = "text/csv"
                        )

                    val success =
                        code in 200..299 &&
                                body.contains("\"success\":true")

                    if (success) uploaded++ else skipped++

                    addLog(
                        "WiGLE upload response ($code): $body"
                    )
                } catch (e: Exception) {
                    skipped++
                    addLog(
                        "WiGLE upload failed: ${e.message ?: "unknown error"}"
                    )
                }
            }

            if (wdgWarsReady) {
                try {
                    val (code, body) =
                        multipartUpload(
                            urlStr =
                                "https://wdgwars.pl/api/upload-csv",
                            headers =
                                mapOf(
                                    "X-API-Key" to wdgWarsKeyValue
                                ),
                            fields = emptyMap(),
                            fileFieldName = "file",
                            fileName = fileName,
                            fileBytes = data,
                            fileContentType = "text/csv"
                        )

                    val ok = code in 200..299
                    if (ok) uploaded++ else skipped++

                    addLog(
                        "WDGWars upload response ($code): $body"
                    )
                } catch (e: Exception) {
                    skipped++
                    addLog(
                        "WDGWars upload failed: ${e.message ?: "unknown error"}"
                    )
                }
            }

            if (wardriftReady) {
                try {
                    val result =
                        uploadWardriftData(
                            fileName = fileName,
                            data = data,
                            username = wardriftUsernameValue,
                            password = wardriftPasswordValue
                        )

                    if (result.first) {
                        uploaded++
                    } else {
                        skipped++
                    }

                    addLog(
                        "Wardrift upload response (${result.second.first}): ${result.second.second}"
                    )
                } catch (e: Exception) {
                    skipped++
                    addLog(
                        "Wardrift upload failed: ${e.message ?: "unknown error"}"
                    )
                }
            }

            runOnUiThread {
                addLog(
                    "UPLOAD ALL finished: $uploaded uploaded, $skipped failed/skipped"
                )

                Toast.makeText(
                    this,
                    "UPLOAD ALL finished: $uploaded uploaded",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun uploadToWigle() {
        val name = pref("wigle_name")
        val token = pref("wigle_token")

        if (
            name.isEmpty() ||
            token.isEmpty()
        ) {
            Toast.makeText(
                this,
                "Enter your WiGLE API name and token first",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (captureRecords.isEmpty()) {
            addLog(
                "WiGLE upload requested with no captured observations"
            )

            Toast.makeText(
                this,
                "No captured observations to upload",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val saved =
            saveWigleCsvToDownloads()
                ?: return

        val fileName =
            saved.fileName

        val data =
            saved.data

        addLog(
            "WiGLE upload started " +
                    "($fileName, ${captureRecords.size} records)"
        )

        Thread {
            try {

                val auth =
                    "Basic " +
                            Base64.encodeToString(
                                "$name:$token"
                                    .toByteArray(
                                        Charsets.UTF_8
                                    ),
                                Base64.NO_WRAP
                            )

                val (code, body) =
                    multipartUpload(
                        urlStr =
                            "https://api.wigle.net/api/v2/file/upload",
                        headers =
                            mapOf(
                                "Authorization" to auth,
                                "Accept" to "application/json"
                            ),
                        fields =
                            mapOf(
                                "donate" to "false"
                            ),
                        fileFieldName =
                            "file",
                        fileName =
                            fileName,
                        fileBytes =
                            data,
                        fileContentType =
                            "text/csv"
                    )

                val success =
                    code in 200..299 &&
                            body.contains(
                                "\"success\":true"
                            )

                runOnUiThread {
                    addLog(
                        "WiGLE upload response " +
                                "($code): $body"
                    )

                    Toast.makeText(
                        this,
                        if (success) {
                            "Uploaded to WiGLE"
                        } else {
                            "WiGLE upload failed — see Monitor log"
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                runOnUiThread {
                    addLog(
                        "WiGLE upload failed: " +
                                "${e.message ?: "unknown error"}"
                    )

                    Toast.makeText(
                        this,
                        "WiGLE upload failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun uploadToWdgWars() {
        val key = pref("wdgwars_key")

        if (key.isEmpty()) {
            Toast.makeText(
                this,
                "Enter your WDGWars.pl API key first",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (captureRecords.isEmpty()) {
            addLog(
                "WDGWars upload requested with no captured observations"
            )

            Toast.makeText(
                this,
                "No captured observations to upload",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val saved =
            saveWigleCsvToDownloads()
                ?: return

        val fileName =
            saved.fileName

        val data =
            saved.data

        addLog(
            "WDGWars upload started " +
                    "($fileName, ${captureRecords.size} records)"
        )

        Thread {
            try {

                val (code, body) =
                    multipartUpload(
                        urlStr =
                            "https://wdgwars.pl/api/upload-csv",
                        headers =
                            mapOf(
                                "X-API-Key" to key
                            ),
                        fields =
                            emptyMap(),
                        fileFieldName =
                            "file",
                        fileName =
                            fileName,
                        fileBytes =
                            data,
                        fileContentType =
                            "text/csv"
                    )

                val ok =
                    code in 200..299

                runOnUiThread {
                    addLog(
                        "WDGWars upload response " +
                                "($code): $body"
                    )

                    Toast.makeText(
                        this,
                        if (ok) {
                            "Uploaded to WDGWars"
                        } else {
                            "WDGWars upload failed — see Monitor log"
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                runOnUiThread {
                    addLog(
                        "WDGWars upload failed: " +
                                "${e.message ?: "unknown error"}"
                    )

                    Toast.makeText(
                        this,
                        "WDGWars upload failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }



    private fun signInAndGetWardriftToken(
        username: String,
        password: String
    ): String {
        val payload =
            JSONObject()
                .put("username", username)
                .put("password", password)
                .toString()

        val conn =
            (
                    java.net.URL(
                        "https://wardrift.net/v1/auth/sign-in/username"
                    ).openConnection()
                            as java.net.HttpURLConnection
                    ).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 20000
                    readTimeout = 30000
                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )
                }

        conn.outputStream.use { out ->
            out.write(
                payload.toByteArray(Charsets.UTF_8)
            )
            out.flush()
        }

        val code = conn.responseCode
        val body =
            try {
                val stream =
                    if (code in 200..299) {
                        conn.inputStream
                    } else {
                        conn.errorStream
                    }

                stream?.bufferedReader()?.use { it.readText() }
                    ?: ""
            } catch (_: Exception) {
                ""
            }

        if (code !in 200..299) {
            throw IllegalStateException(
                "HTTP $code${if (body.isNotBlank()) ": $body" else ""}"
            )
        }

        val json =
            try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Wardrift returned invalid sign-in JSON"
                )
            }

        val token =
            findWardriftToken(json)
                .orEmpty()

        if (token.isEmpty()) {
            throw IllegalStateException(
                "Signed in, but no session token came back"
            )
        }

        return token
    }

    private fun findWardriftToken(
        json: JSONObject
    ): String? {
        val preferredKeys =
            listOf(
                "token",
                "sessionToken",
                "session_token",
                "accessToken",
                "access_token"
            )

        for (key in preferredKeys) {
            if (json.has(key) && !json.isNull(key)) {
                val value =
                    json.optString(key, "").trim()

                if (value.isNotEmpty()) {
                    return value
                }
            }
        }

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)

            if (value is JSONObject) {
                val nested =
                    findWardriftToken(value)

                if (!nested.isNullOrBlank()) {
                    return nested
                }
            }
        }

        return null
    }

    private fun uploadWardriftData(
        fileName: String,
        data: ByteArray,
        username: String,
        password: String
    ): Pair<Boolean, Pair<Int, String>> {

        var token =
            prefs.getString(
                "wardrift_session_token",
                ""
            )
                ?.trim()
                .orEmpty()

        if (token.isEmpty()) {
            token =
                signInAndGetWardriftToken(
                    username,
                    password
                )

            prefs.edit()
                .putString(
                    "wardrift_session_token",
                    token
                )
                .apply()
        }

        var result =
            multipartUpload(
                urlStr =
                    "https://wardrift.net/v1/wardrive/logs",
                headers =
                    mapOf(
                        "Authorization" to
                                "Bearer $token",
                        "Accept" to
                                "application/json"
                    ),
                fields =
                    mapOf(
                        "public_route" to
                                "true"
                    ),
                fileFieldName =
                    "file",
                fileName =
                    fileName,
                fileBytes =
                    data,
                fileContentType =
                    "text/csv"
            )

        if (result.first == 401) {
            addLog(
                "Wardrift session expired; signing in again"
            )

            token =
                signInAndGetWardriftToken(
                    username,
                    password
                )

            prefs.edit()
                .putString(
                    "wardrift_session_token",
                    token
                )
                .apply()

            result =
                multipartUpload(
                    urlStr =
                        "https://wardrift.net/v1/wardrive/logs",
                    headers =
                        mapOf(
                            "Authorization" to
                                    "Bearer $token",
                            "Accept" to
                                    "application/json"
                        ),
                    fields =
                        mapOf(
                            "public_route" to
                                    "true"
                        ),
                    fileFieldName =
                        "file",
                    fileName =
                        fileName,
                    fileBytes =
                        data,
                    fileContentType =
                        "text/csv"
                )
        }

        return Pair(
            result.first in 200..299,
            result
        )
    }

    private fun uploadToWardrift() {
        val username = pref("wardrift_username")
        val password = pref("wardrift_password")

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(
                this,
                "Enter your Wardrift username and password first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (captureRecords.isEmpty()) {
            addLog(
                "Wardrift upload requested with no captured observations"
            )

            Toast.makeText(
                this,
                "No captured observations to upload",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val saved =
            saveWigleCsvToDownloads()
                ?: return

        val fileName = saved.fileName
        val data = saved.data

        addLog(
            "Wardrift upload started ($fileName, ${captureRecords.size} records)"
        )

        Thread {
            try {
                val result =
                    uploadWardriftData(
                        fileName = fileName,
                        data = data,
                        username = username,
                        password = password
                    )

                val code = result.second.first
                val body = result.second.second
                val ok = result.first

                runOnUiThread {
                    addLog(
                        "Wardrift upload response ($code): $body"
                    )

                    Toast.makeText(
                        this,
                        if (ok) {
                            "Uploaded to Wardrift"
                        } else {
                            "Wardrift upload failed — see Monitor log"
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addLog(
                        "Wardrift upload failed: ${e.message ?: "unknown error"}"
                    )

                    Toast.makeText(
                        this,
                        "Wardrift upload failed — see Monitor log",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun multipartUpload(
        urlStr: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray,
        fileContentType: String
    ): Pair<Int, String> {

        val boundary =
            "----DPSBoundary${System.currentTimeMillis()}"

        val conn =
            (
                    java.net.URL(urlStr)
                        .openConnection()
                            as java.net.HttpURLConnection
                    ).apply {

                    requestMethod = "POST"

                    doOutput = true

                    connectTimeout = 20000

                    readTimeout = 60000

                    setChunkedStreamingMode(0)

                    setRequestProperty(
                        "Content-Type",
                        "multipart/form-data; boundary=$boundary"
                    )

                    headers.forEach { (k, v) ->
                        setRequestProperty(
                            k,
                            v
                        )
                    }
                }

        conn.outputStream.use { out ->

            val writer =
                out.bufferedWriter(
                    Charsets.UTF_8
                )

            fields.forEach { (name, value) ->

                writer.write(
                    "--$boundary\r\n"
                )

                writer.write(
                    "Content-Disposition: " +
                            "form-data; name=\"$name\"\r\n\r\n"
                )

                writer.write(
                    "$value\r\n"
                )
            }

            writer.write(
                "--$boundary\r\n"
            )

            writer.write(
                "Content-Disposition: " +
                        "form-data; name=\"$fileFieldName\"; " +
                        "filename=\"$fileName\"\r\n"
            )

            writer.write(
                "Content-Type: $fileContentType\r\n\r\n"
            )

            writer.flush()

            out.write(fileBytes)

            out.flush()

            writer.write(
                "\r\n--$boundary--\r\n"
            )

            writer.flush()
        }

        val code =
            conn.responseCode

        val stream =
            if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }

        val body =
            stream
                ?.bufferedReader(
                    Charsets.UTF_8
                )
                ?.use {
                    it.readText()
                }
                ?: ""

        conn.disconnect()

        return code to body
    }

    private fun saveWigleCsvToDownloads():
            SavedCsv? {

        if (captureRecords.isEmpty()) {

            addLog(
                "WiGLE export requested with no captured observations"
            )

            Toast.makeText(
                this,
                "No captured observations to save",
                Toast.LENGTH_SHORT
            ).show()

            return null
        }

        val fileName =
            "DPS-${
                SimpleDateFormat(
                    "yyyyMMddHHmmss",
                    Locale.US
                ).format(Date())
            }.csv"

        val data =
            buildWigleCsv()
                .toByteArray(
                    Charsets.UTF_8
                )

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                val values =
                    android.content.ContentValues()
                        .apply {

                            put(
                                MediaStore.Downloads.DISPLAY_NAME,
                                fileName
                            )

                            put(
                                MediaStore.Downloads.MIME_TYPE,
                                "text/csv"
                            )

                            put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS
                            )

                            put(
                                MediaStore.Downloads.IS_PENDING,
                                1
                            )
                        }

                val uri =
                    contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    )
                        ?: throw java.io.IOException(
                            "Unable to create Downloads file"
                        )

                try {

                    contentResolver
                        .openOutputStream(uri)
                        ?.use {
                            it.write(data)
                        }
                        ?: throw java.io.IOException(
                            "Unable to open Downloads file"
                        )

                    values.clear()

                    values.put(
                        MediaStore.Downloads.IS_PENDING,
                        0
                    )

                    contentResolver.update(
                        uri,
                        values,
                        null,
                        null
                    )

                } catch (e: Exception) {

                    contentResolver.delete(
                        uri,
                        null,
                        null
                    )

                    throw e
                }

            } else {

                @Suppress("DEPRECATION")
                val downloads =
                    Environment
                        .getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )

                if (
                    !downloads.exists() &&
                    !downloads.mkdirs()
                ) {
                    throw java.io.IOException(
                        "Unable to create Downloads directory"
                    )
                }

                FileOutputStream(
                    File(
                        downloads,
                        fileName
                    )
                ).use {
                    it.write(data)
                }
            }

            addLog(
                "WiGLE CSV saved to Downloads: " +
                        "$fileName (${captureRecords.size} records)"
            )

            Toast.makeText(
                this,
                "Saved to Downloads: $fileName",
                Toast.LENGTH_LONG
            ).show()

            return SavedCsv(
                fileName = fileName,
                data = data
            )

        } catch (e: Exception) {

            addLog(
                "WiGLE CSV save failed: " +
                        "${e.message ?: "unknown error"}"
            )

            Toast.makeText(
                this,
                "Could not save WiGLE CSV",
                Toast.LENGTH_LONG
            ).show()

            return null
        }
    }

    private fun exportDebugLogToDownloads() {

        val file =
            fileLogger.currentFile()

        if (
            file == null ||
            !file.exists() ||
            file.length() == 0L
        ) {
            Toast.makeText(
                this,
                "No debug log data yet",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        fileLogger.flush()

        val fileName =
            file.name

        try {

            val data =
                file.readBytes()

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                val values =
                    android.content.ContentValues()
                        .apply {

                            put(
                                MediaStore.Downloads.DISPLAY_NAME,
                                fileName
                            )

                            put(
                                MediaStore.Downloads.MIME_TYPE,
                                "text/plain"
                            )

                            put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS
                            )

                            put(
                                MediaStore.Downloads.IS_PENDING,
                                1
                            )
                        }

                val uri =
                    contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    )
                        ?: throw java.io.IOException(
                            "Unable to create Downloads file"
                        )

                try {

                    contentResolver
                        .openOutputStream(uri)
                        ?.use {
                            it.write(data)
                        }
                        ?: throw java.io.IOException(
                            "Unable to open Downloads file"
                        )

                    values.clear()

                    values.put(
                        MediaStore.Downloads.IS_PENDING,
                        0
                    )

                    contentResolver.update(
                        uri,
                        values,
                        null,
                        null
                    )

                } catch (e: Exception) {

                    contentResolver.delete(
                        uri,
                        null,
                        null
                    )

                    throw e
                }

            } else {

                @Suppress("DEPRECATION")
                val downloads =
                    Environment
                        .getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )

                if (
                    !downloads.exists() &&
                    !downloads.mkdirs()
                ) {
                    throw java.io.IOException(
                        "Unable to create Downloads directory"
                    )
                }

                FileOutputStream(
                    File(
                        downloads,
                        fileName
                    )
                ).use {
                    it.write(data)
                }
            }

            addLog(
                "DEBUG LOG exported to Downloads: $fileName"
            )

            Toast.makeText(
                this,
                "Debug log saved to Downloads: $fileName",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {

            addLog(
                "DEBUG LOG export failed: " +
                        "${e.message ?: "unknown error"}"
            )

            Toast.makeText(
                this,
                "Could not export debug log",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMap() {

        mapView.onResume()

        mapView.controller.setZoom(
            16.0
        )

        if (hasLocationPermission()) {

            try {

                val gpsEnabled =
                    locationManager.isProviderEnabled(
                        LocationManager.GPS_PROVIDER
                    )

                val networkEnabled =
                    locationManager.isProviderEnabled(
                        LocationManager.NETWORK_PROVIDER
                    )

                if (gpsEnabled) {

                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        1f,
                        locationListener
                    )
                }

                if (networkEnabled) {

                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2000L,
                        2f,
                        locationListener
                    )
                }

            } catch (_: SecurityException) {
            }
        }

        lastLocation?.let {
            updatePositionMarker(it)
        }
    }

    private fun updateTravelPath(
        location: Location
    ) {

        if (!::mapView.isInitialized) {
            return
        }

        val point =
            GeoPoint(
                location.latitude,
                location.longitude
            )

        val last =
            trackPoints.lastOrNull()

        if (last != null) {

            val distance =
                floatArrayOf(0f)

            Location.distanceBetween(
                last.latitude,
                last.longitude,
                point.latitude,
                point.longitude,
                distance
            )

            if (distance[0] < 5f) {
                return
            }
        }

        trackPoints.add(point)

        if (trackLine == null) {

            trackLine =
                Polyline(mapView).apply {
                    title =
                        "DPS TRAVEL PATH"

                    width =
                        dp(4).toFloat()
                }

            mapView.overlays.add(
                trackLine
            )
        }

        trackLine?.setPoints(
            trackPoints
        )

        mapView.invalidate()
    }

    private fun clearTravelPath() {

        trackPoints.clear()

        if (::mapView.isInitialized) {

            trackLine?.let {
                mapView.overlays.remove(it)
            }

            trackLine = null

            mapView.invalidate()
        }
    }

    private fun updateGpsUi(
        location: Location
    ) {

        if (::gpsText.isInitialized) {

            gpsText.text =
                String.format(
                    Locale.US,
                    "GPS FIX\n%.5f\n%.5f\n±%.0fm",
                    location.latitude,
                    location.longitude,
                    location.accuracy
                )
        }
    }

    private fun updateObservationUi() {

        updateStealthCounters()

        if (::observationText.isInitialized) {

            observationText.text =
                String.format(
                    Locale.US,
                    "OBSERVATIONS\n%d\nWIFI %d • BLE %d • CELL %d",
                    observations,
                    seenBssids.size,
                    seenBleMacs.size,
                    seenCellKeys.size
                )
        }
    }

    private fun updatePositionMarker(
        location: Location
    ) {

        if (!::mapView.isInitialized) {
            return
        }

        val point =
            GeoPoint(
                location.latitude,
                location.longitude
            )

        if (currentPositionMarker == null) {

            val marker =
                Marker(mapView).apply {

                    title =
                        "DPS FIELD UNIT"

                    icon =
                        ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.ic_position_marker
                        )

                    setAnchor(
                        Marker.ANCHOR_CENTER,
                        Marker.ANCHOR_CENTER
                    )
                }

            currentPositionMarker =
                marker

            mapView.overlays.add(
                marker
            )
        }

        currentPositionMarker?.position =
            point

        currentPositionMarker?.snippet =
            "Accuracy ±${location.accuracy.toInt()} m"

        centerOnLocation(location)

        mapView.invalidate()
    }

    private fun centerOnLocation(
        location: Location
    ) {

        if (!::mapView.isInitialized) {
            return
        }

        mapView.controller.animateTo(
            GeoPoint(
                location.latitude,
                location.longitude
            )
        )
    }

    private fun addObservationMarker(
        type: String,
        name: String,
        rssi: Int
    ) {

        val location =
            lastLocation
                ?: return

        if (!::mapView.isInitialized) {
            return
        }

        val marker =
            Marker(mapView).apply {

                position =
                    GeoPoint(
                        location.latitude,
                        location.longitude
                    )

                title =
                    "$type SIGNAL"

                snippet =
                    "$name • RSSI $rssi dBm"

                icon =
                    markerIcon(type)

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_BOTTOM
                )
            }

        mapView.overlays.add(marker)

        observationMarkers.addLast(marker)

        while (
            observationMarkers.size > 120
        ) {

            val old =
                observationMarkers.removeFirst()

            mapView.overlays.remove(old)
        }

        mapView.invalidate()
    }

    private fun markerIcon(
        type: String
    ): Drawable? {

        return if (type == "BLE") {

            ContextCompat.getDrawable(
                this,
                R.drawable.ic_ble_marker
            )

        } else {

            ContextCompat.getDrawable(
                this,
                R.drawable.ic_wifi_marker
            )
        }
    }

    private data class CellObservation(
        val type: String,
        val key: String,
        val name: String,
        val capabilities: String,
        val frequency: String,
        val rssi: Int
    )

    private fun startCellScanning() {

        if (cellScanRunnable != null) {
            return
        }

        val runnable =
            object : Runnable {

                override fun run() {

                    pollCellTowers()

                    cellScanHandler.postDelayed(
                        this,
                        cellScanIntervalMs
                    )
                }
            }

        cellScanRunnable =
            runnable

        cellScanHandler.post(runnable)
    }

    private fun stopCellScanning() {

        cellScanRunnable?.let {
            cellScanHandler.removeCallbacks(it)
        }

        cellScanRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun pollCellTowers() {

        if (
            !hasPhoneStatePermission() ||
            !hasLocationPermission()
        ) {

            addLog(
                "CELL: poll skipped — missing phone-state or location permission"
            )

            return
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            requestFreshCellInfo()

        } else {

            val infos:
                    List<CellInfo> =
                try {

                    telephonyManager
                        .allCellInfo
                        ?: emptyList()

                } catch (_: SecurityException) {

                    emptyList()
                }

            handleCellInfoList(infos)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun requestFreshCellInfo() {

        try {

            telephonyManager.requestCellInfoUpdate(
                ContextCompat.getMainExecutor(
                    this
                ),
                object :
                    TelephonyManager.CellInfoCallback() {

                    override fun onCellInfo(
                        cellInfo:
                        MutableList<CellInfo>
                    ) {

                        handleCellInfoList(
                            cellInfo
                        )
                    }

                    override fun onError(
                        errorCode: Int,
                        detail: Throwable?
                    ) {

                        addLog(
                            "CELL: requestCellInfoUpdate error " +
                                    "$errorCode " +
                                    "${detail?.message ?: ""}"
                        )
                    }
                }
            )

        } catch (e: SecurityException) {

            addLog(
                "CELL: requestCellInfoUpdate SecurityException: " +
                        "${e.message}"
            )
        }
    }

    private fun handleCellInfoList(
        infos: List<CellInfo>
    ) {

        if (infos.isEmpty()) {

            addLog(
                "CELL: poll returned 0 towers " +
                        "(check system Location is ON, and that " +
                        "this device has cellular radio)"
            )

            return
        }

        val now =
            Date()

        for (info in infos) {

            val obs =
                extractCellObservation(info)
                    ?: continue

            if (!seenCellKeys.add(obs.key)) {
                continue
            }

            observations++

            updateObservationUi()

            recent.addLast(
                "${dateFormat.format(now)}|" +
                        "${obs.type}|" +
                        "${obs.name}|" +
                        "${obs.rssi}|0"
            )

            while (recent.size > 20) {
                recent.removeFirst()
            }

            renderRecentObservations()

            if (scanning) {

                captureRecords.add(
                    CaptureRecord(
                        type =
                            obs.type,

                        mac =
                            obs.key,

                        name =
                            obs.name,

                        authMode =
                            obs.capabilities,

                        channel =
                            "",

                        frequency =
                            obs.frequency,

                        rssi =
                            obs.rssi.toString(),

                        firstSeenUtc =
                            exportDateFormat.format(
                                now
                            ),

                        latitude =
                            lastLocation
                                ?.latitude
                                ?.toString()
                                ?: "",

                        longitude =
                            lastLocation
                                ?.longitude
                                ?.toString()
                                ?: "",

                        altitude =
                            lastLocation
                                ?.altitude
                                ?.toString()
                                ?: "",

                        accuracy =
                            lastLocation
                                ?.accuracy
                                ?.toString()
                                ?: "",

                        rcois =
                            "",

                        mfgrId =
                            ""
                    )
                )
            }

            addLog(
                "CELL: ${obs.type} " +
                        "${obs.key} RSSI ${obs.rssi}"
            )
        }
    }

    private fun mccMncOperator(
        mcc: String?,
        mnc: String?
    ): String? {

        if (
            mcc.isNullOrBlank() ||
            mnc.isNullOrBlank()
        ) {
            return null
        }

        return "$mcc$mnc"
    }

    @Suppress("DEPRECATION")
    private fun getMccMnc(
        info: CellInfo
    ): Pair<String?, String?> {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.P
        ) {

            when (info) {

                is CellInfoLte ->
                    info.cellIdentity.mccString to
                            info.cellIdentity.mncString

                is CellInfoGsm ->
                    info.cellIdentity.mccString to
                            info.cellIdentity.mncString

                is CellInfoWcdma ->
                    info.cellIdentity.mccString to
                            info.cellIdentity.mncString

                else ->
                    null to null
            }

        } else {

            when (info) {

                is CellInfoLte -> {

                    val mcc =
                        info.cellIdentity.mcc
                            .takeIf {
                                it != Int.MAX_VALUE
                            }
                            ?.toString()

                    val mnc =
                        info.cellIdentity.mnc
                            .takeIf {
                                it != Int.MAX_VALUE
                            }
                            ?.toString()

                    mcc to mnc
                }

                is CellInfoGsm -> {

                    val mcc =
                        info.cellIdentity.mcc
                            .takeIf {
                                it != Int.MAX_VALUE
                            }
                            ?.toString()

                    val mnc =
                        info.cellIdentity.mnc
                            .takeIf {
                                it != Int.MAX_VALUE
                            }
                            ?.toString()

                    mcc to mnc
                }

                is CellInfoWcdma -> {

                    val mcc =
                        info.cellIdentity.mcc
                            .takeIf {
                                it != Int.MAX_VALUE
                            }
                            ?.toString()

                    val mnc =
                        info.cellIdentity.mnc
                            .takeIf {
                                it != Int.MAX_VALUE
                            }
                            ?.toString()

                    mcc to mnc
                }

                else ->
                    null to null
            }
        }
    }

    private fun extractCellObservation(
        info: CellInfo
    ): CellObservation? {

        val (mcc, mnc) =
            getMccMnc(info)

        return when (info) {

            is CellInfoLte -> {

                val id =
                    info.cellIdentity

                val op =
                    mccMncOperator(
                        mcc,
                        mnc
                    )
                        ?: return null

                val tac =
                    id.tac

                val ci =
                    id.ci

                if (
                    tac == Int.MAX_VALUE ||
                    ci == Int.MAX_VALUE
                ) {
                    return null
                }

                CellObservation(
                    type =
                        "LTE",

                    key =
                        "${op}_${tac}_${ci}",

                    name =
                        op,

                    capabilities =
                        "LTE;$op",

                    frequency =
                        if (
                            id.earfcn !=
                            Int.MAX_VALUE
                        ) {
                            id.earfcn.toString()
                        } else {
                            ""
                        },

                    rssi =
                        info.cellSignalStrength.dbm
                )
            }

            is CellInfoGsm -> {

                val id =
                    info.cellIdentity

                val op =
                    mccMncOperator(
                        mcc,
                        mnc
                    )
                        ?: return null

                val lac =
                    id.lac

                val cid =
                    id.cid

                if (
                    lac == Int.MAX_VALUE ||
                    cid == Int.MAX_VALUE
                ) {
                    return null
                }

                CellObservation(
                    type =
                        "GSM",

                    key =
                        "${op}_${lac}_${cid}",

                    name =
                        op,

                    capabilities =
                        "GSM;$op",

                    frequency =
                        if (
                            id.arfcn !=
                            Int.MAX_VALUE
                        ) {
                            id.arfcn.toString()
                        } else {
                            ""
                        },

                    rssi =
                        info.cellSignalStrength.dbm
                )
            }

            is CellInfoWcdma -> {

                val id =
                    info.cellIdentity

                val op =
                    mccMncOperator(
                        mcc,
                        mnc
                    )
                        ?: return null

                val lac =
                    id.lac

                val cid =
                    id.cid

                if (
                    lac == Int.MAX_VALUE ||
                    cid == Int.MAX_VALUE
                ) {
                    return null
                }

                CellObservation(
                    type =
                        "WCDMA",

                    key =
                        "${op}_${lac}_${cid}",

                    name =
                        op,

                    capabilities =
                        "WCDMA;$op",

                    frequency =
                        if (
                            id.uarfcn !=
                            Int.MAX_VALUE
                        ) {
                            id.uarfcn.toString()
                        } else {
                            ""
                        },

                    rssi =
                        info.cellSignalStrength.dbm
                )
            }

            is CellInfoCdma -> {

                val id =
                    info.cellIdentity

                CellObservation(
                    type =
                        "CDMA",

                    key =
                        "${id.systemId}_" +
                                "${id.networkId}_" +
                                "${id.basestationId}",

                    name =
                        "CDMA",

                    capabilities =
                        "CDMA;${id.systemId}",

                    frequency =
                        "",

                    rssi =
                        info.cellSignalStrength.dbm
                )
            }

            else -> {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {
                    extractNrObservation(info)
                } else {
                    null
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun extractNrObservation(
        info: CellInfo
    ): CellObservation? {

        val nrInfo =
            info as?
                    android.telephony.CellInfoNr
                ?: return null

        val id =
            nrInfo.cellIdentity as?
                    android.telephony.CellIdentityNr
                ?: return null

        val ss =
            nrInfo.cellSignalStrength as?
                    android.telephony.CellSignalStrengthNr
                ?: return null

        val op =
            mccMncOperator(
                id.mccString,
                id.mncString
            )
                ?: return null

        val tac =
            id.tac

        val nci =
            id.nci

        if (
            tac == Int.MAX_VALUE ||
            nci == Long.MAX_VALUE
        ) {
            return null
        }

        return CellObservation(
            type =
                "NR",

            key =
                "${op}_${tac}_${nci}",

            name =
                op,

            capabilities =
                "NR;$op",

            frequency =
                if (
                    id.nrarfcn !=
                    Int.MAX_VALUE
                ) {
                    id.nrarfcn.toString()
                } else {
                    ""
                },

            rssi =
                ss.dbm
        )
    }

    private fun requestPermissionsIfNeeded() {

        val permissions =
            mutableListOf<String>()

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S
        ) {

            permissions +=
                Manifest.permission.BLUETOOTH_SCAN

            permissions +=
                Manifest.permission.BLUETOOTH_CONNECT
        }

        permissions +=
            Manifest.permission.ACCESS_FINE_LOCATION

        permissions +=
            Manifest.permission.ACCESS_COARSE_LOCATION

        permissions +=
            Manifest.permission.READ_PHONE_STATE

        val missing =
            permissions.any {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing) {

            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                100
            )

        } else {

            ble.connectBondedOrScan()

            startLocationIfDashboardReady()
        }
    }

    private fun startLocationIfDashboardReady() {

        if (hasLocationPermission()) {
            startMap()
        }
    }

    private fun hasLocationPermission():
            Boolean =

        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) ==
                PackageManager.PERMISSION_GRANTED ||

                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasPhoneStatePermission():
            Boolean =

        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 100) {

            ble.connectBondedOrScan()

            startLocationIfDashboardReady()
        }
    }

    override fun onResume() {

        super.onResume()

        if (::mapView.isInitialized) {
            mapView.onResume()
        }

        if (::ble.isInitialized) {
            ble.connectBondedOrScan()
        }
    }

    override fun onPause() {

        if (::mapView.isInitialized) {
            mapView.onPause()
        }

        try {
            locationManager.removeUpdates(
                locationListener
            )
        } catch (_: Exception) {
        }

        super.onPause()
    }

    override fun onDestroy() {

        try {
            locationManager.removeUpdates(
                locationListener
            )
        } catch (_: Exception) {
        }

        try {
            ble.disconnect()
        } catch (_: Exception) {
        }

        stopCellScanning()

        if (::fileLogger.isInitialized) {

            fileLogger.write(
                "=== APP STOP ==="
            )

            fileLogger.close()
        }

        super.onDestroy()
    }

    private fun cardBg(
        borderColor: Int,
        fillColor: Int = cPanel,
        radius: Float = dp(4).toFloat(),
        strokeWidth: Int = dp(1)
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                radius

            setColor(fillColor)

            setStroke(
                strokeWidth,
                borderColor
            )
        }
    }

    private fun ovalBg(
        borderColor: Int,
        fillColor: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.OVAL

            setColor(fillColor)

            setStroke(
                dp(1),
                borderColor
            )
        }
    }

    private fun pillButtonBg(
        accent: Int,
        dashed: Boolean
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                dp(3).toFloat()

            setColor(
                Color.argb(
                    28,
                    Color.red(accent),
                    Color.green(accent),
                    Color.blue(accent)
                )
            )

            if (dashed) {

                setStroke(
                    dp(1),
                    accent,
                    dp(6).toFloat(),
                    dp(4).toFloat()
                )

            } else {

                setStroke(
                    dp(1),
                    accent
                )
            }
        }
    }

    private fun panel(
        borderColor: Int = cLine
    ): LinearLayout {

        return LinearLayout(this).apply {

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(10),
                dp(8),
                dp(10),
                dp(8)
            )

            background =
                cardBg(
                    borderColor,
                    cPanel
                )

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    -2
                ).apply {

                    setMargins(
                        0,
                        dp(4),
                        0,
                        dp(4)
                    )
                }
        }
    }

    private fun panelText(
        title: String,
        text: String
    ): View {

        val p =
            panel()

        p.addView(
            tv(
                title,
                15,
                cText0
            ).apply {

                typeface =
                    Typeface.create(
                        "sans-serif-condensed",
                        Typeface.BOLD
                    )

                letterSpacing =
                    0.03f
            }
        )

        p.addView(
            tv(
                text,
                12,
                cText1
            ).apply {

                typeface =
                    Typeface.MONOSPACE
            }
        )

        return p
    }

    private fun sectionLabel(
        text: String,
        accent: Int
    ): TextView {

        return tv(
            text,
            11,
            accent
        ).apply {

            typeface =
                Typeface.create(
                    "sans-serif-condensed",
                    Typeface.BOLD
                )

            letterSpacing =
                0.1f

            includeFontPadding =
                false

            setPadding(
                dp(4),
                0,
                dp(4),
                dp(6)
            )
        }
    }

    private fun EditText.persist(
        key: String
    ) {

        setText(
            prefs.getString(
                key,
                ""
            )
        )

        addTextChangedListener(
            object :
                TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {

                    prefs.edit()
                        .putString(
                            key,
                            s?.toString() ?: ""
                        )
                        .apply()
                }
            }
        )
    }

    private inner class StatView(
        context: Context
    ) : TextView(context) {

        override fun setText(
            text: CharSequence?,
            type: BufferType?
        ) {

            val lines =
                (text?.toString() ?: "")
                    .split("\n")

            if (lines.size < 2) {

                super.setText(
                    text,
                    type
                )

                return
            }

            val flag =
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

            val data =
                lines.drop(1)

            val sb =
                SpannableStringBuilder()

            sb.append(lines[0])

            sb.setSpan(
                RelativeSizeSpan(0.74f),
                0,
                sb.length,
                flag
            )

            sb.setSpan(
                ForegroundColorSpan(cText2),
                0,
                sb.length,
                flag
            )

            val valueLineCount =
                if (data.size >= 2) {
                    data.size - 1
                } else {
                    data.size
                }

            data.forEachIndexed {
                    i,
                    line ->

                sb.append('\n')

                val start =
                    sb.length

                sb.append(line)

                if (i < valueLineCount) {

                    sb.setSpan(
                        RelativeSizeSpan(
                            1.24f
                        ),
                        start,
                        sb.length,
                        flag
                    )

                    sb.setSpan(
                        StyleSpan(
                            Typeface.BOLD
                        ),
                        start,
                        sb.length,
                        flag
                    )

                } else {

                    sb.setSpan(
                        RelativeSizeSpan(
                            0.8f
                        ),
                        start,
                        sb.length,
                        flag
                    )

                    sb.setSpan(
                        ForegroundColorSpan(
                            cText1
                        ),
                        start,
                        sb.length,
                        flag
                    )
                }
            }

            super.setText(
                sb,
                BufferType.SPANNABLE
            )
        }
    }

    private fun stat(
        title: String,
        value: String,
        accent: Int
    ): TextView {

        val tick =
            GradientDrawable().apply {

                cornerRadius =
                    dp(2).toFloat()

                setColor(accent)

                setSize(
                    dp(3),
                    dp(13)
                )
            }

        return StatView(this).apply {

            textSize =
                12f

            setPadding(
                dp(14),
                dp(13),
                dp(12),
                dp(13)
            )

            background =
                cardBg(
                    cLine,
                    cPanelSoft,
                    dp(6).toFloat(),
                    dp(1)
                )

            gravity =
                Gravity.CENTER_VERTICAL

            includeFontPadding =
                false

            typeface =
                Typeface.create(
                    "sans-serif-condensed",
                    Typeface.NORMAL
                )

            letterSpacing =
                0.01f

            setLineSpacing(
                dp(4).toFloat(),
                1f
            )

            setCompoundDrawablesWithIntrinsicBounds(
                tick,
                null,
                null,
                null
            )

            compoundDrawablePadding =
                dp(10)

            setTextColor(accent)

            text =
                "$title\n$value"
        }
    }

    private fun gridCellParams():
            LinearLayout.LayoutParams =

        LinearLayout.LayoutParams(
            0,
            -1,
            1f
        ).apply {

            setMargins(
                dp(3),
                dp(3),
                dp(3),
                dp(3)
            )
        }

    private fun tv(
        text: String,
        size: Int,
        textColor: Int
    ): TextView {

        return TextView(this).apply {

            this.text =
                text

            textSize =
                size.toFloat()

            setTextColor(
                textColor
            )

            setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4)
            )
        }
    }

    private fun button(
        text: String,
        accent: Int,
        dashed: Boolean = false,
        action: (() -> Unit)? = null
    ): Button {

        return Button(this).apply {

            this.text =
                text

            textSize =
                12.5f

            setTextColor(
                accent
            )

            typeface =
                Typeface.create(
                    "sans-serif-condensed",
                    Typeface.BOLD
                )

            letterSpacing =
                0.03f

            background =
                pillButtonBg(
                    accent,
                    dashed
                )

            setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4)
            )

            stateListAnimator =
                null

            action?.let {
                setOnClickListener {
                    it()
                }
            }
        }
    }

    private fun edit(
        hint: String
    ): EditText {

        return EditText(this).apply {

            this.hint =
                hint

            setSingleLine(true)

            setTextColor(
                cText0
            )

            setHintTextColor(
                cText2
            )

            typeface =
                Typeface.MONOSPACE

            background =
                cardBg(
                    cLine,
                    cPanelSoft,
                    dp(3).toFloat(),
                    dp(1)
                )

            setPadding(
                dp(12),
                dp(4),
                dp(12),
                dp(4)
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    -1,
                    dp(52)
                ).apply {

                    setMargins(
                        0,
                        dp(4),
                        0,
                        dp(4)
                    )
                }
        }
    }

    private fun weightParams():
            LinearLayout.LayoutParams =

        LinearLayout.LayoutParams(
            0,
            -2,
            1f
        ).apply {

            setMargins(
                dp(3),
                dp(3),
                dp(3),
                dp(3)
            )
        }

    private fun addLog(
        message: String
    ) {

        val timestamp =
            dateFormat.format(
                Date()
            )

        val line =
            "[$timestamp] $message"

        logs.addLast(line)

        while (logs.size > 200) {
            logs.removeFirst()
        }

        if (::fileLogger.isInitialized) {
            fileLogger.write(message)
        }
    }

    private fun copyLogs() {

        val clipboard =
            getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as android.content.ClipboardManager

        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "DPS Communication",
                logs.joinToString("\n")
            )
        )

        addLog(
            "COMMUNICATION COPIED"
        )
    }

    private fun color(
        hex: String
    ): Int =
        Color.parseColor(hex)

    private fun dp(
        value: Int
    ): Int =
        (
                value *
                        resources.displayMetrics.density
                ).toInt()


}