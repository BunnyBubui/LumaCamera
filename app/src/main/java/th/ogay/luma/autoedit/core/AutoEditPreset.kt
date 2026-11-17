package th.ogay.luma.autoedit.core

/**
 * Snapshot of the user's Auto Edit intent. Values 0.0..1.0 control editorial
 * intensity; they are NOT event-detection confidence values.
 *
 * Phase 1 does not expose or run this feature in the existing editor.
 */
data class AutoEditPreset(
    val schemaVersion: Int = 1,
    val presetId: String = GAMING_PRESET_ID,
    val mode: String = "gaming",
    val editingEnergy: Double = 0.65,
    val cameraMotion: Double = 0.45,
    val soundEffects: Double = 0.60,
    val preserveSourceAudio: Boolean = true,
    val analysisPrivacy: String = "on_device",
    val timelineOutput: String = "editable",
    val automaticPublish: Boolean = false,
    val featureFlag: String = AutoEditFeatureFlags.KEY
) {
    init {
        require(schemaVersion == 1) { "Unsupported Auto Edit preset schema" }
        require(presetId.isNotBlank() && mode.isNotBlank())
        require(editingEnergy.isFinite() && editingEnergy in 0.0..1.0)
        require(cameraMotion.isFinite() && cameraMotion in 0.0..1.0)
        require(soundEffects.isFinite() && soundEffects in 0.0..1.0)
        require(analysisPrivacy == "on_device") { "Phase 1 only supports on-device analysis" }
        require(timelineOutput == "editable")
        require(!automaticPublish) { "Auto publishing must be explicitly designed and consented to" }
        require(featureFlag == AutoEditFeatureFlags.KEY)
    }

    companion object {
        const val GAMING_PRESET_ID = "gaming_highlight_v2"
        fun gamingDefault() = AutoEditPreset()
    }
}

/** Keep the not-yet-integrated Phase 1 architecture completely dormant. */
object AutoEditFeatureFlags {
    const val KEY = "autoedit_v2"
    const val ENABLED = false
}
