package th.ogay.luma.autoedit.core

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uses a single String Bundle key already supported by StudioProjects' schema-1
 * storage. No existing project is rewritten or migrated during Phase 1.
 */
object AutoEditProjectAdapter {
    const val BUNDLE_KEY = "autoedit_v2_edl_json"
    private const val MAX_JSON_CHARS = 2_000_000

    sealed class Restore {
        object Missing : Restore()
        data class Loaded(val plan: EditDecisionList) : Restore()
        data class Invalid(val reason: String) : Restore()
    }

    fun attach(state: Bundle, plan: EditDecisionList): Bundle {
        val json = encode(plan)
        return Bundle(state).apply { putString(BUNDLE_KEY, json) }
    }

    fun restore(state: Bundle): Restore {
        val raw = state.getString(BUNDLE_KEY) ?: return Restore.Missing
        return try { Restore.Loaded(decode(raw)) }
        catch (e: Exception) { Restore.Invalid(e.message ?: "Invalid Auto Edit project") }
    }

    private fun <T> array(list: List<T>, encodeItem: (T) -> JSONObject): JSONArray =
        JSONArray().also { out -> list.forEach { out.put(encodeItem(it)) } }

    private fun <T> readArray(items: JSONArray, decodeItem: (JSONObject) -> T): List<T> =
        (0 until items.length()).map { decodeItem(items.getJSONObject(it)) }

    fun encode(plan: EditDecisionList): String {
        require(EdlValidator.validate(plan).isEmpty()) { "Cannot serialize invalid EDL" }
        val preset = plan.presetSnapshot
        val presetJson = JSONObject()
            .put("schemaVersion", preset.schemaVersion).put("presetId", preset.presetId)
            .put("mode", preset.mode).put("editingEnergy", preset.editingEnergy)
            .put("cameraMotion", preset.cameraMotion).put("soundEffects", preset.soundEffects)
            .put("preserveSourceAudio", preset.preserveSourceAudio)
            .put("analysisPrivacy", preset.analysisPrivacy)
            .put("timelineOutput", preset.timelineOutput)
            .put("automaticPublish", preset.automaticPublish).put("featureFlag", preset.featureFlag)
        val assets = array(plan.sourceAssets) {
            JSONObject().put("id", it.id).put("uri", it.uri).put("durationUs", it.durationUs)
                .put("videoWidth", it.videoWidth).put("videoHeight", it.videoHeight)
                .put("rotationDegrees", it.rotationDegrees)
        }
        val clips = array(plan.clips) { c ->
            JSONObject().put("id", c.id).put("sourceAssetId", c.sourceAssetId)
                .put("sourceInUs", c.sourceInUs).put("sourceOutUs", c.sourceOutUs)
                .put("outputStartUs", c.outputStartUs).put("reason", c.reason)
                .put("importance", c.importance).put("originalAudioPolicy", c.originalAudioPolicy)
                .put("speedSegments", array(c.speedSegments) {
                    JSONObject().put("sourceOffsetStartUs", it.sourceOffsetStartUs)
                        .put("sourceOffsetEndUs", it.sourceOffsetEndUs).put("rate", it.rate)
                }).put("motionKeyframes", array(c.motionKeyframes) {
                    JSONObject().put("outputOffsetUs", it.outputOffsetUs)
                        .put("centerX", it.centerX).put("centerY", it.centerY).put("scale", it.scale)
                })
        }
        val events = array(plan.analysisEvents) { ev ->
            JSONObject().put("id", ev.id).put("assetId", ev.assetId)
                .put("sourceStartUs", ev.sourceStartUs).put("sourceEndUs", ev.sourceEndUs)
                .put("type", ev.type.name).put("importance", ev.importance)
                .put("confidence", ev.confidence).put("detectorVersion", ev.detectorVersion)
                .put("gameProfileId", ev.gameProfileId)
                .put("evidence", array(ev.evidence) {
                    JSONObject().put("kind", it.kind).put("sourceUs", it.sourceUs)
                        .put("confidence", it.confidence).put("explanation", it.explanation)
                })
        }
        val cues = array(plan.sfxCues) {
            JSONObject().put("id", it.id).put("uri", it.uri)
                .put("outputStartUs", it.outputStartUs).put("gainDb", it.gainDb)
                .put("reason", it.reason)
        }
        val diagnostics = JSONObject().also { o -> plan.diagnostics.toSortedMap().forEach { (k,v) -> o.put(k,v) } }
        val root = JSONObject().put("schemaVersion", plan.schemaVersion)
            .put("planId", plan.planId).put("presetSnapshot", presetJson)
            .put("analysisVersion", plan.analysisVersion)
            .put("sourceAssets", assets).put("clips", clips)
            .put("analysisEvents", events).put("sfxCues", cues)
            .put("diagnostics", diagnostics).put("generationSeed", plan.generationSeed)
        val result = root.toString()
        require(result.length <= MAX_JSON_CHARS) { "Auto Edit metadata is too large" }
        return result
    }

    fun decode(raw: String): EditDecisionList {
        require(raw.length <= MAX_JSON_CHARS) { "Auto Edit metadata is too large" }
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == 1) { "Unsupported EDL schema" }
        val p = root.getJSONObject("presetSnapshot")
        val preset = AutoEditPreset(
            schemaVersion = p.getInt("schemaVersion"),
            presetId = p.getString("presetId"), mode = p.getString("mode"),
            editingEnergy = p.getDouble("editingEnergy"),
            cameraMotion = p.getDouble("cameraMotion"), soundEffects = p.getDouble("soundEffects"),
            preserveSourceAudio = p.getBoolean("preserveSourceAudio"),
            analysisPrivacy = p.getString("analysisPrivacy"),
            timelineOutput = p.getString("timelineOutput"),
            automaticPublish = p.getBoolean("automaticPublish"),
            featureFlag = p.getString("featureFlag")
        )
        val assets = readArray(root.getJSONArray("sourceAssets")) {
            MediaAssetRef(it.getString("id"), it.getString("uri"), it.getLong("durationUs"),
                it.getInt("videoWidth"), it.getInt("videoHeight"), it.getInt("rotationDegrees"))
        }
        val clips = readArray(root.getJSONArray("clips")) { c ->
            ClipDecision(c.getString("id"), c.getString("sourceAssetId"),
                c.getLong("sourceInUs"), c.getLong("sourceOutUs"),
                c.getLong("outputStartUs"), c.getString("reason"), c.getDouble("importance"),
                speedSegments = readArray(c.getJSONArray("speedSegments")) {
                    SpeedSegment(it.getLong("sourceOffsetStartUs"),
                        it.getLong("sourceOffsetEndUs"), it.getDouble("rate"))
                },
                motionKeyframes = readArray(c.getJSONArray("motionKeyframes")) {
                    MotionKeyframe(it.getLong("outputOffsetUs"),
                        it.getDouble("centerX"), it.getDouble("centerY"), it.getDouble("scale"))
                },
                originalAudioPolicy = c.getString("originalAudioPolicy"))
        }
        val events = readArray(root.getJSONArray("analysisEvents")) { ev ->
            AnalysisEvent(ev.getString("id"), ev.getString("assetId"),
                ev.getLong("sourceStartUs"), ev.getLong("sourceEndUs"),
                AnalysisEventType.valueOf(ev.getString("type")),
                ev.getDouble("importance"), ev.getDouble("confidence"),
                ev.getString("detectorVersion"),
                evidence = readArray(ev.getJSONArray("evidence")) {
                    EventEvidence(it.getString("kind"), it.getLong("sourceUs"),
                        it.getDouble("confidence"), it.getString("explanation"))
                },
                gameProfileId = ev.optString("gameProfileId").takeIf { it.isNotBlank() && it != "null" })
        }
        val cues = readArray(root.getJSONArray("sfxCues")) {
            SfxCue(it.getString("id"), it.getString("uri"),
                it.getLong("outputStartUs"), it.getDouble("gainDb"), it.getString("reason"))
        }
        val extras = root.getJSONObject("diagnostics")
        val diagnostics = extras.keys().asSequence().associateWith { extras.getString(it) }
        val plan = EditDecisionList(
            schemaVersion = root.getInt("schemaVersion"), planId = root.getString("planId"),
            presetSnapshot = preset, analysisVersion = root.getString("analysisVersion"),
            sourceAssets = assets, clips = clips, analysisEvents = events,
            sfxCues = cues, diagnostics = diagnostics, generationSeed = root.getLong("generationSeed")
        )
        val errors = EdlValidator.validate(plan)
        require(errors.isEmpty()) { "Invalid EDL: " + errors.joinToString { it.code } }
        return plan
    }
}
