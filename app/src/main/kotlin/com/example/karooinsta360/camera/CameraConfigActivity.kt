package com.example.karooinsta360.camera

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.karooinsta360.R
import com.example.karooinsta360.RecordingReason
import com.example.karooinsta360.connection.Insta360ConnectionManager

/**
 * Per-camera **identity** screen: rename, remove, and manually test (Start/Stop).
 *
 * **Changed (2026-08-29, build 0.1.9):** this used to also be where all four trigger
 * metrics (heart rate/power/speed/radar) got configured. That configuration now lives
 * entirely in [ProfileStore] instead — see [ProfileActivity]/[ProfileCameraConfigActivity]
 * — since a camera's trigger behavior is a property of whichever profile currently has it
 * switched on, not of the camera itself. This screen only knows about the camera's
 * identity (address/name) and its live connection/recording status.
 *
 * While this screen is visible it pauses the extension's automatic trigger monitor for
 * this camera (see [Insta360ConnectionManager.pauseAutomation]) so the manual Start/Stop
 * buttons aren't immediately overridden by it.
 *
 * Launched from [com.example.karooinsta360.MainActivity] with [EXTRA_ADDRESS] set.
 */
class CameraConfigActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ADDRESS = "address"
    }

    private lateinit var address: String

    private lateinit var addressText: TextView
    private lateinit var nameInput: EditText
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button
    private lateinit var removeButton: Button

    private val connectionListener = object : Insta360ConnectionManager.Listener {
        override fun onCameraStateChanged(changedAddress: String) {
            if (changedAddress == address) runOnUiThread { updateStatusText() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_config)

        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run {
            finish()
            return
        }

        addressText = findViewById(R.id.configAddressText)
        nameInput = findViewById(R.id.configNameInput)
        statusText = findViewById(R.id.configStatusText)
        startButton = findViewById(R.id.configStartButton)
        stopButton = findViewById(R.id.configStopButton)
        saveButton = findViewById(R.id.saveConfigButton)
        removeButton = findViewById(R.id.removeCameraButton)

        addressText.text = address
        startButton.setOnClickListener {
            Insta360ConnectionManager.startCapture(
                address,
                Insta360ConnectionManager.RecordingOwner.MANUAL,
                reason = RecordingReason.Manual(RecordingReason.Manual.Source.CONFIG_SCREEN),
            )
        }
        stopButton.setOnClickListener {
            Insta360ConnectionManager.stopCapture(
                address,
                reason = RecordingReason.Manual(RecordingReason.Manual.Source.CONFIG_SCREEN),
            )
        }
        saveButton.setOnClickListener { saveName() }
        removeButton.setOnClickListener { removeCamera() }

        loadCamera()
        Insta360ConnectionManager.addListener(connectionListener)
        updateStatusText()
    }

    override fun onResume() {
        super.onResume()
        // The extension's automatic heart-rate/power/speed/radar monitor for this camera
        // keeps running in the background the whole time (it's a separate Service, not
        // tied to this screen), polling every second and forcing actual recording state to
        // match whatever its own thresholds compute. Without this, pressing Start here
        // would start a recording that the monitor then immediately stops again on its
        // very next tick (since nothing about a manual test button press changes its
        // computed desired state) — recordings only ever lasting a fraction of a second.
        // Pausing automation while this screen is visible lets the manual buttons actually
        // mean "start"/"stop", not "start, then get overridden within ~1 second."
        Insta360ConnectionManager.pauseAutomation(address)
    }

    override fun onPause() {
        // Just re-arms the automatic triggers to watch this camera again — it's not
        // what protects a manually-started recording from being auto-stopped (that's
        // recording ownership — see Insta360ConnectionManager.RecordingOwner — which
        // lasts for the whole recording regardless of whether this screen is ever opened).
        Insta360ConnectionManager.resumeAutomation(address)
        super.onPause()
    }

    override fun onDestroy() {
        Insta360ConnectionManager.removeListener(connectionListener)
        super.onDestroy()
    }

    private fun updateStatusText() {
        val connected = Insta360ConnectionManager.isConnected(address)
        val recording = Insta360ConnectionManager.isRecording(address)
        statusText.text = when {
            !connected -> "Disconnected"
            recording -> "Connected — Recording"
            else -> "Connected — Idle"
        }
    }

    private fun loadCamera() {
        val camera = CameraStore.getCamera(this, address) ?: CameraStore.CameraConfig(address = address, name = address)
        nameInput.setText(camera.name)
    }

    private fun saveName() {
        val name = nameInput.text.toString().trim().ifEmpty { address }
        CameraStore.addOrUpdateCamera(this, CameraStore.CameraConfig(address = address, name = name))
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun removeCamera() {
        Insta360ConnectionManager.removeCamera(this, address)
        Toast.makeText(this, "Camera removed", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent())
        finish()
    }
}
