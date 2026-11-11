package cn.com.omnimind.baselib.llm

object SceneModelCatalogResolver {
    fun listCatalogItems(): List<SceneCatalogItem> = resolveCatalogItems(
        runtimeProfiles = ModelSceneRegistry.listRuntimeProfiles(),
        providers = ModelProviderConfigStore.listProfiles(),
        bindings = SceneModelBindingStore.getBindingMap(),
    )

    internal fun resolveCatalogItems(
        runtimeProfiles: List<ModelSceneRegistry.SceneRuntimeProfile>,
        providers: List<ModelProviderProfile>,
        bindings: Map<String, SceneModelBindingEntry>,
    ): List<SceneCatalogItem> {
        val profilesById = providers.associateBy { it.id }
        val scenesById = runtimeProfiles.associateBy { it.sceneId }
        return runtimeProfiles.map { profile ->
                val directBinding = bindings[profile.sceneId]
                val effectiveBinding = directBinding ?: inheritedBinding(profile, bindings, scenesById)
                val boundProfile = effectiveBinding?.providerProfileId?.let(profilesById::get)
                val bindingApplied = effectiveBinding != null && boundProfile?.isConfigured() == true
                val bindingProfileMissing = directBinding != null && boundProfile == null
                SceneCatalogItem(
                    sceneId = profile.sceneId,
                    description = profile.description,
                    defaultModel = profile.model,
                    effectiveModel = if (bindingApplied) effectiveBinding.modelId else profile.model,
                    effectiveProviderProfileId = if (bindingApplied) boundProfile.id else null,
                    effectiveProviderProfileName = if (bindingApplied) boundProfile.name else null,
                    boundProviderProfileId = directBinding?.providerProfileId,
                    boundProviderProfileName = directBinding?.providerProfileId?.let(profilesById::get)?.name,
                    transport = if (bindingApplied) {
                        ModelSceneRegistry.SceneTransport.OPENAI_COMPATIBLE.wireValue
                    } else {
                        profile.transport.wireValue
                    },
                    configSource = profile.configSource.wireValue,
                    overrideApplied = directBinding != null && bindingApplied,
                    overrideModel = directBinding?.modelId,
                    providerConfigured = boundProfile?.isConfigured() == true,
                    bindingExists = directBinding != null,
                    bindingProfileMissing = bindingProfileMissing
                )
            }
    }

    private fun inheritedBinding(
        profile: ModelSceneRegistry.SceneRuntimeProfile,
        bindings: Map<String, SceneModelBindingEntry>,
        scenesById: Map<String, ModelSceneRegistry.SceneRuntimeProfile>,
    ): SceneModelBindingEntry? {
        val visited = mutableSetOf<String>()
        var parentId = profile.inheritsModelFrom
        while (parentId != null && visited.add(parentId)) {
            bindings[parentId]?.let { return it }
            parentId = scenesById[parentId]?.inheritsModelFrom
        }
        return null
    }
}
