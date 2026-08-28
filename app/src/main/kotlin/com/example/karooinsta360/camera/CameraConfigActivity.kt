package com.example.karooinsta360.camera

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.karooinsta360.R
import com.example.karooinsta360.connection.Insta360ConnectionManager

/**
 * Per-camera settings screen: rename, remove, manually test (Start/Stop), and configure
 * the four independent trigger metrics (see [Insta360Extension] for how heart rate/
 * power/speed/radar combine — this screen just edits the numbers).
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

    private lateinit var hrEnabled: CheckBox
    private lateinit var hrStartThreshold: EditText
    private lateinit var hrStopThreshold: EditText
    private lateinit var hrStartSeconds: EditText
    private lateinit var hrStopSeconds: EditText

    private lateinit var powerEnabled: CheckBox
    private lateinit var powerStartThreshold: EditText
    private lateinit var powerStopThreshold: EditText
    private lateinit var powerStartSeconds: EditText
    private lateinit var powerStopSeconds: EditText

    private lateinit var speedEnabled: CheckBox
    private lateinit var speedStartThreshold: EditText
    private lateinit var speedStopThreshold: EditText
    private lateinit var speedStartSeconds: EditText
    private lateinit var speedStopSeconds: EditText

    // Radar has no separate stop threshold — see readRadarTrigger()/the layout's Radar
    // section for why (its stop condition is "no vehicle on radar," not a crossed value).
    private lateinit var radarEnabled: CheckBox
    private lateinit var radarStartThreshold: EditText
    private lateinit var radarStartSeconds: EditText
    private lateinit var radarStopSeconds: EditText

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

        hrEnabled = findViewById(R.id.hrEnabledCheckbox)
        hrStartThreshold = findViewById(R.id.hrStartThresholdInput)
        hrStopThreshold = findViewById(R.id.hrStopThresholdInput)
        hrStartSeconds = findViewById(R.id.hrStartSecondsInput)
        hrStopSeconds = findViewById(R.id.hrStopSecondsInput)

        powerEnabled = findViewById(R.id.powerEnabledCheckbox)
        powerStartThreshold = findViewById(R.id.powerStartThresholdInput)
        powerStopThreshold = findViewById(R.id.powerStopThresholdInput)
        powerStartSeconds = findViewById(R.id.powerStartSecondsInput)
        powerStopSeconds = findViewById(R.id.powerStopSecondsInput)

        speedEnabled = findViewById(R.id.speedEnabledCheckbox)
        speedStartThreshold = findViewById(R.id.speedStartThresholdInput)
        speedStopThreshold = findViewById(R.id.speedStopThresholdInput)
        speedStartSeconds = findViewById(R.id.speedStartSecondsInput)
        speedStopSeconds = findViewById(R.id.speedStopSecondsInput)

        radarEnabled = findViewById(R.id.radarEnabledCheckbox)
        radarStartThreshold = findViewById(R.id.radarThresholdInput)
        radarStartSeconds = findViewById(R.id.radarStartSecondsInput)
        radarStopSeconds = findViewById(R.id.radarStopSecondsInput)

        saveButton = findViewById(R.id.saveConfigButton)
        removeButton = findViewById(R.id.removeCameraButton)

        addressText.text = address
        startButton.setOnClickListener {
            Insta360ConnectionManager.startCapture(address, Insta360ConnectionManager.RecordingOwner.MANUAL)
        }
        stopButton.setOnClickListener { Insta360ConnectionManager.stopCapture(address) }
        saveButton.setOnClickListener { saveConfig() }
        removeButton.setOnClickListener { removeCamera() }

        loadConfig()
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
        // Also the only place a camera left paused by the Karoo Control Center
        // notification, the ride-page tile, or the controller-button BonusAction gets
        // its pause cleared — those three are a single tap/press with no "screen
        // closing" moment of their own (see Insta360ConnectionManager.
        // startAllCameras/stopAllCameras). This only re-arms the automatic triggers to
        // watch this camera again; it's not what protects a manually-started recording
        // from being auto-stopped (that's recording ownership — see
        // Insta360ConnectionManager.RecordingOwner — which lasts for the whole
        // recording regardless of whether this screen is ever opened).
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

    private fun loadConfig() {
        val config = CameraStore.getCamera(this, address) ?: CameraStore.CameraConfig(address = address, name = address)
        nameInput.setText(config.name)

        hrEnabled.isChecked = config.heartRate.enabled
        hrStartThreshold.setText(formatFloat(config.heartRate.startThreshold))
        hrStopThreshold.setText(formatFloat(config.heartRate.stopThreshold))
        hrStartSeconds.setText(config.heartRate.startSeconds.toString())
        hrStopSeconds.setText(config.heartRate.stopSeconds.toString())

        powerEnabled.isChecked = config.power.enabled
        powerStartThreshold.setText(formatFloat(config.power.startThreshold))
        powerStopThreshold.setText(formatFloat(config.power.stopThreshold))
        powerStartSeconds.setText(config.power.startSeconds.toString())
        powerStopSeconds.setText(config.power.stopSeconds.toString())

        speedEnabled.isChecked = config.speed.enabled
        speedStartThreshold.setText(formatFloat(config.speed.startThreshold))
        speedStopThreshold.setText(formatFloat(config.speed.stopThreshold))
        speedStartSeconds.setText(config.speed.startSeconds.toString())
        speedStopSeconds.setText(config.speed.stopSeconds.toString())

        radarEnabled.isChecked = config.radar.enabled
        radarStartThreshold.setText(formatFloat(config.radar.startThreshold))
        radarStartSeconds.setText(config.radar.startSeconds.toString())
        radarStopSeconds.setText(config.radar.stopSeconds.toString())
    }

    private fun formatFloat(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

    /** Heart Rate / Power / Speed: an independent start value and stop value. */
    private fun readTrigger(
        enabled: CheckBox,
        startThreshold: EditText,
        stopThreshold: EditText,
        startSeconds: EditText,
        stopSeconds: EditText,
        label: String,
    ): CameraStore.MetricTrigger? {
        if (!enabled.isChecked) return CameraStore.MetricTrigger(enabled = false)

        val start = startThreshold.text.toString().toFloatOrNull()
        val stop = stopThreshold.text.toString().toFloatOrNull()
        val startSec = startSeconds.text.toString().toIntOrNull()
        val stopSec = stopSeconds.text.toString().toIntOrNull()
        if (start == null || stop == null || startSec == null || stopSec == null ||
            start <= 0f || stop <= 0f || startSec < 0 || stopSec < 0
        ) {
            Toast.makeText(
                this,
                "$label: enter valid start/stop values and non-negative durations",
                Toast.LENGTH_LONG,
            ).show()
            return null
        }
        return CameraStore.MetricTrigger(
            enabled = true,
            startThreshold = start,
            stopThreshold = stop,
            startSeconds = startSec,
            stopSeconds = stopSec,
        )
    }

    /**
     * Radar: only a start distance — there's no separate stop threshold to read, since
     * stop means "no vehicle detected" rather than a second distance (see
     * [Insta360Extension]'s radar latch). [startThreshold] is stored in both
     * [CameraStore.MetricTrigger.startThreshold] and `.stopThreshold` purely so the field
     * is never left at a stale/meaningless value — the stop side of the code never reads
     * it.
     */
    private fun readRadarTrigger(
        enabled: CheckBox,
        startThreshold: EditText,
        startSeconds: EditText,
        stopSeconds: EditText,
    ): CameraStore.MetricTrigger? {
        if (!enabled.isChecked) return CameraStore.MetricTrigger(enabled = false)

        val distance = startThreshold.text.toString().toFloatOrNull()
        val startSec = startSeconds.text.toString().toIntOrNull()
        val stopSec = stopSeconds.text.toString().toIntOrNull()
        if (distance == null || startSec == null || stopSec == null || distance <= 0f || startSec < 0 || stopSec < 0) {
            Toast.makeText(
                this,
                "Radar: enter a valid trigger distance and non-negative durations",
                Toast.LENGTH_LONG,
            ).show()
            return null
        }
        return CameraStore.MetricTrigger(
            enabled = true,
            startThreshold = distance,
            stopThreshold = distance,
            startSeconds = startSec,
            stopSeconds = stopSec,
        )
    }

    private fun saveConfig() {
        val name = nameInput.text.toString().trim().ifEmpty { address }

        val hr = readTrigger(hrEnabled, hrStartThreshold, hrStopThreshold, hrStartSeconds, hrStopSeconds, "Heart Rate")
            ?: return
        val power = readTrigger(powerEnabled, powerStartThreshold, powerStopThreshold, powerStartSeconds, powerStopSeconds, "Power")
            ?: return
        val speed = readTrigger(speedEnabled, speedStartThreshold, speedStopThreshold, speedStartSeconds, speedStopSeconds, "Speed")
            ?: return
        val radar = readRadarTrigger(radarEnabled, radarStartThreshold, radarStartSeconds, radarStopSeconds)
            ?: return

        CameraStore.addOrUpdateCamera(
            this,
            CameraStore.CameraConfig(
                address = address,
                name = name,
                heartRate = hr,
                power = power,
                speed = speed,
                radar = radar,
            ),
        )
        Toast.makeText(this, "Saved — takes effect immediately", Toast.LENGTH_SHORT).show()
    }

    private fun removeCamera() {
        Insta360ConnectionManager.removeCamera(this, address)
        Toast.makeText(this, "Camera removed", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent())
        finish()
    }
}
