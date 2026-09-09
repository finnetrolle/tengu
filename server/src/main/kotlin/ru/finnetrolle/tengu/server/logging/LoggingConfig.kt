package ru.finnetrolle.tengu.server.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ru.finnetrolle.tengu.server.ServerInfo

/** Startup-only settings. Invalid values are never retained or included in diagnostics. */
data class LoggingConfig(val level: Level = Level.INFO, val responseBody: Boolean = false) {
    fun applyTo(context: LoggerContext) {
        context.getLogger("ru.finnetrolle.tengu").level = level
    }

    companion object {
        /** Configure a valid INFO bootstrap before inspecting either environment setting. */
        fun bootstrap(context: LoggerContext) {
            context.reset()
            context.putProperty("tengu.service.version", ServerInfo.VERSION)
            JoranConfigurator().apply {
                this.context = context
                doConfigure(checkNotNull(LoggingConfig::class.java.getResource("/logback.xml")))
            }
            context.start()
        }

        fun fromEnv(env: Map<String, String>): LoggingConfig {
            val levels = listOf("DEBUG", "INFO", "WARN", "ERROR")
            val bodies = listOf("0", "1")
            val level = env["TENGU_LOG_LEVEL"] ?: "INFO"
            if (level !in levels) throw LoggingConfigurationException("TENGU_LOG_LEVEL", levels)
            val body = env["TENGU_LOG_RESPONSE_BODY"] ?: "0"
            if (body !in bodies) throw LoggingConfigurationException("TENGU_LOG_RESPONSE_BODY", bodies)
            return LoggingConfig(Level.valueOf(level), body == "1")
        }
    }
}

class LoggingConfigurationException(val key: String, val allowedValues: List<String>) :
    IllegalArgumentException("Invalid logging configuration")
