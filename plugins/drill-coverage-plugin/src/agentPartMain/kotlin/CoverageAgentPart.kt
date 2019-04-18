package com.epam.drill.plugins.coverage

import com.epam.drill.plugin.api.processing.InstrumentedPlugin
import com.epam.drill.plugin.api.processing.Sender
import com.epam.drill.session.DrillRequest
import com.google.common.reflect.ClassPath
import kotlinx.serialization.json.JSON
import kotlinx.serialization.list
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore

val instrContext = object : InstrContext {
    override fun invoke(): String? = DrillRequest.currentSession()

    override fun get(key: String): String? = DrillRequest[key]
}

object DrillProbeArrayProvider : SimpleSessionProbeArrayProvider(instrContext)

@Suppress("unused")
class CoveragePlugin @JvmOverloads constructor(
    override val id: String,
    private val instrContext: SessionProbeArrayProvider = DrillProbeArrayProvider
) : InstrumentedPlugin<CoverConfig, CoverageAction>() {

    val instrumenter = instrumenter(instrContext)
    
    private val classNameSet = mutableSetOf<String>()

    override fun initPlugin() {
        val initializingMessage = "Initializing plugin $id...\nConfig: ${config.message}"
        println(initializingMessage)
        @Suppress("UnstableApiUsage")
        val classPath = ClassPath.from(ClassLoader.getSystemClassLoader())
        sendMessage(CoverageEventType.INIT, initializingMessage)
        classPath.topLevelClasses.mapNotNull { classInfo ->
            val resourceName = classInfo.resourceName
                .removePrefix("BOOT-INF/classes/") //fix from Spring Boot Executable jar
                .removeSuffix(".class")
            if (config.pathPrefixes.any { resourceName.startsWith(it) }) {
                Pair(resourceName, classInfo)
            } else null
        }.forEach { (resourceName, classInfo) ->
            classNameSet.add(resourceName)
            val bytes = classInfo.asByteSource().read()
            sendClass(ClassBytes(resourceName, bytes.toList()))
        }
        val initializedStr = "Plugin $id initialized!"
        sendMessage(CoverageEventType.INITIALIZED, initializedStr)
        println(initializedStr)
        println("Loaded ${classNameSet.count()} classes: $classNameSet")
    }

    override fun doAction(action: CoverageAction) {
        val record = action.isRecord
        if (record) {
            println("Start recording for session ${action.sessionId}")
            instrContext.start(action.sessionId)
        } else {
            println("End of recording for session ${action.sessionId}")
            val runtimeData = instrContext.stop(action.sessionId)
            runtimeData?.forEach {execData ->
                val dataStore = ExecutionDataStore()
                val sessionInfos = SessionInfoStore()
                execData.collect(dataStore, sessionInfos, false)
                sendExecutionData(dataStore.contents.map { exData ->
                    ExDataTemp(
                        id = exData.id,
                        className = exData.name,
                        probes = exData.probes.toList(),
                        testName = execData.sessionId.takeIf { !it.isNullOrBlank() }
                    )
                })
            }
        }
    }

    override fun instrument(className: String, initialBytes: ByteArray): ByteArray {
        return if (className in classNameSet) { //instrument only classes from the selected packages
            try {
                instrumenter(className, initialBytes)
            } catch (ex: Throwable) {
                ex.printStackTrace()
                throw ex
            }
        } else initialBytes
    }

    private fun sendClass(classBytes: ClassBytes) {
        val classJson = JSON.stringify(ClassBytes.serializer(), classBytes)
        sendMessage(CoverageEventType.CLASS_BYTES, classJson)
    }

    private fun sendExecutionData(exData: List<ExDataTemp>) {
        val exDataJson = JSON.stringify(ExDataTemp.serializer().list, exData)
        sendMessage(CoverageEventType.COVERAGE_DATA, exDataJson)
    }

    private fun sendMessage(type: CoverageEventType, str: String) {
        val message = JSON.stringify(
            CoverageMessage.serializer(),
            CoverageMessage(type, str)
        )
        Sender.sendMessage("coverage", message)
    }


    override var confSerializer: kotlinx.serialization.KSerializer<CoverConfig> = CoverConfig.serializer()
    override var actionSerializer: kotlinx.serialization.KSerializer<CoverageAction> = CoverageAction.serializer()

}
