package ru.finnetrolle.tengu.toolkit

import ru.finnetrolle.tengu.protocol.ToolDescriptor
import io.ktor.client.HttpClient
import java.time.Clock

/**
 * Всё, что плагин получает на вызов. Фреймворк-агностик: ни серверного Ktor, ни Vault
 * в сигнатурах нет — это шов для будущего plugin-executor (тот же контракт за сетью).
 */
class InvocationContext(
    val tool: String,
    val userId: String,
    val args: Map<String, String>,
    val flags: Map<String, String>,
    val secrets: SecretScope,
    val httpClient: HttpClient,      // общий и credential-free: свой auth-заголовок плагин ставит сам
    val clock: Clock,
)

/**
 * Контракт плагина: дескриптор — вся поверхность тула (данные, единый источник правды
 * для манифеста, валидации и --help); invoke — выполнение команды. Гарантии автору:
 * args/flags уже провалидированы, пользователь известен, секреты скоуплены,
 * рендер/транспорт/exit codes — не его забота.
 */
interface ToolPlugin {
    val descriptor: ToolDescriptor
    suspend fun invoke(commandPath: List<String>, ctx: InvocationContext): AxiResult
}
