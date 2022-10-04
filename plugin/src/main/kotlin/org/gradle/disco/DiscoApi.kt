package org.gradle.disco

import org.gradle.api.GradleException
import org.gradle.disco.spec.*
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.platform.Architecture
import org.gradle.platform.OperatingSystem
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit.SECONDS


class DiscoApi {

    val CONNECT_TIMEOUT = SECONDS.toMillis(10).toInt()
    val READ_TIMEOUT = SECONDS.toMillis(20).toInt()

    val SCHEMA = "https"

    val ENDPOINT_ROOT = "api.foojay.io/disco/v3.0"
    val DISTRIBUTIONS_ENDPOIT = "$ENDPOINT_ROOT/distributions"
    val PACKAGES_ENDPOINT = "$ENDPOINT_ROOT/packages"

    val distributions = mutableListOf<Distribution>()

    fun toUri(
        version: JavaLanguageVersion,
        vendor: JvmVendorSpec,
        implementation: JvmImplementation,
        operatingSystem: OperatingSystem,
        architecture: Architecture
    ): URI? {
        val distribution = match(vendor, implementation) ?: return null
        val downloadPackage = match(distribution.name, version, operatingSystem, architecture) ?: return null
        return downloadPackage.links.pkg_download_redirect
    }

    internal fun match(vendor: JvmVendorSpec, implementation: JvmImplementation): Distribution? {
        fetchDistributionsIfMissing()
        return match(distributions, vendor, implementation)
    }

    private fun fetchDistributionsIfMissing() {
        if (distributions.isEmpty()) {
            val con = createConnection(
                DISTRIBUTIONS_ENDPOIT,
                mapOf("include_versions" to "true", "include_synonyms" to "true")
            )
            val json = readResponse(con)
            con.disconnect()

            distributions.addAll(parseDistributions(json))
        }
    }

    internal fun match(distributionName: String, version: JavaLanguageVersion, operatingSystem: OperatingSystem, architecture: Architecture): Package? {
        val con = createConnection(
            PACKAGES_ENDPOINT,
            mapOf(
                "version" to "${version.asInt()}",
                "distro" to distributionName,
                "operating_system" to map(operatingSystem),
                "latest" to "available",
                "directly_downloadable" to "true",
            )
        )
        val json = readResponse(con)
        con.disconnect()

        val packages = parsePackages(json)
        return match(packages, architecture)
    }

    private fun createConnection(endpoint: String, parameters: Map<String, String>): HttpURLConnection {
        val url = URL("$SCHEMA://$endpoint?${toParameterString(parameters)}")
        val con = url.openConnection() as HttpURLConnection
        con.setRequestProperty("Content-Type", "application/json");
        con.requestMethod = "GET"
        con.connectTimeout = CONNECT_TIMEOUT;
        con.readTimeout = READ_TIMEOUT;
        return con
    }

    private fun toParameterString(params: Map<String, String>): String {
        val result = StringBuilder()
        for (param in params) {
            result.append(URLEncoder.encode(param.key, UTF_8))
            result.append("=")
            result.append(URLEncoder.encode(param.value, UTF_8))
            result.append("&")
        }
        if (params.isNotEmpty()) result.delete(result.length - 1, result.length)
        return result.toString()
    }

    private fun readResponse(con: HttpURLConnection): String {
        val status = con.responseCode
        if (status != HttpURLConnection.HTTP_OK) {
            throw GradleException("Requesting vendor list failed: ${readContent(con.errorStream)}")
        }
        return readContent(con.inputStream)
    }

    private fun readContent(stream: InputStream) = stream.bufferedReader().use(BufferedReader::readText)
}