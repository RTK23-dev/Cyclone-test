package com.cyclone.mobile.gateway

import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKey
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.mapping.crawl.MappingDanger
import com.cyclone.mobile.mapping.crawl.MappingDoorKind
import com.cyclone.mobile.mapping.crawl.VerifiedStructure
import com.cyclone.mobile.mapping.run.AtlasStoreMappingPort
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GatewayV5KnowledgeAdapterTest {
    private val dir: File = Files.createTempDirectory("knowledge").toFile()
    private val store = AtlasStore(File(dir, "atlas.json"))
    private val gmail = "package:com.google.android.gm"
    private val home = "screen:home:aaaaaaaaaaaaaaaa"
    private val menu = "screen:menu:bbbbbbbbbbbbbbbb"
    private val settings = "screen:settings:cccccccccccccccc"
    private val inbox = "screen:list:dddddddddddddddd"
    private val v1 = AppVersionEvidence("com.google.android.gm", "2026.08.01", 100)
    private val v2 = AppVersionEvidence("com.google.android.gm", "2026.09.14", 120)

    @After
    fun cleanup() {
        GatewayV5KnowledgeAdapter.resetForTests()
        dir.deleteRecursively()
    }

    private fun map(version: AppVersionEvidence, clock: Long, vararg doors: Triple<String, String, String>) {
        val port = AtlasStoreMappingPort(store, gmail, "Gmail", clock = { clock }, appVersion = version)
        doors.forEach { (from, to, key) -> port.recordVerified(gmail, "mapping", VerifiedStructure(from, to, key, MappingDoorKind.MENU, "fp-$from", "fp-$to")) }
    }

    private fun install(installed: AppVersionEvidence?, walks: List<RunWalk> = emptyList()) {
        GatewayV5KnowledgeAdapter.snapshot = { placeId, persona -> store.snapshot(AtlasPlaceKey(placeId, persona)) }
        GatewayV5KnowledgeAdapter.installedVersion = { installed }
        GatewayV5KnowledgeAdapter.walks = { walks }
    }

    @Test
    fun versionsShowDoorsPerVersionAndDoorsLastConfirmedOnAnOlderOne() {
        map(v1, 1_000, Triple(home, menu, "door:menu:1111111111111111"), Triple(menu, settings, "door:settings:2222222222222222"))
        map(v2, 2_000, Triple(home, inbox, "door:inbox:3333333333333333"))
        install(v2)
        val result = GatewayV5KnowledgeAdapter.versions(JSONObject().put("placeId", gmail))
        val versions = result.getJSONArray("versions")
        assertEquals(2, versions.length())
        assertEquals("2026.09.14", versions.getJSONObject(0).getString("versionName"))
        assertTrue(versions.getJSONObject(0).getBoolean("installed"))
        assertEquals(1, versions.getJSONObject(0).getInt("doors"))
        assertEquals(2, versions.getJSONObject(1).getInt("doors"))
        assertFalse(result.getBoolean("needsRemap"))
        assertEquals(2, result.getInt("staleDoorCount"))

        install(AppVersionEvidence("com.google.android.gm", "2026.10.01", 130))
        assertTrue(GatewayV5KnowledgeAdapter.versions(JSONObject().put("placeId", gmail)).getBoolean("needsRemap"))
    }

    @Test
    fun scenariosAreRoutesFromTheEntryWithHealthFromRuns() {
        map(v2, 1_000, Triple(home, menu, "door:menu:1111111111111111"), Triple(menu, settings, "door:settings:2222222222222222"), Triple(home, inbox, "door:inbox:3333333333333333"))
        AtlasStoreMappingPort(store, gmail, "Gmail", clock = { 1_000 }).markDanger(gmail, "mapping", settings, "door:x:4444444444444444", MappingDanger.DELETE)
        install(v2, listOf(
            RunWalk("ai-run-3", "completed", 3_000, gmail, listOf(home, inbox)),
            RunWalk("ai-run-2", "failed", 2_000, gmail, listOf(home, menu, settings)),
            RunWalk("ai-run-1", "failed", 1_000, gmail, listOf(home, menu, settings)),
        ))
        val result = GatewayV5KnowledgeAdapter.scenarios(JSONObject().put("placeId", gmail))
        assertEquals(home, result.getString("entryScreenId"))
        val scenarios = (0 until result.getJSONArray("scenarios").length()).map { result.getJSONArray("scenarios").getJSONObject(it) }
        assertEquals(3, scenarios.size)
        val toSettings = scenarios.first { it.getString("endScreenId") == settings }
        assertEquals(listOf(home, menu, settings), (0 until 3).map { toSettings.getJSONArray("route").getString(it) })
        assertEquals(2, toSettings.getInt("steps"))
        assertEquals("critical", toSettings.getString("health"))
        assertTrue(toSettings.getBoolean("danger"))
        assertTrue(toSettings.getString("title").startsWith("Reach "))
        assertEquals("passing", scenarios.first { it.getString("endScreenId") == inbox }.getString("health"))
        assertEquals("critical", scenarios.first { it.getString("endScreenId") == menu }.getString("health"))
        assertEquals(2, toSettings.getJSONArray("runs").length())
        assertEquals(toSettings.getString("scenarioId"), GatewayV5KnowledgeAdapter.scenarios(JSONObject().put("placeId", gmail))
            .getJSONArray("scenarios").let { a -> (0 until a.length()).map(a::getJSONObject).first { it.getString("endScreenId") == settings }.getString("scenarioId") })
    }

    @Test
    fun healthRulesAndRequestValidation() {
        assertEquals("untested", GatewayV5KnowledgeAdapter.health(emptyList()))
        assertEquals("warning", GatewayV5KnowledgeAdapter.health(listOf(RunWalk("a", "failed", 2, gmail, emptyList()), RunWalk("b", "completed", 1, gmail, emptyList()))))
        fun code(args: JSONObject, op: String) = (runCatching { GatewayV5KnowledgeAdapter.dispatch(op, args) }.exceptionOrNull() as GatewayProtocolException).code
        assertEquals("INVALID_REQUEST", code(JSONObject().put("placeId", "chrome:https://x.com"), "atlas.versions"))
        assertEquals("INVALID_REQUEST", code(JSONObject().put("placeId", gmail).put("sql", 1), "scenarios.list"))
        assertEquals("INVALID_REQUEST", code(JSONObject().put("placeId", gmail).put("persona", "boss"), "scenarios.list"))
        install(null)
        assertEquals(0, GatewayV5KnowledgeAdapter.scenarios(JSONObject().put("placeId", gmail)).getJSONArray("scenarios").length())
        assertTrue(setOf("atlas.versions", "scenarios.list").all { it in GatewayProtocol.operations && it in GatewayProtocol.legacyReadOnlyOperations })
    }
}
