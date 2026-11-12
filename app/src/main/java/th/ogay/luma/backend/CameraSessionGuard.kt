package th.ogay.luma.backend

/**
 * Makes sure only one backend holds the camera at a time. A backend must [acquire] before it opens
 * a camera and [release] after its session is fully closed. Switching backends therefore always
 * means: old backend closes and releases, then the new one acquires.
 */
class CameraSessionGuard {
    private var owner: CameraBackendKind? = null

    /** True if [kind] now owns the camera (or already did). False if another backend holds it. */
    @Synchronized fun acquire(kind: CameraBackendKind): Boolean {
        val current = owner
        if (current != null && current != kind) return false
        owner = kind
        return true
    }

    /** Releases only if [kind] is the owner, so a late callback from an old backend cannot free a new session. */
    @Synchronized fun release(kind: CameraBackendKind): Boolean {
        if (owner != kind) return false
        owner = null
        return true
    }

    @Synchronized fun owner(): CameraBackendKind? = owner

    companion object {
        /** Process-wide guard used by the camera screens. */
        val shared = CameraSessionGuard()
    }
}
