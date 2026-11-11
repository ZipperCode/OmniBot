package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneModelCatalogResolverTest {
    @Test
    fun inheritedGuiUsesTheAgentProviderWithoutCreatingADirectBinding() {
        val agent = scene("scene.dispatch.model")
        val gui = scene("scene.vlm.operation.primary", parent = agent.sceneId)
        val provider = provider("agent-provider")
        val binding = SceneModelBindingEntry(agent.sceneId, provider.id, "agent-model")

        val resolved = SceneModelCatalogResolver.resolveCatalogItems(
            listOf(agent, gui), listOf(provider), mapOf(agent.sceneId to binding),
        ).single { it.sceneId == gui.sceneId }

        assertEquals("agent-model", resolved.effectiveModel)
        assertEquals(provider.id, resolved.effectiveProviderProfileId)
        assertTrue(resolved.providerConfigured)
        assertFalse(resolved.bindingExists)
        assertNull(resolved.boundProviderProfileId)
    }

    @Test
    fun removedDedicatedProviderDoesNotFallBackToTheAgentProvider() {
        val agent = scene("scene.dispatch.model")
        val gui = scene("scene.vlm.operation.primary", parent = agent.sceneId)
        val provider = provider("agent-provider")

        val resolved = SceneModelCatalogResolver.resolveCatalogItems(
            listOf(agent, gui), listOf(provider), mapOf(
                agent.sceneId to SceneModelBindingEntry(agent.sceneId, provider.id, "agent-model"),
                gui.sceneId to SceneModelBindingEntry(gui.sceneId, "removed-provider", "gui-model"),
            ),
        ).single { it.sceneId == gui.sceneId }

        assertTrue(resolved.bindingExists)
        assertTrue(resolved.bindingProfileMissing)
        assertFalse(resolved.providerConfigured)
        assertNull(resolved.effectiveProviderProfileId)
        assertEquals("removed-provider", resolved.boundProviderProfileId)
    }

    @Test
    fun imageAndVisionRemainUnconfiguredWhenOnlyTheAgentIsBound() {
        val agent = scene("scene.dispatch.model")
        val provider = provider("agent-provider")
        val resolved = SceneModelCatalogResolver.resolveCatalogItems(
            listOf(agent, scene("scene.vision.understanding"), scene("scene.image.generate")),
            listOf(provider),
            mapOf(agent.sceneId to SceneModelBindingEntry(agent.sceneId, provider.id, "agent-model")),
        ).filter { it.sceneId != agent.sceneId }

        resolved.forEach { item ->
            assertFalse(item.providerConfigured)
            assertFalse(item.bindingExists)
            assertEquals("", item.effectiveModel)
            assertNull(item.effectiveProviderProfileId)
        }
    }

    @Test
    fun cyclicInheritanceDoesNotInventAnEffectiveBinding() {
        val resolved = SceneModelCatalogResolver.resolveCatalogItems(
            listOf(scene("scene.a", parent = "scene.b"), scene("scene.b", parent = "scene.a")),
            emptyList(), emptyMap(),
        )

        assertTrue(resolved.all { !it.providerConfigured && it.effectiveProviderProfileId == null })
    }

    private fun provider(id: String) = ModelProviderProfile(
        id = id, name = id, baseUrl = "https://provider.example/v1",
    )

    private fun scene(id: String, parent: String? = null) = ModelSceneRegistry.SceneRuntimeProfile(
        sceneId = id,
        model = "",
        transport = ModelSceneRegistry.SceneTransport.OPENAI_COMPATIBLE,
        responseParser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
        configSource = ModelSceneRegistry.SceneSource.BUILTIN,
        modelSource = ModelSceneRegistry.SceneSource.BUILTIN,
        inheritsModelFrom = parent,
    )
}
