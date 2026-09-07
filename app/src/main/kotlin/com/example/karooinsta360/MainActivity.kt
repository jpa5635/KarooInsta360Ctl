package com.example.karooinsta360

import android.Manifest
import android.app.AlertDialog
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
import com.example.karooinsta360.camera.ProfileActivity
import com.example.karooinsta360.camera.ProfileStore
import com.example.karooinsta360.connection.Insta360ConnectionManager

/**
 * Camera list: shows every saved camera (name + address + live connected/recording
 * status), lets you scan for new ones or add one by address, remove a camera, force a
 * fresh Bluetooth reconnect attempt (see [Insta360ConnectionManager.reconnect]), or jump
 * into [CameraConfigActivity] to rename/remove/manually test it.
 *
 * Also manages **configuration profiles** (2026-08-29, see [ProfileStore]) — named,
 * user-created sets of which cameras are switched on and what their heart rate/power/
 * speed/radar trigger settings are. **Changed (build 0.1.9):** trigger configuration now
 * lives entirely on the profile (via [ProfileActivity]/`ProfileCameraConfigActivity`) —
 * this screen only creates/applies/renames/deletes profiles, it doesn't edit their
 * contents directly. Applying a profile (see [ProfileStore.activateProfile]) makes it the
 * one [com.example.karooinsta360.extension.Insta360Extension] drives automation from —
 * switching between e.g. a "Road" and a "Gravel race" profile reconfigures the whole
 * fleet's trigger behavior in one tap.
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
    private lateinit var dataSourceLossTimeoutInput: EditText
    private lateinit var saveDataSourceLossTimeoutButton: Button
    private lateinit var activeProfileText: TextView
    private lateinit var noProfilesText: TextView
    private lateinit var profileListContainer: LinearLayout
    private lateinit var saveNewProfileButton: Button

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
        dataSourceLossTimeoutInput = findViewById(R.id.dataSourceLossTimeoutInput)
        saveDataSourceLossTimeoutButton = findViewById(R.id.saveDataSourceLossTimeoutButton)
        activeProfileText = findViewById(R.id.activeProfileText)
        noProfilesText = findViewById(R.id.noProfilesText)
        profileListContainer = findViewById(R.id.profileListContainer)
        saveNewProfileButton = findViewById(R.id.saveNewProfileButton)

        scanForCamerasButton.setOnClickListener { requestPermissionsAndScan() }
        manualAddButton.setOnClickListener { addByAddress() }
        saveNewProfileButton.setOnClickListener { promptSaveNewProfile() }

        notifyCheckbox.isChecked = AppSettings.isRecordingNotificationEnabled(this)
        notifyCheckbox.setOnCheckedChangeListener { _, checked ->
            AppSettings.setRecordingNotificationEnabled(this, checked)
            if (checked) requestNotificationPermissionIfNeeded()
        }

        dataSourceLossTimeoutInput.setText(AppSettings.getDataSourceLossTimeoutMinutes(this).toString())
        saveDataSourceLossTimeoutButton.setOnClickListener { saveDataSourceLossTimeout() }

        Insta360ConnectionManager.ensureStarted(this)
        Insta360ConnectionManager.addListener(connectionListener)
    }

    override fun onResume() {
        super.onResume()
        refreshCameraList() // picks up name/config edits made in CameraConfigActivity
        refreshProfileList()

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
                text = "Reconnect"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
                setOnClickListener {
                    Insta360ConnectionManager.reconnect(this@MainActivity, state.address)
                    Toast.makeText(this@MainActivity, "Reconnecting…", Toast.LENGTH_SHORT).show()
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

    // --- Configuration profiles ---
    //
    // See ProfileStore's doc comment for the full model — a profile owns both *which*
    // cameras it considers (ProfileActivity) and their trigger settings
    // (ProfileCameraConfigActivity, reached from there); this screen only creates/applies/
    // renames/deletes whole profiles. Rows here mirror buildCameraRow's pattern (a title
    // line, then a row of equal-weight action buttons) split across two button rows rather
    // than one, since four buttons in a single row don't comfortably fit the Karoo's
    // narrow screen the way the camera list's three already do.

    private fun refreshProfileList() {
        val profiles = ProfileStore.getProfiles(this)
        val activeId = ProfileStore.getActiveProfileId(this)
        noProfilesText.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        activeProfileText.text = "Active profile: " + (profiles.find { it.id == activeId }?.name ?: "none")
        profileListContainer.removeAllViews()
        profiles.forEach { profile -> profileListContainer.addView(buildProfileRow(profile, activeId)) }
    }

    private fun buildProfileRow(profile: ProfileStore.Profile, activeId: String?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val isActive = profile.id == activeId
        row.addView(
            TextView(this).apply {
                text = if (isActive) "${profile.name} (active)" else profile.name
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        row.addView(
            TextView(this).apply {
                text = "${profile.activeCameraAddresses.size} camera(s) active in this profile"
                textSize = 12f
            },
        )

        val topButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        topButtonRow.addView(
            Button(this).apply {
                text = "Apply"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { applyProfile(profile) }
            },
        )
        topButtonRow.addView(
            Button(this).apply {
                text = "Configure"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
                setOnClickListener { openProfile(profile.id) }
            },
        )
        row.addView(topButtonRow)

        val bottomButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        bottomButtonRow.addView(
            Button(this).apply {
                text = "Rename"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { promptRenameProfile(profile) }
            },
        )
        bottomButtonRow.addView(
            Button(this).apply {
                text = "Delete"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
                setOnClickListener { promptDeleteProfile(profile) }
            },
        )
        row.addView(bottomButtonRow)

        return row
    }

    private fun openProfile(profileId: String) {
        startActivity(
            Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_PROFILE_ID, profileId),
        )
    }

    /** Makes [profile] the one automation is driven from — see [ProfileStore.activateProfile]. */
    private fun applyProfile(profile: ProfileStore.Profile) {
        ProfileStore.activateProfile(this, profile.id)
        refreshProfileList()
        val count = profile.activeCameraAddresses.size
        val message = if (count == 0) {
            "Applied '${profile.name}' — it has no active cameras yet, so nothing will auto-record. Use Configure to add some."
        } else {
            "Applied '${profile.name}' — driving automation for $count camera(s)"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * New profile starts from every currently-saved camera, switched on, seeded with the
     * currently-active profile's settings where it covers the same camera (so a new
     * profile begins from a known baseline instead of every trigger reset to defaults) —
     * see [ProfileStore.createProfile]. Jumps straight into [ProfileActivity] afterward
     * since a brand new profile is exactly when reviewing/adjusting per-camera settings is
     * most useful.
     */
    private fun promptSaveNewProfile() {
        val cameras = CameraStore.getCameras(this)
        if (cameras.isEmpty()) {
            Toast.makeText(this, "Add a camera first", Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this).apply { hint = "Profile name (e.g. Road, Gravel race)" }
        AlertDialog.Builder(this)
            .setTitle("Create New Profile")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Enter a profile name", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val profile = ProfileStore.createProfile(this, name, seedFromProfileId = ProfileStore.getActiveProfileId(this))
                refreshProfileList()
                openProfile(profile.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRenameProfile(profile: ProfileStore.Profile) {
        val input = EditText(this).apply { setText(profile.name) }
        AlertDialog.Builder(this)
            .setTitle("Rename Profile")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Enter a profile name", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                ProfileStore.renameProfile(this, profile.id, name)
                refreshProfileList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDeleteProfile(profile: ProfileStore.Profile) {
        AlertDialog.Builder(this)
            .setTitle("Delete '${profile.name}'?")
            .setMessage("This only deletes the saved profile — it doesn't change any camera's current settings.")
            .setPositiveButton("Delete") { _, _ ->
                ProfileStore.deleteProfile(this, profile.id)
                refreshProfileList()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    // --- Data source loss timeout ---
    //
    // See AppSettings.getDataSourceLossTimeoutMinutes's doc comment for the full picture:
    // an app-wide (not per-camera/profile) minutes value Insta360Extension's monitor reads
    // every tick, so a change here takes effect on the next ~1s tick for any camera
    // currently recording — no restart, resync, or reapplying a profile needed.

    /**
     * Explicit Save button rather than saving on every keystroke — a half-typed number
     * (or a briefly-empty field mid-edit) would otherwise write a bogus value before the
     * user's done, same reasoning as [CameraConfigActivity][com.example.karooinsta360.camera.CameraConfigActivity]'s
     * own Save button for a camera's name.
     */
    private fun saveDataSourceLossTimeout() {
        val minutes = dataSourceLossTimeoutInput.text.toString().trim().toIntOrNull()
        if (minutes == null || minutes < 0) {
            Toast.makeText(this, "Enter 0 or a positive number of minutes", Toast.LENGTH_LONG).show()
            dataSourceLossTimeoutInput.setText(AppSettings.getDataSourceLossTimeoutMinutes(this).toString())
            return
        }
        AppSettings.setDataSourceLossTimeoutMinutes(this, minutes)
        dataSourceLossTimeoutInput.setText(minutes.toString())
        val message = if (minutes == 0) {
            "Saved — data source loss will never auto-stop a recording"
        } else {
            "Saved — a lost data source will auto-stop its recording after ${minutes}min"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
