package com.cyclone.mobile.mapping.crawl

/**
 * Agent-005's narrow consumption seam for the Run-2 mapping control plane.
 *
 * The implementation of this interface belongs to Agent 004. The crawler never owns mapping
 * session state, input leases, control revisions, or budgets; it only asks the authoritative
 * phone session for a snapshot and reports verified outcomes.
 */
interface MappingSessionPort {
    fun snapshot(): MappingSessionSnapshot
    fun reportCurrentNode(nodeKey: String)
    fun pauseNeedsSecret(reason: String)
    fun pauseHumanControl(reason: String)
    fun markDanger(doorKey: String, danger: MappingDanger)
    fun recordVerifiedProgress(newScreen: Boolean)
    fun recordNoProgress(doorKey: String)
    fun completePartial(reason: String)
    fun fail(reason: String)
}

data class MappingSessionSnapshot(
    val jobId: String,
    val placeId: String,
    val sessionId: String,
    val displayId: Int,
    val plane: String,
    val controlRevision: Long,
    val workspaceId: String? = null,
    val workspaceGeneration: Long? = null,
    val executionGeneration: Long? = null,
    val startedAtMs: Long,
    val budget: MappingBudget,
    val authority: MappingAuthority = MappingAuthority.OWNED,
    val newScreens: Int = 0,
    val consecutiveNonProgress: Int = 0,
)

data class MappingBudget(
    val maxNewScreens: Int,
    val maxElapsedMs: Long,
    val maxConsecutiveNonProgress: Int,
    val maxAttemptsPerDoor: Int,
) {
    init {
        require(maxNewScreens > 0)
        require(maxElapsedMs > 0)
        require(maxConsecutiveNonProgress > 0)
        require(maxAttemptsPerDoor > 0)
    }
}

enum class MappingAuthority {
    OWNED,
    SESSION_MISSING,
    DISPLAY_MISMATCH,
    HUMAN_CONTROL,
    COMPANION_CONTROL,
    PLANE_CHANGED,
    STALE_CONTROL_REVISION,
}

/**
 * Safe, structural observation projection. Adapters may derive it from AgentPageCard / gateway
 * observations, but ordinary user content must not be copied into these fields.
 */
data class MappingObservation(
    val observationId: String,
    val sessionId: String,
    val displayId: Int,
    val fingerprint: String,
    val structuralFingerprint: String,
    val fresh: Boolean,
    val purpose: StructuralScreenPurpose? = null,
    val doors: List<MappingDoor> = emptyList(),
)

enum class StructuralScreenPurpose {
    HOME,
    SETTINGS,
    ACCOUNT,
    SEARCH,
    MENU,
    LIST,
    DETAIL,
    LOGIN,
    PERMISSIONS,
    UNKNOWN,
}

enum class MappingDoorKind {
    TAB,
    MENU,
    NAV_DRAWER,
    SETTINGS,
    SEARCH,
    ACCOUNT,
    BACK,
    HOME,
    STRUCTURAL_SAMPLE,
    CONTENT_ROW,
    UNKNOWN,
}

data class MappingDoor(
    val elementId: String,
    val observationId: String,
    val key: String,
    val kind: MappingDoorKind,
    val regionKey: String? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
)

/**
 * Atlas hints are data only. There is deliberately no executable path/macro field.
 */
data class MappingAtlasHint(
    val currentNodeKey: String? = null,
    val knownDoorKeys: Set<String> = emptySet(),
    val darkDoorKeys: Set<String> = emptySet(),
)

data class VerifiedStructure(
    val fromNodeKey: String,
    val toNodeKey: String,
    val doorKey: String,
    val doorKind: MappingDoorKind,
    val beforeStructuralFingerprint: String,
    val afterStructuralFingerprint: String,
)

interface MappingObservationPort {
    /** Must return a newly captured observation for exactly the bound session/display. */
    fun freshObservation(session: MappingSessionSnapshot): MappingObservation?
}

interface MappingAtlasPort {
    /** Agent 005 always requests and writes the mapping persona. */
    fun hint(placeId: String, persona: String, observation: MappingObservation): MappingAtlasHint
    fun recordVerified(placeId: String, persona: String, structure: VerifiedStructure)
    fun markDanger(placeId: String, persona: String, nodeKey: String, doorKey: String, danger: MappingDanger)
    fun markPartial(placeId: String, persona: String, reason: String)
}

enum class MappingDanger {
    NONE,
    PAY,
    SEND_PUBLIC,
    DELETE,
    LOGOUT_ALL,
    GRANT,
    REVIEW_BOUNDARY,
}

/**
 * Production wiring must delegate to the existing GATE / boundary semantics. Agent 005 does not
 * invent a second payment/send/delete/grant classifier.
 */
interface MappingSafetyPort {
    /**
     * Production wiring must re-resolve [MappingDoor.elementId] against the exact current
     * observation and call the existing GateClassifier/grounded policy. Never infer safety from
     * the durable door key alone.
     */
    fun classify(observation: MappingObservation, door: MappingDoor): MappingDanger
}

data class MappingAction(
    val observationId: String,
    val elementId: String,
    val doorKey: String,
    val kind: MappingDoorKind,
)

data class MappingMutationResult(
    val performed: Boolean,
    val verifiedByExecutor: Boolean,
    val errorCode: String? = null,
)

/**
 * Production wiring must execute exactly one [MappingAction] through PhoneToolExecutor and return
 * only after its existing settle/grounding behavior. The crawler never batches actions.
 */
interface MappingMutationPort {
    fun execute(session: MappingSessionSnapshot, action: MappingAction): MappingMutationResult
}

data class MappingSecretWall(
    val placeId: String,
    val slot: String,
    val reason: String,
    val elementId: String,
    val observationId: String,
    val sessionId: String,
    val displayId: Int,
)

/**
 * Metadata-only secret boundary. The implementation may invoke Run-1 SecretsPhoneFacade, but no
 * plaintext or secret-bearing value is representable here.
 */
interface MappingSecretsPort {
    fun detect(session: MappingSessionSnapshot, observation: MappingObservation): MappingSecretWall?
    fun request(wall: MappingSecretWall)
}

sealed class MappingStepResult {
    data class Progress(
        val action: MappingAction,
        val fromNodeKey: String,
        val toNodeKey: String,
    ) : MappingStepResult()

    data class NoProgress(
        val action: MappingAction,
        val reason: String,
    ) : MappingStepResult()

    data class Paused(
        val reason: PauseReason,
    ) : MappingStepResult()

    data class CompletedPartial(
        val reason: String,
    ) : MappingStepResult()

    data class Failed(
        val reason: String,
    ) : MappingStepResult()
}

enum class PauseReason {
    NEEDS_SECRET,
    HUMAN_CONTROL,
}

/**
 * Fixed by Run-2 law: autonomous/dummy crawl output is never relabelled as live.
 */
internal const val MAPPING_PERSONA = "mapping"
