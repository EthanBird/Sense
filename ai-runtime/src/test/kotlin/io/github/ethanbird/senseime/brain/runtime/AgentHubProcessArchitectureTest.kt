package io.github.ethanbird.senseime.brain.runtime

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AgentHubProcessArchitectureTest {
    @Test
    fun hubOwnersAndBridgeSharePrivateBrainProcess() {
        val root = repositoryRoot()
        val runtimeManifest = parse(File(root, "ai-runtime/src/main/AndroidManifest.xml"))
        val appManifest = parse(File(root, "app/src/main/AndroidManifest.xml"))

        listOf(
            "io.github.ethanbird.senseime.brain.runtime.SenseAiBrainService",
            "io.github.ethanbird.senseime.brain.runtime.SenseAgentChannelService",
            "io.github.ethanbird.senseime.brain.runtime.SenseAgentHubBridgeService",
        ).forEach { name ->
            val service = component(runtimeManifest, "service", name)
            assertEquals("false", service.getAttributeNS(ANDROID_NS, "exported"))
            assertEquals(":brain", service.getAttributeNS(ANDROID_NS, "process"))
        }

        val activity = component(appManifest, "activity", ".AgentHubActivity")
        assertEquals("false", activity.getAttributeNS(ANDROID_NS, "exported"))
        assertEquals(":brain", activity.getAttributeNS(ANDROID_NS, "process"))
    }

    @Test
    fun imeUsesRemotePortAndDoesNotConstructHubStores() {
        val root = repositoryRoot()
        val ime = File(
            root,
            "ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/" +
                "SenseInputMethodService.kt",
        ).readText()

        assertTrue(ime.contains("RemoteSenseAgentHubClient.get(applicationContext)"))
        assertTrue(ime.contains("private var agentRuntime: AgentHubPort?"))
        assertFalse(ime.contains("SenseAgentHubRuntime.get("))
        assertFalse(ime.contains("AgentConversationStore("))
        assertFalse(ime.contains("AgentDurableRunStore("))
    }

    @Test
    fun imeBinderCommandsAreOneWayWrittenOffMainAndCompletedByAsyncAck() {
        val root = repositoryRoot()
        val remote = File(
            root,
            "ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/" +
                "RemoteSenseAgentHubClient.kt",
        ).readText()
        val bridge = File(
            root,
            "ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/" +
                "SenseAgentHubBridgeService.kt",
        ).readText()

        assertTrue(remote.contains("sense-agent-hub-ipc-writer"))
        assertTrue(remote.contains("private fun transactOneway("))
        assertTrue(remote.contains("TRANSACTION_COMMAND_ACK"))
        assertTrue(remote.contains("IBinder.FLAG_ONEWAY"))
        assertTrue(remote.indexOf("ipcWriter.execute") < remote.indexOf("service.transact("))
        assertFalse(remote.contains("CountDownLatch"))
        assertFalse(remote.contains(".await("))
        assertFalse(remote.contains("reply.readException"))
        assertFalse(bridge.contains("CountDownLatch"))
        assertFalse(bridge.contains(".await("))
        assertTrue(bridge.contains("tryLinkAgentHubCallback"))
        assertTrue(bridge.contains("AgentHubCommandAckLedger"))
    }

    @Test
    fun everyImeAgentActionUsesAsyncAckAndHistoryCanHydrateBeyondCompactProjection() {
        val root = repositoryRoot()
        val ime = File(
            root,
            "ime-service/src/main/kotlin/io/github/ethanbird/senseime/service/" +
                "SenseInputMethodService.kt",
        ).readText()
        val remote = File(
            root,
            "ai-runtime/src/main/kotlin/io/github/ethanbird/senseime/brain/runtime/" +
                "RemoteSenseAgentHubClient.kt",
        ).readText()

        listOf(
            "sendAsync(",
            "stopAsync(",
            "clearConversationAsync(",
            "openConversationAsync(",
            "runGoldQuoteAsync(",
            "cancelActionAsync(",
            "dismissActionAsync(",
        ).forEach { call -> assertTrue("Missing $call", ime.contains(call)) }
        assertTrue(ime.contains("AgentHubCommandOutcomeCode.ACCEPTED"))
        assertTrue(ime.contains("agentCommandLifecycle"))
        assertTrue(remote.contains("TRANSACTION_HISTORY_FETCH"))
        assertTrue(remote.contains("TRANSACTION_CONVERSATION_FETCH"))
        assertTrue(remote.contains("handleHistoryPage"))
        assertTrue(remote.contains("handleConversationPage"))
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var cursor = File(userDir).canonicalFile
        repeat(6) {
            if (File(cursor, "settings.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Sense repository root not found from $userDir")
    }

    private fun parse(file: File): Element {
        assertTrue("Missing ${file.path}", file.isFile)
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun component(root: Element, tag: String, name: String): Element {
        val nodes = root.getElementsByTagName(tag)
        val matches = (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .filter { it.getAttributeNS(ANDROID_NS, "name") == name }
        assertEquals("Expected one $tag $name", 1, matches.size)
        return matches.single()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
