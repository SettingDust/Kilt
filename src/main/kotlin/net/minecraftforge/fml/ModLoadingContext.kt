package net.minecraftforge.fml

import com.electronwill.nightconfig.toml.TomlParser
import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry
import net.minecraftforge.fml.config.IConfigSpec
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.moddiscovery.NightConfigWrapper
import org.apache.commons.codec.digest.DigestUtils
import xyz.bluspring.kilt.Kilt
import xyz.bluspring.kilt.loader.KiltModContainer
import xyz.bluspring.kilt.loader.mod.ForgeMod
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

class ModLoadingContext(private val mod: ForgeMod) {
    // this should be Any, but we're only handling Java mods here so
    private var languageExtension: FMLJavaModLoadingContext = FMLJavaModLoadingContext(mod)

    val activeContainer: ModContainer = KiltModContainer(mod)
    val activeNamespace: String
        get() {
            return mod.modId
        }

    fun extension(): FMLJavaModLoadingContext {
        return languageExtension
    }

    fun <T> registerExtensionPoint(point: Class<out IExtensionPoint<T>>, extension: Supplier<T>) where T : Record, T : IExtensionPoint<T> {
        activeContainer.registerExtensionPoint(point, extension)
    }

    // Thank gOD ForgeConfigApiPort uses a different package name for ModLoadingContext, otherwise
    // this wouldn't work well at all.
    fun registerConfig(type: ModConfig.Type, spec: IConfigSpec<*>, fileName: String) {
        val modId = mod.modId
        ForgeConfigRegistry.INSTANCE.register(modId, type, spec, fileName)
    }

    fun registerConfig(type: ModConfig.Type, spec: IConfigSpec<*>) {
        val modId = mod.modId
        ForgeConfigRegistry.INSTANCE.register(modId, type, spec)
    }

    companion object {
        // Mapped from Mod ID to ModLoadingContext
        internal val contexts = ConcurrentHashMap<String, ModLoadingContext>()
        // Mapped from MD5 hash of mods.toml to Mod ID, makes things faster.
        private val tomlCache = ConcurrentHashMap<String, String>()

        private val tomlParser = TomlParser()

        // oh so that's why they did it like that
        var kiltActiveModId: String? = null

        val activeContainer: ModContainer
            get() {
                if (kiltActiveModId != null)
                    return Kilt.loader.mods.first { it.modId == kiltActiveModId }.container

                val stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                val source = stackWalker.callerClass

                return get(source).activeContainer
            }

        @JvmStatic
        fun get(): ModLoadingContext {
            if (kiltActiveModId != null) {
                if (!contexts.contains(kiltActiveModId)) {
                    val mod = Kilt.loader.getMod(kiltActiveModId!!) ?: throw Exception("Kilt has not finished loading mods yet!")
                    contexts[kiltActiveModId!!] = ModLoadingContext(mod)
                }

                return contexts[kiltActiveModId]!!
            }

            return getForgeContext()
        }

        private fun getForgeContext(): ModLoadingContext {
            if (!contexts.contains("forge")) {
                val mod = Kilt.loader.getMod("forge") ?: throw Exception("Kilt has not finished loading mods yet!")
                contexts["forge"] = ModLoadingContext(mod)
            }

            return contexts["forge"]!!
        }

        // Put this here so ModLoadingContext can be called from a Forge method
        @JvmStatic
        fun get(source: Class<*>): ModLoadingContext {
            val tomlText = source.getResource("/META-INF/mods.toml")?.readText() ?: source.getResource("/META-INF/forge.mods.toml")?.readText() ?: return getForgeContext()
            val tomlStream = tomlText.byteInputStream()

            val hash = DigestUtils.md5Hex(tomlStream)

            return if (tomlCache.contains(hash)) {
                contexts[tomlCache[hash]]!!
            } else {
                val toml = NightConfigWrapper(tomlParser.parse(tomlText))
                val modId = toml.getConfigList("mods")[0].getConfigElement<String>("modId").orElseThrow {
                    RuntimeException("Mod does not contain mod ID! How did you even get to this point?")
                }

                if (modId == "forge")
                    return getForgeContext()

                tomlCache[hash] = modId

                if (!contexts.contains(modId)) {
                    val mod = Kilt.loader.getMod(modId) ?: throw Exception("Kilt has not finished loading mods yet!")
                    contexts[modId] = ModLoadingContext(mod)
                }

                return contexts[modId]!!
            }
        }
    }
}