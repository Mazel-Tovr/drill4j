package com.epam.drill.endpoints.openapi

import com.epam.drill.agentmanager.AgentStorage
import com.epam.drill.agentmanager.DrillAgent
import com.epam.drill.common.Message
import com.epam.drill.common.MessageType
import com.epam.drill.endpoints.agentWsMessage
import com.epam.drill.plugins.Plugins
import com.epam.drill.plugins.agentPluginPart
import com.epam.drill.plugins.serverInstance
import com.epam.drill.router.DevRoutes
import com.epam.drill.router.Routes
import com.google.gson.Gson
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.auth.authenticate
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.send
import io.ktor.locations.KtorExperimentalLocationsAPI
import io.ktor.locations.get
import io.ktor.locations.patch
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Routing
import io.ktor.routing.routing
import kotlinx.serialization.toUtf8Bytes
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Swagger DrillAdmin
 *
 * This is a drill-ktor-admin-server
 */
@KtorExperimentalLocationsAPI
class SwaggerDrillAdminServer(override val kodein: Kodein) : KodeinAware {
    private val app: Application by instance()
    private val agentStorage: AgentStorage by instance()
    private val plugins: Plugins by kodein.instance()

    init {
        app.routing {
            registerAgent()
            registerDrillAdmin()
            if (app.environment.config.property("ktor.dev").toString().toBoolean()) {
                registerDrillAdminDev()
            }
        }
    }

    private fun Routing.registerAgent() {
        authenticate {
            patch<Routes.Api.Agent.Agent> {config ->
                agentStorage.agents[config.agentId]?.agentWsSession?.send(
                    Frame.Text(
                        Gson().toJson(
                            Message(
                                MessageType.MESSAGE,
                                "/agent/updateAgentConfig",
                                call.receive()
                            )
                        )
                    )
                )
                call.respond { HttpStatusCode.OK }
            }
        }

        authenticate {
            get<Routes.Api.Agent.UnloadPlugin> { up ->
                val drillAgent: DrillAgent? = agentStorage.agents[up.agentId]
                if (drillAgent == null) {
                    call.respond("can't find the agent '${up.agentId}'")
                    return@get
                }
                val agentPluginPartFile = plugins.plugins[up.pluginName]?.agentPluginPart
                if (agentPluginPartFile == null) {
                    call.respond("can't find the plugin '${up.pluginName}' in the agent '${up.agentId}'")
                    return@get
                }

                drillAgent.agentWsSession.send(agentWsMessage("/plugins/unload", up.pluginName))
//            drillAgent.agentInfo.rawPluginNames.removeIf { x -> x.id == up.pluginName }
                call.respond("event 'unload' was sent to AGENT")
            }
        }

        authenticate {
            get<Routes.Api.Agent.LoadPlugin> { lp ->
                val drillAgent: DrillAgent? = agentStorage.agents[lp.agentId]
                if (drillAgent == null) {
                    call.respond("can't find the agent '${lp.agentId}'")
                    return@get
                }
                val agentPluginPartFile = plugins.plugins[lp.pluginName]?.agentPluginPart
                if (agentPluginPartFile == null) {
                    call.respond("can't find the plugin '${lp.pluginName}' in the agent '${lp.agentId}'")
                    return@get
                }
                val inChannel: FileChannel = agentPluginPartFile.inputStream().channel
                val fileSize: Long = inChannel.size()
                val buffer: ByteBuffer = ByteBuffer.allocate(fileSize.toInt())

                val message = Gson().toJson(
                    Message(MessageType.MESSAGE, "/plugins/load", lp.pluginName)
                ).toUtf8Bytes()

                val messagePosition = ByteBuffer.allocate(4).putInt(message.size)
                val filePosition = ByteBuffer.allocate(4).putInt(message.size)
                messagePosition.flip()
                filePosition.flip()

                inChannel.read(buffer)
                buffer.flip()


                val put = ByteBuffer.allocate(8 + message.size + fileSize.toInt())
                    .put(messagePosition)
                    .put(filePosition)
                    .put(message)
                    .put(buffer)


                put.flip()
//                println(put.array().contentToString())
//                put.flip()
                drillAgent.agentWsSession.send(Frame.Binary(true, put))
                call.respond("event 'load' and plugin's file(${lp.agentId}) was sent to AGENT")
            }
        }

        authenticate {
            get<Routes.Api.Agent.Agent> { up ->
                call.respond(agentStorage.agents[up.agentId]?.agentInfo!!)
            }
        }
    }

    /**
     * drill-admin
     */
    private fun Routing.registerDrillAdmin() {
        authenticate {
            get<Routes.Api.AllPlugins> {
                call.respond(plugins.plugins.values.map { dp -> dp.serverInstance.id })
            }
        }
    }

    /**
     * drill-admin-dev
     */
    private fun Routing.registerDrillAdminDev() {
        authenticate {
            get<DevRoutes.Api.Agent.Agents> {
                call.respond(agentStorage.agents.values.map { da -> da.agentInfo })
            }
        }
    }

}