package com.example.karooinsta360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.karooinsta360.camera.CameraConfigActivity
import com.example.karooinsta360.camera.CameraStore
import com.example.karooinsta360.connection.Insta360ConnectionManager

/**
 * Camera list: shows every saved camera (name + address + live connected/recording
 * status), lets you scan for new ones or add one by address, remove a camera, or jump
 * into [CameraConfigActivity] to edit its trigger settings.
 *
 * Sideload this the same way you'd sideload any Karoo extension APK.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var noCamerasText: TextView
    private lateinit var cameraListContainer: LinearLayout
    private lateinit var scanForCamerasButton: Button
    private lateinit var discoveredListContainer: LinearLayout
    private lateinit var manualNameInput: EditText
    private lateinit var manualAddressInput: EditText
    private lateinit var manualAddButton: Button
    private lateinit var notifyCheckbox: CheckBox
    private lateinit var controlCenterCheckbox: CheckBox

    // address -> name, for devices found by the current scan but not yet saved.
    private val discovered = LinkedHashMap<String, String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            Insta360ConnectionManager.ensureStarted(this)
            startScan()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    // Android 13+ only — posting a notification needs this granted first. If the user
    // declines, the checkbox reverts itself rather than leaving a setting on that
    // silently does nothing.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            notifyCheckbox.isChecked = false
            AppSettings.setRecordingNotificationEnabled(this, false)
            Toast.makeText(this, "Notification permission denied — setting turned back off", Toast.LENGTH_LONG).show()
        }
    }

    private val connectionListener = object : Insta360ConnectionManager.Listener {
        override fun onCameraStateChanged(address: String) {
            runOnUiThread { refreshCameraList() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        noCamerasText = findViewById(R.id.noCamerasText)
        cameraListContainer = findViewById(R.id.cameraListContainer)
        scanForCamerasButton = findViewById(R.id.scanForCamerasButton)
        discoveredListContainer = findViewById(R.id.discoveredListContainer)
        manualNameInput = findViewById(R.id.manualNameInput)
        manualAddressInput = findViewById(R.id.manualAddressInput)
        manualAddButton = findViewById(R.id.manualAddButton)
        notifyCheckbox = findViewById(R.id.notifyOnRecordingChangeCheckbox)
        controlCenterCheckbox = findViewById(R.id.controlCenterControlCheckbox)

        scanForCamerasButton.setOnClickListener { requestPermissionsAndScan() }
        manualAddButton.setOnClickListener { addByAddress() }

        notifyCheckbox.isChecked = AppSettings.isRecordingNotificationEnabled(this)
        notifyCheckbox.setOnCheckedChangeListener { _, checked ->
            AppSettings.setRecordingNotificationEnabled(this, checked)
            if (checked) requestNotificationPermissionIfNeeded()
        }

        // No runtime permission needed here — this posts through karoo-ext's own
        // SystemNotification effect straight into the Karoo's Control Center, not
        // Android's NotificationManager, so POST_NOTIFICATIONS doesn't apply to it.
        controlCenterCheckbox.isChecked = AppSettings.isControlCenterControlEnabled(this)
        controlCenterCheckbox.setOnCheckedChangeListener { _, checked ->
            AppSettings.setControlCenterControlEnabled(this, checked)
        }

        Insta360ConnectionManager.ensureStarted(this)
        Insta360ConnectionManager.addListener(connectionListener)
    }

    override fun onResume() {
        super.onResume()
        refreshCameraList() // picks up name/config edits made in CameraConfigActivity

        // Catches the case where the user granted notification permission earlier, then
        // revoked it from system Settings without ever touching this checkbox — without
        // this, the setting would stay "on" while silently doing nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            AppSettings.isRecordingNotificationEnabled(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyCheckbox.isChecked = false
            AppSettings.setRecordingNotificationEnabled(this, false)
        }
    }

    override fun onDestroy() {
        Insta360ConnectionManager.removeListener(connectionListener)
        super.onDestroy()
        // Deliberately not touching any camera's BLE connection here — see
        // Insta360ConnectionManager's doc comment; connections outlive this screen.
    }

    // --- Saved camera list ---

    private fun refreshCameraList() {
        val states = Insta360ConnectionManager.getCameraStates(this)
        noCamerasText.visibility = if (states.isEmpty()) View.VISIBLE else View.GONE
        cameraListContainer.removeAllViews()
        states.forEach { state -> cameraListContainer.addView(buildCameraRow(state)) }
    }

    private fun buildCameraRow(state: Insta360ConnectionManager.CameraState): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val statusLabel = when {
            !state.connected -> "Disconnected"
            state.recording -> "Connected — Recording"
            else -> "Connected — Idle"
        }
        row.addView(
            TextView(this).apply {
                text = "${state.name} (${state.address})"
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        row.addView(TextView(this).apply { text = statusLabel; textSize = 12f })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        buttonRow.addView(
            Button(this).apply {
                text = "Configure"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, CameraConfigActivity::class.java)
                            .putExtra(CameraConfigActivity.EXTRA_ADDRESS, state.address),
                    )
                }
            },
        )
        buttonRow.addView(
            Button(this).apply {
                text = "Remove"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
                setOnClickListener {
                    Insta360ConnectionManager.removeCamera(this@MainActivity, state.address)
                    refreshCameraList()
                }
            },
        )
        row.addView(buttonRow)
        return row
    }

    // --- Scan / discover new cameras ---

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissionsAndScan() {
        if (hasPermissions()) {
            Insta360ConnectionManager.ensureStarted(this)
            startScan()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    // --- Recording start/stop notifications ---

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return // not needed before Android 13
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startScan() {
        discovered.clear()
        discoveredListContainer.removeAllViews()
        scanForCamerasButton.isEnabled = false
        scanForCamerasButton.text = "Scanning..."

        val savedAddresses = CameraStore.getCameras(this).map { it.address }.toSet()
        Insta360ConnectionManager.startDiscoveryScan(
            context = this,
            onFound = { address, name ->
                if (address in savedAddresses || discovered.containsKey(address)) return@startDiscoveryScan
                discovered[address] = name
                runOnUiThread { discoveredListContainer.addView(buildDiscoveredRow(address, name)) }
            },
            onFinished = {
                runOnUiThread {
                    scanForCamerasButton.isEnabled = true
                    scanForCamerasButton.text = "Scan for Cameras"
                    if (discovered.isEmpty()) {
                        Toast.makeText(this, "No new cameras found", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    private fun buildDiscoveredRow(address: String, name: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        row.addView(
            TextView(this).apply {
                text = "$name ($address)"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            Button(this).apply {
                text = "Add"
                setOnClickListener {
                    Insta360ConnectionManager.addCamera(this@MainActivity, address, name)
                    discoveredListContainer.removeView(row)
                    refreshCameraList()
                }
            },
        )
        return row
    }

    private fun addByAddress() {
        if (!hasPermissions()) {
            Toast.makeText(this, "Grant Bluetooth permissions first", Toast.LENGTH_LONG).show()
            return
        }
        val address = manualAddressInput.text.toString().trim()
        if (address.isEmpty()) {
            Toast.makeText(this, "Enter a camera address", Toast.LENGTH_LONG).show()
            return
        }
        val name = manualNameInput.text.toString().trim().ifEmpty { address }
        Insta360ConnectionManager.addCamera(this, address, name)
        manualAddressInput.setText("")
        manualNameInput.setText("")
        refreshCameraList()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
