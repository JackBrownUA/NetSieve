package io.github.jackbrownua.netsieve.core

import io.github.jackbrownua.netsieve.vpn.StrategyPreset
import io.github.jackbrownua.netsieve.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class StrategyTestResult(
    val preset: StrategyPreset,
    val success: Boolean,
    val latencyMs: Long,
    val httpCode: Int?,
    val error: String? = null
)

object StrategyTester {

    private const val TEST_HOST = "127.0.0.1"
    private const val TEST_PORT = 1081

    @Volatile
    private var activeConn: HttpsURLConnection? = null

    fun abortActive() {
        runCatching { activeConn?.disconnect() }
        runCatching { NativeBridge.stopByeDpi() }
    }

    suspend fun runAll(
        rawDomain: String,
        presets: List<StrategyPreset>,
        onStepStart: (StrategyPreset, Int) -> Unit,
        onStepResult: (StrategyTestResult) -> Unit
    ): List<StrategyTestResult> = withContext(Dispatchers.IO) {
        val domain = DnsChecker.sanitizeDomain(rawDomain)
        val results = mutableListOf<StrategyTestResult>()

        if (!NativeBridge.ensureLoaded()) {
            VpnState.log("Автоподбор: не удалось загрузить libnetsieve.so")
            return@withContext results
        }

        VpnState.setAutoTesting(true)
        val completionHandle = coroutineContext[Job]?.invokeOnCompletion {
            abortActive()
        }

        try {
            VpnState.log("Автоподбор: старт проверки ${presets.size} стратегий на $domain")

            for ((index, preset) in presets.withIndex()) {
                if (!isActive) break
                onStepStart(preset, index)

                val result = testSingle(domain, preset)
                if (!isActive) break

                results.add(result)
                onStepResult(result)

                val statusText = if (result.success) {
                    "OK (${result.httpCode}, ${result.latencyMs} мс)"
                } else {
                    "FAIL (${result.error ?: "таймаут"})"
                }
                VpnState.log("Автоподбор [${preset.id}]: $statusText")
                delay(200)
            }
        } finally {
            completionHandle?.dispose()
            abortActive()
            VpnState.setAutoTesting(false)
        }

        results
    }

    private suspend fun testSingle(domain: String, preset: StrategyPreset): StrategyTestResult {
        val args = (listOf("-i", TEST_HOST, "-p", TEST_PORT.toString()) + preset.args).toTypedArray()

        val thread = Thread({
            NativeBridge.runByeDpi(args)
        }, "NetSieve-Test-${preset.id}")
        thread.start()

        try {
            if (!waitForPort(TEST_HOST, TEST_PORT)) {
                return StrategyTestResult(
                    preset = preset,
                    success = false,
                    latencyMs = 0L,
                    httpCode = null,
                    error = "Прокси не поднялся"
                )
            }

            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(TEST_HOST, TEST_PORT))
            val start = System.currentTimeMillis()
            val conn = (URL("https://$domain/").openConnection(proxy) as HttpsURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NetSieve/0.4")
            }
            activeConn = conn

            return try {
                val code = conn.responseCode
                val ms = System.currentTimeMillis() - start
                StrategyTestResult(
                    preset = preset,
                    success = code in 100..599,
                    latencyMs = ms,
                    httpCode = code
                )
            } catch (t: Throwable) {
                val ms = System.currentTimeMillis() - start
                StrategyTestResult(
                    preset = preset,
                    success = false,
                    latencyMs = ms,
                    httpCode = null,
                    error = t.message ?: t.javaClass.simpleName
                )
            } finally {
                activeConn = null
                runCatching { conn.disconnect() }
            }
        } finally {
            runCatching { NativeBridge.stopByeDpi() }
            runCatching { thread.join(1500) }
        }
    }

    private suspend fun waitForPort(host: String, port: Int): Boolean {
        repeat(20) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 100)
                }
                return true
            } catch (_: Throwable) {
                delay(100)
            }
        }
        return false
    }
}
