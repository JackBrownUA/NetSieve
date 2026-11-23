package io.github.jackbrownua.netsieve.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

enum class DnsVerdict {
    OK,
    SUSPICIOUS,
    SPOOFED,
    ERROR
}

data class DnsCheckResult(
    val domain: String,
    val systemIps: List<String>,
    val dohIps: List<String>,
    val dohProvider: String,
    val systemLatencyMs: Long,
    val dohLatencyMs: Long,
    val verdict: DnsVerdict,
    val details: String
)

object DnsChecker {

    fun sanitizeDomain(rawDomain: String): String {
        return rawDomain.trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore(":")
            .trim()
            .ifEmpty { "github.com" }
    }

    suspend fun check(rawDomain: String): DnsCheckResult = withContext(Dispatchers.IO) {
        val domain = sanitizeDomain(rawDomain)

        val sysStart = System.currentTimeMillis()
        val systemIps = runCatching {
            InetAddress.getAllByName(domain)
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrElse { emptyList() }
        val sysMs = System.currentTimeMillis() - sysStart

        val dohStart = System.currentTimeMillis()
        var dohProvider = "Cloudflare DoH"
        var dohIps = queryDoh("https://cloudflare-dns.com/dns-query?name=${encode(domain)}&type=A")
        if (dohIps.isEmpty()) {
            dohProvider = "Google DoH"
            dohIps = queryDoh("https://dns.google/resolve?name=${encode(domain)}&type=A")
        }
        val dohMs = System.currentTimeMillis() - dohStart

        if (dohIps.isEmpty() && systemIps.isEmpty()) {
            return@withContext DnsCheckResult(
                domain = domain,
                systemIps = systemIps,
                dohIps = dohIps,
                dohProvider = dohProvider,
                systemLatencyMs = sysMs,
                dohLatencyMs = dohMs,
                verdict = DnsVerdict.ERROR,
                details = "Не удалось получить ответы ни от системного DNS, ни от DoH. Проверьте подключение к сети."
            )
        }

        if (dohIps.isEmpty()) {
            return@withContext DnsCheckResult(
                domain = domain,
                systemIps = systemIps,
                dohIps = dohIps,
                dohProvider = dohProvider,
                systemLatencyMs = sysMs,
                dohLatencyMs = dohMs,
                verdict = DnsVerdict.SUSPICIOUS,
                details = "Системный DNS ответил, но DoH-запрос заблокирован сетью."
            )
        }

        val hasPrivateOrBogon = systemIps.any { isBogonOrPrivate(it) }
        val exactMatch = systemIps.any { it in dohIps }
        val sameSubnet = systemIps.any { sysIp ->
            val sysPrefix = sysIp.split(".").take(2).joinToString(".")
            dohIps.any { dohIp -> dohIp.startsWith("$sysPrefix.") }
        }

        val (verdict, details) = when {
            systemIps.isEmpty() -> DnsVerdict.SPOOFED to
                "Системный DNS не смог разрешить домен, хотя DoH вернул рабочие IP — возможна блокировка на уровне DNS."
            hasPrivateOrBogon -> DnsVerdict.SPOOFED to
                "Системный DNS вернул локальный/заглушечный IP (${systemIps.joinToString()}). Обнаружена подмена DNS."
            exactMatch -> DnsVerdict.OK to
                "Адреса системного DNS и $dohProvider совпадают — подмены не обнаружено."
            sameSubnet -> DnsVerdict.OK to
                "Адреса в одной подсети (разные CDN-узлы) — подмена маловероятна."
            else -> DnsVerdict.SUSPICIOUS to
                "Системный DNS вернул другие IP-подсети, чем $dohProvider. Возможна подмена или проксирование DNS."
        }

        DnsCheckResult(
            domain = domain,
            systemIps = systemIps,
            dohIps = dohIps,
            dohProvider = dohProvider,
            systemLatencyMs = sysMs,
            dohLatencyMs = dohMs,
            verdict = verdict,
            details = details
        )
    }

    private fun queryDoh(urlStr: String): List<String> {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/dns-json")
            }
            try {
                if (conn.responseCode != 200) return@runCatching emptyList()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: return@runCatching emptyList()
                val result = mutableListOf<String>()
                for (i in 0 until answers.length()) {
                    val item = answers.optJSONObject(i) ?: continue
                    if (item.optInt("type") == 1) {
                        val ip = item.optString("data")
                        if (ip.isNotBlank()) result.add(ip)
                    }
                }
                result.distinct()
            } finally {
                conn.disconnect()
            }
        }.getOrElse { emptyList() }
    }

    private fun isBogonOrPrivate(ip: String): Boolean {
        return ip == "0.0.0.0" ||
            ip.startsWith("127.") ||
            ip.startsWith("10.") ||
            ip.startsWith("192.168.") ||
            ip.startsWith("169.254.") ||
            (ip.startsWith("172.") && (ip.split(".").getOrNull(1)?.toIntOrNull() in 16..31))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
