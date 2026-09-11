package com.example.karooinsta360.camera

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.karooinsta360.R

/**
 * Trigger configuration for **one camera within one profile** (2026-08-29, build 0.1.9).
 *
 * This is where heart rate/power/speed/radar actually get configured now — moved here
 * from `CameraConfigActivity` per explicit request that triggers belong to the profile,
 * not the camera. Everything typed here is saved into [ProfileStore] against
 * ([EXTRA_PROFILE_ID], [EXTRA_ADDRESS]) via [ProfileStore.updateCameraSettings] — it never
 * touches [CameraStore] at all. If [EXTRA_PROFILE_ID]'s profile happens to be the one
 * currently active, [com.example.karooinsta360.extension.Insta360Extension] picks up the
 * change immediately (it listens for any [ProfileStore] change).
 *
 * Launched from [ProfileActivity] with both extras set, for a camera [ProfileActivity]
 * already confirmed is switched on in this profile.
 */
class ProfileCameraConfigActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_ADDRESS = "address"
    }

    private lateinit var profileId: String
    private lateinit var address: String

    private lateinit var headerText: TextView

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
    private lateinit var powerStopAllowedSpikes: EditText

    private lateinit var speedEnabled: CheckBox
    private lateinit var speedUnitGroup: RadioGroup
    private lateinit var speedStartThreshold: EditText
    private lateinit var speedStopThreshold: EditText
    private lateinit var speedStartSeconds: EditText
    private lateinit var speedStopSeconds: EditText

    // Radar has no separate stop threshold — see readRadarTrigger()/the layout's Radar
    // section for why (its stop condition is "no vehicle on radar," not a crossed value).
    private lateinit var batteryFloorEnabled: CheckBox
    private lateinit var batteryFloorPercent: EditText

    private lateinit var radarEnabled: CheckBox
    private lateinit var radarUnitGroup: RadioGroup
    private lateinit var radarStartThreshold: EditText
    private lateinit var radarStartSeconds: EditText
    private lateinit var radarStopSeconds: EditText

    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_camera_config)

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: run { finish(); return }
        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run { finish(); return }

        headerText = findViewById(R.id.profileCameraHeaderText)

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
        powerStopAllowedSpikes = findViewById(R.id.powerStopAllowedSpikesInput)

        speedEnabled = findViewById(R.id.speedEnabledCheckbox)
        speedUnitGroup = findViewById(R.id.speedUnitGroup)
        speedStartThreshold = findViewById(R.id.speedStartThresholdInput)
        speedStopThreshold = findViewById(R.id.speedStopThresholdInput)
        speedStartSeconds = findViewById(R.id.speedStartSecondsInput)
        speedStopSeconds = findViewById(R.id.speedStopSecondsInput)

        radarEnabled = findViewById(R.id.radarEnabledCheckbox)
        radarUnitGroup = findViewById(R.id.radarUnitGroup)
        radarStartThreshold = findViewById(R.id.radarThresholdInput)
        radarStartSeconds = findViewById(R.id.radarStartSecondsInput)
        radarStopSeconds = findViewById(R.id.radarStopSecondsInput)

        batteryFloorEnabled = findViewById(R.id.batteryFloorCheckbox)
        batteryFloorPercent = findViewById(R.id.batteryFloorInput)

        saveButton = findViewById(R.id.saveProfileCameraConfigButton)
        saveButton.setOnClickListener { saveConfig() }

        loadConfig()
    }

    private fun loadConfig() {
        val profile = ProfileStore.getProfile(this, profileId)
        val camera = CameraStore.getCamera(this, address)
        headerText.text = "${profile?.name ?: "Profile"} — ${camera?.name ?: address}"

        val settings = profile?.cameraSettings?.get(address) ?: ProfileStore.ProfileCameraSettings.DEFAULT

        hrEnabled.isChecked = settings.heartRate.enabled
        hrStartThreshold.setText(formatFloat(settings.heartRate.startThreshold))
        hrStopThreshold.setText(formatFloat(settings.heartRate.stopThreshold))
        hrStartSeconds.setText(settings.heartRate.startSeconds.toString())
        hrStopSeconds.setText(settings.heartRate.stopSeconds.toString())

        powerEnabled.isChecked = settings.power.enabled
        powerStartThreshold.setText(formatFloat(settings.power.startThreshold))
        powerStopThreshold.setText(formatFloat(settings.power.stopThreshold))
        powerStartSeconds.setText(settings.power.startSeconds.toString())
        powerStopSeconds.setText(settings.power.stopSeconds.toString())
        powerStopAllowedSpikes.setText(settings.powerStopAllowedSpikes.toString())

        speedEnabled.isChecked = settings.speed.enabled
        speedUnitGroup.check(
            if (settings.speedUnit == CameraStore.SpeedUnit.KMH) R.id.speedUnitKmh else R.id.speedUnitMph,
        )
        speedStartThreshold.setText(formatFloat(settings.speed.startThreshold))
        speedStopThreshold.setText(formatFloat(settings.speed.stopThreshold))
        speedStartSeconds.setText(settings.speed.startSeconds.toString())
        speedStopSeconds.setText(settings.speed.stopSeconds.toString())

        radarEnabled.isChecked = settings.radar.enabled
        radarUnitGroup.check(
            if (settings.radarUnit == CameraStore.DistanceUnit.METERS) R.id.radarUnitMeters else R.id.radarUnitFeet,
        )
        radarStartThreshold.setText(formatFloat(settings.radar.startThreshold))
        radarStartSeconds.setText(settings.radar.startSeconds.toString())
        radarStopSeconds.setText(settings.radar.stopSeconds.toString())

        batteryFloorEnabled.isChecked = settings.batteryFloorEnabled
        batteryFloorPercent.setText(settings.batteryFloorPercent.toString())
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
     * [com.example.karooinsta360.extension.Insta360Extension]'s radar latch).
     * [startThreshold] is stored in both [CameraStore.MetricTrigger.startThreshold] and
     * `.stopThreshold` purely so the field is never left at a stale/meaningless value —
     * the stop side of the code never reads it.
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
        val hr = readTrigger(hrEnabled, hrStartThreshold, hrStopThreshold, hrStartSeconds, hrStopSeconds, "Heart Rate")
            ?: return
        val power = readTrigger(powerEnabled, powerStartThreshold, powerStopThreshold, powerStartSeconds, powerStopSeconds, "Power")
            ?: return
        val speed = readTrigger(speedEnabled, speedStartThreshold, speedStopThreshold, speedStartSeconds, speedStopSeconds, "Speed")
            ?: return
        val radar = readRadarTrigger(radarEnabled, radarStartThreshold, radarStartSeconds, radarStopSeconds)
            ?: return

        // Validated even when the checkbox is off, so a nonsense value can't sit in the
        // field waiting to take effect the moment someone ticks it.
        val batteryFloor = batteryFloorPercent.text.toString().toIntOrNull()
        if (batteryFloor == null || batteryFloor !in 1..99) {
            Toast.makeText(
                this,
                "Low battery: level must be a whole number from 1 to 99",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val powerSpikes = powerStopAllowedSpikes.text.toString().toIntOrNull()
        if (powerSpikes == null || powerSpikes !in 0..5) {
            Toast.makeText(this, "Power: allowed spikes must be a whole number from 0 to 5", Toast.LENGTH_LONG).show()
            return
        }

        val speedUnit = if (speedUnitGroup.checkedRadioButtonId == R.id.speedUnitKmh) {
            CameraStore.SpeedUnit.KMH
        } else {
            CameraStore.SpeedUnit.MPH
        }
        val radarUnit = if (radarUnitGroup.checkedRadioButtonId == R.id.radarUnitMeters) {
            CameraStore.DistanceUnit.METERS
        } else {
            CameraStore.DistanceUnit.FEET
        }

        ProfileStore.updateCameraSettings(
            this,
            profileId,
            address,
            ProfileStore.ProfileCameraSettings(
                heartRate = hr,
                power = power,
                powerStopAllowedSpikes = powerSpikes,
                speed = speed,
                speedUnit = speedUnit,
                radar = radar,
                radarUnit = radarUnit,
                batteryFloorEnabled = batteryFloorEnabled.isChecked,
                batteryFloorPercent = batteryFloor,
            ),
        )
        Toast.makeText(
            this,
            "Saved — takes effect immediately if this profile is active",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
