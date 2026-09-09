package com.example.karooinsta360.camera

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.karooinsta360.R

/**
 * Per-profile camera list (2026-08-29, build 0.1.9) — lists every camera saved in
 * [CameraStore], each with a checkbox for whether this profile currently considers it at
 * all ([ProfileStore.Profile.activeCameraAddresses]) and, for one that's switched on, a
 * "Configure" button into [ProfileCameraConfigActivity] to edit that camera's actual
 * trigger settings within this profile.
 *
 * This is the "what cameras are active in each profile" half of profile management — the
 * other half (the trigger values themselves) is [ProfileCameraConfigActivity]. Launched
 * from [com.example.karooinsta360.MainActivity]'s "Configure" button on a profile row.
 */
class ProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    private lateinit var profileId: String
    private lateinit var titleText: TextView
    private lateinit var noCamerasText: TextView
    private lateinit var cameraListContainer: LinearLayout
    private lateinit var karooProfileNameEdit: EditText
    private lateinit var karooProfileNameSaveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: run { finish(); return }

        titleText = findViewById(R.id.profileTitleText)
        noCamerasText = findViewById(R.id.profileNoCamerasText)
        cameraListContainer = findViewById(R.id.profileCameraListContainer)
        karooProfileNameEdit = findViewById(R.id.profileKarooProfileNameEdit)
        karooProfileNameSaveButton = findViewById(R.id.profileKarooProfileNameSaveButton)

        karooProfileNameSaveButton.setOnClickListener { saveKarooProfileName() }
    }

    /**
     * **Added (2026-09-08)** — see [ProfileStore.setKarooProfileName]. A blank field
     * clears the mapping (always allowed); a name already used by another profile is
     * rejected with a Toast naming which one, rather than silently handed to it.
     */
    private fun saveKarooProfileName() {
        val typed = karooProfileNameEdit.text.toString()
        val conflict = typed.trim().takeUnless { it.isEmpty() }?.let { trimmed ->
            ProfileStore.getProfiles(this).find {
                it.id != profileId && it.karooProfileName?.trim()?.equals(trimmed, ignoreCase = true) == true
            }
        }
        if (conflict != null) {
            Toast.makeText(this, "Already assigned to '${conflict.name}'", Toast.LENGTH_LONG).show()
            return
        }
        val saved = ProfileStore.setKarooProfileName(this, profileId, typed)
        if (saved) {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // picks up name/setting edits made in ProfileCameraConfigActivity or elsewhere
    }

    private fun refresh() {
        val profile = ProfileStore.getProfile(this, profileId)
        if (profile == null) {
            finish() // profile was deleted from under us
            return
        }
        titleText.text = profile.name
        // Only overwrite the field if it's not already showing what's saved — avoids
        // clobbering an in-progress edit if refresh() ever runs while the field has
        // unsaved focus (e.g. a future onResume path), and keeps the cursor position
        // stable on the ordinary case where it just matches after a successful save.
        val savedName = profile.karooProfileName.orEmpty()
        if (karooProfileNameEdit.text.toString() != savedName) {
            karooProfileNameEdit.setText(savedName)
        }

        val cameras = CameraStore.getCameras(this)
        noCamerasText.visibility = if (cameras.isEmpty()) View.VISIBLE else View.GONE
        cameraListContainer.removeAllViews()
        cameras.forEach { camera ->
            cameraListContainer.addView(buildCameraRow(profile, camera))
        }
    }

    private fun buildCameraRow(profile: ProfileStore.Profile, camera: CameraStore.CameraConfig): View {
        val included = camera.address in profile.activeCameraAddresses

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val checkbox = CheckBox(this).apply {
            text = camera.displayLabel
            isChecked = included
            setOnCheckedChangeListener { _, checked ->
                ProfileStore.setCameraIncluded(this@ProfileActivity, profile.id, camera.address, checked)
                refresh()
            }
        }
        row.addView(checkbox)

        if (included) {
            row.addView(
                Button(this).apply {
                    text = "Configure Triggers"
                    setPadding(paddingLeft, dp(4), paddingRight, paddingBottom)
                    setOnClickListener {
                        startActivity(
                            Intent(this@ProfileActivity, ProfileCameraConfigActivity::class.java)
                                .putExtra(ProfileCameraConfigActivity.EXTRA_PROFILE_ID, profile.id)
                                .putExtra(ProfileCameraConfigActivity.EXTRA_ADDRESS, camera.address),
                        )
                    }
                },
            )
        } else {
            row.addView(
                TextView(this).apply {
                    text = "Not part of this profile — its automatic triggers stay off while this profile is active."
                    textSize = 12f
                    setTypeface(typeface, Typeface.ITALIC)
                },
            )
        }

        return row
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
