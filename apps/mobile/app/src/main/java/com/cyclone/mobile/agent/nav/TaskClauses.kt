package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.agent.plan.*
import com.cyclone.mobile.fastpath.FastPathLanding
import org.json.JSONArray
import org.json.JSONObject

enum class NavCapability { OPEN_PLACE, FIND_SIGNED_IN_IDENTITY, CREATE_ACCOUNT, OPEN_DM, SEARCH_PERSON, SET_TIMER, OPEN_WIFI, READ_NETWORK, USER_GOAL }
enum class ClauseStatus(val wire: String) { PENDING("pending"), ACTIVE("active"), VERIFIED("verified"), NEEDS_APPROVAL("needs-approval"), FAILED("failed") }

data class TaskClause(
    val id: String,
    val text: String,
    val place: String?,
    val capability: NavCapability,
    val doneWhen: String,
    val target: String? = null,
    val status: ClauseStatus = ClauseStatus.PENDING,
    val proof: String? = null,
) {
    fun toJson() = JSONObject().put("id", id).put("text", text.take(500)).put("place", place ?: JSONObject.NULL)
        .put("capability", capability.name).put("doneWhen", doneWhen).put("status", status.wire)
        .put("proof", proof ?: JSONObject.NULL)
}

/** Bind meanings and evidence, never a click script. Unknown meanings keep the user's clause. */
object ClauseCompiler {
    /**
     * Clause runs are for sentences that need them: several clauses, or one clause whose proof is stronger than the
     * ordinary goal contract (identity, account, DM, timer, network). A plain "open X" keeps Stage 1 Fast Path and the
     * existing completion contract.
     */
    fun needsClauseRun(clauses: List<TaskClause>): Boolean = clauses.any { it.place != null } &&
        (clauses.size > 1 || clauses.any { it.capability !in PLAIN })

    private val PLAIN = setOf(NavCapability.OPEN_PLACE, NavCapability.OPEN_WIFI, NavCapability.USER_GOAL)

    fun compile(goal: String, nativeAvailable: (String) -> Boolean? = { null }): List<TaskClause> {
        var previousPlace: String? = null
        val pieces = SPLIT.split(goal).map { it.trim().trimEnd(',', ';').trim() }.filter(String::isNotBlank)
        // Keep the whole sentence if a pathological input exceeds the bounded clause budget.
        return (if (pieces.size > 16) listOf(goal) else pieces).mapIndexed { index, text ->
            val destination = TaskDifficulty.destinationsInOrder(text).lastOrNull()
            var place = destination?.let {
                if (it.kind == "host") "chrome:https://${it.value}" else "package:${it.value}"
            } ?: previousPlace
            if (place?.startsWith("package:") == true) {
                val pkg = place.removePrefix("package:")
                if (nativeAvailable(pkg) == false) FastPathLanding.webFallback(pkg)?.let { origin ->
                    place = "chrome:${origin.trimEnd('/')}"
                }
            }
            previousPlace = place
            val capability = when {
                IDENTITY.containsMatchIn(text) && (text.contains("gmail", true) || text.contains("email", true)) -> NavCapability.FIND_SIGNED_IN_IDENTITY
                SIGNUP.containsMatchIn(text) -> NavCapability.CREATE_ACCOUNT
                DM.containsMatchIn(text) -> NavCapability.OPEN_DM
                TIMER.containsMatchIn(text) -> NavCapability.SET_TIMER
                NETWORK.containsMatchIn(text) -> NavCapability.READ_NETWORK
                WIFI.containsMatchIn(text) -> NavCapability.OPEN_WIFI
                TaskDifficulty.isNamedAppOpenOnly(text) || com.cyclone.mobile.agent.contract.GoalContractCompiler.isSimpleWebNavigation(text) -> NavCapability.OPEN_PLACE
                else -> NavCapability.USER_GOAL
            }
            val target = when (capability) {
                NavCapability.OPEN_DM, NavCapability.SEARCH_PERSON -> person(text)
                NavCapability.SET_TIMER -> TIMER.find(text)?.let { match ->
                    val number = match.groupValues[1].toLongOrNull() ?: return@let null
                    (number * if (match.groupValues[2].startsWith("hour", true)) 3600 else if (match.groupValues[2].startsWith("min", true)) 60 else 1)
                        .takeIf { it in 1..86400 }?.toString()
                }
                else -> null
            }
            TaskClause("clause-${index + 1}", text, place, capability, when (capability) {
                NavCapability.FIND_SIGNED_IN_IDENTITY -> "live signed-in-email recorded from account UI"
                NavCapability.CREATE_ACCOUNT -> "sign-up email field matches the live ledger; approval before submit"
                NavCapability.OPEN_DM -> "named conversation open with a message composer"
                NavCapability.SEARCH_PERSON -> "named person found in current results"
                NavCapability.SET_TIMER -> "requested duration running in the timer UI"
                NavCapability.OPEN_WIFI -> "Wi-Fi settings visible"
                NavCapability.READ_NETWORK -> "connected-network read from current Wi-Fi UI"
                NavCapability.OPEN_PLACE -> "requested app or origin visible"
                NavCapability.USER_GOAL -> "clause-specific goal contract verified on the live screen"
            }, target)
        }
    }

    private fun person(text: String): String? = listOf(
        Regex("(?i)\\b(?:dm|chat|thread|conversation)\\s+(?:of|with|from)\\s+(.+?)(?:\\s+(?:on|in|using)\\s+|$)"),
        Regex("(?i)\\b(?:find|open)\\s+(.+?)[’']s\\s+(?:dm|chat|thread)\\b"),
    ).firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim()?.takeIf { name -> name.length in 1..80 } }

    private val SPLIT = Regex("(?i),?\\s+(?:and\\s+)?then\\s+|;\\s*|\\s+and\\s+(?=(?:open|launch)\\s)")
    private val IDENTITY = Regex("(?i)which|signed[- ]?in|logged[- ]?in|current.*(?:email|account)")
    private val SIGNUP = Regex("(?i)sign[- ]?up|register|(?:create|make)\\s+(?:an?\\s+|new\\s+)?(?:[a-z]+\\s+)?account")
    private val DM = Regex("(?i)\\b(dm|direct message|conversation|thread|chat)\\b")
    private val TIMER = Regex("(?i)\\b(?:timer.*?)(\\d+)\\s*(minutes?|mins?|seconds?|secs?|hours?)\\b")
    private val NETWORK = Regex("(?i)connected.*network|network.*name|(?:which|what).*wi[- ]?fi")
    private val WIFI = Regex("(?i)wi[- ]?fi|wireless settings")
}

class ClauseRun(val goal: String, initial: List<TaskClause>) {
    private val rows = initial.toMutableList()
    var index: Int = 0
        private set
    val current: TaskClause? get() = rows.getOrNull(index)
    val complete: Boolean get() = rows.isNotEmpty() && index == rows.size
    fun clauses(): List<TaskClause> = rows.toList()
    fun toJson(): JSONArray = JSONArray().also { out -> rows.forEach { out.put(it.toJson()) } }

    /** Advance only the current clause. A later screen cannot retroactively prove skipped work. */
    fun observe(screen: NavigationScreen, ledger: TaskLedger, genericProof: Boolean = false): Boolean {
        val clause = current ?: return false
        if (clause.status == ClauseStatus.FAILED) return false
        val proof = ClauseProof.check(clause, screen, ledger, genericProof)
        if (proof == null) {
            if (clause.status != ClauseStatus.ACTIVE) rows[index] = clause.copy(status = ClauseStatus.ACTIVE, proof = null)
            return false
        }
        if (clause.capability == NavCapability.CREATE_ACCOUNT) {
            rows[index] = clause.copy(status = ClauseStatus.NEEDS_APPROVAL, proof = proof)
            return false
        }
        rows[index] = clause.copy(status = ClauseStatus.VERIFIED, proof = proof)
        index++
        return true
    }

    fun fail(): TaskClause? = current?.let {
        it.copy(status = ClauseStatus.FAILED).also { failed -> rows[index] = failed }
    }

    fun trajectory(screen: NavigationScreen?): TaskTrajectory {
        val steps = rows.map { clause ->
            val needsLanding = clause == current && clause.place != null && !ClauseProof.samePlace(clause.place, screen?.placeId)
            val uri = clause.place?.takeIf { it.startsWith("chrome:") }?.removePrefix("chrome:")
            TaskWaypoint(
                kind = if (needsLanding) { if (uri != null) WaypointKind.LAUNCH_INTENT else WaypointKind.OPEN_APP }
                    else WaypointKind.SCENE,
                packageName = clause.place?.takeIf { it.startsWith("package:") }?.removePrefix("package:")
                    ?: if (uri != null) "com.android.chrome" else null,
                uri = uri, until = "clause_proof", summary = clause.text,
                clauseId = clause.id, capability = clause.capability.name, doneWhen = clause.doneWhen,
            )
        }
        return TaskTrajectory(if (rows.size > 1) TaskDifficultyTier.HARD else TaskDifficultyTier.MEDIUM,
            "current", goal, steps, index, horizonPlanned = true)
    }
}
