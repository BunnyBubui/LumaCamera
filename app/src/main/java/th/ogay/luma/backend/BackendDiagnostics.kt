package th.ogay.luma.backend

/**
 * Small in-memory log of backend decisions. Only the whitelisted keys below are kept, so no file
 * names, locations, image content or other personal data can end up in it by accident.
 */
class BackendDiagnostics(private val capacity: Int = 60, private val clock: () -> Long = System::currentTimeMillis) {
    data class Event(val timeMs: Long, val what: String, val fields: Map<String, String>)

    private val events = ArrayDeque<Event>()

    @Synchronized fun record(what: String, fields: Map<String, Any?>) {
        val clean = LinkedHashMap<String, String>()
        for ((k, v) in fields) if (k in KEYS && v != null) clean[k] = v.toString().take(160)
        events.addLast(Event(clock(), what, clean))
        while (events.size > capacity) events.removeFirst()
    }

    @Synchronized fun events(): List<Event> = events.toList()

    @Synchronized fun text(): String = events.joinToString("\n") { e ->
        e.timeMs.toString() + " " + e.what + (if (e.fields.isEmpty()) "" else " " + e.fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    companion object {
        val KEYS = setOf(
            "manufacturer", "model", "android", "cameraId", "lens", "hardwareLevel", "cameraUnit",
            "features", "backend", "feature", "fallback", "resolution", "durationMs", "errorCode",
        )
        val shared = BackendDiagnostics()
    }
}
