package th.ogay.luma.autoedit.core

/** Events are hypotheses with evidence, not proof of a gameplay kill. */
enum class AnalysisEventType {
    SCENE_CHANGE, SPEECH, HIGH_ACTIVITY, POSSIBLE_KILL, CONFIRMED_KILL,
    MULTIKILL, CLUTCH, VICTORY, UNKNOWN
}

data class EventEvidence(
    val kind: String,
    val sourceUs: Long,
    val confidence: Double,
    val explanation: String = ""
) {
    init {
        require(kind.isNotBlank())
        require(sourceUs >= 0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class AnalysisEvent(
    val id: String,
    val assetId: String,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val type: AnalysisEventType,
    val importance: Double,
    val confidence: Double,
    val detectorVersion: String,
    val evidence: List<EventEvidence> = emptyList(),
    val gameProfileId: String? = null
) {
    init {
        require(id.isNotBlank() && assetId.isNotBlank())
        require(sourceStartUs >= 0 && sourceEndUs > sourceStartUs)
        require(importance.isFinite() && importance in 0.0..1.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(detectorVersion.isNotBlank())
    }
}
