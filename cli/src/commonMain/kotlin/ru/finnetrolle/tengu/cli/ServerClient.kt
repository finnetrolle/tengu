package ru.finnetrolle.tengu.cli

import ru.finnetrolle.tengu.protocol.AxiErrorEnvelope
import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.protocol.InvokeRequest
import ru.finnetrolle.tengu.protocol.InvokeResponse
import ru.finnetrolle.tengu.protocol.Manifest
import ru.finnetrolle.tengu.protocol.ProtocolJson
import ru.finnetrolle.tengu.cli.platform.httpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class ServerError(val envelope: AxiErrorEnvelope) : Exception(envelope.message)

/** Тонкий HTTP-клиент хаба: manifest + invoke. Все сбои → ServerError с AXI-конвертом. */
class ServerClient(private val config: StoredConfig) : AutoCloseable {

    private companion object {
        const val HTTP_OK = 200
        const val CAUSE_MESSAGE_LIMIT = 120 // сколько символов причины влезает в конверт
    }

    private val client = HttpClient(httpEngine()) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    fun fetchManifest(): Manifest = call {
        val resp = client.get("${config.serverUrl}/v1/manifest") {
            header(HttpHeaders.Authorization, "Bearer ${config.hubToken}")
        }
        resp.expect(HTTP_OK)
        ProtocolJson.json.decodeFromString<Manifest>(resp.bodyAsText())
    }

    fun invoke(request: InvokeRequest, manifestVersion: Int?): InvokeResponse = call {
        val resp = client.post("${config.serverUrl}/v1/invoke") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.hubToken}")
            manifestVersion?.let { header("X-Tengu-Manifest-Version", it.toString()) }
            setBody(ProtocolJson.json.encodeToString(request))
        }
        resp.expect(HTTP_OK)
        ProtocolJson.json.decodeFromString<InvokeResponse>(resp.bodyAsText())
    }

    private suspend fun HttpResponse.expect(status: Int) {
        if (this.status.value != status) {
            val envelope = runCatching {
                ProtocolJson.json.decodeFromString<AxiErrorEnvelope>(bodyAsText())
            }.getOrNull() ?: AxiErrorEnvelope(
                ErrorKind.INTERNAL,
                "unexpected response from tengu server (HTTP ${this.status.value})",
            )
            throw ServerError(envelope)
        }
    }

    /**
     * Сетевые сбои и битые тела ответов переводятся в конверт «сервер недоступен» с setup-хинтом.
     * На native нет java.io.IOException: транспортные исключения движка (WinHttp/Curl)
     * ловятся общим Exception — семантика fail-loud сохранена (AXI §6).
     */
    @Suppress("TooGenericExceptionCaught") // см. KDoc: единый конверт для всех транспортных сбоев движка
    private fun <T> call(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: ServerError) {
        throw e
    } catch (e: Exception) {
        throw ServerError(unreachable(e))
    }

    private fun unreachable(cause: Exception) = AxiErrorEnvelope(
        kind = ErrorKind.INTERNAL,
        message = "cannot reach tengu server at ${config.serverUrl}" +
            " (${cause.message?.take(CAUSE_MESSAGE_LIMIT) ?: cause::class.simpleName})",
        helpHints = listOf("Run `tengu setup --url <url> --token <hub token>` and check the server is running"),
    )

    override fun close() = client.close()
}
