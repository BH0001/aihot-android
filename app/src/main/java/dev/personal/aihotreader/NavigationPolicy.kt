package dev.personal.aihotreader

import java.net.URI
import java.net.URISyntaxException

/** A single policy for normal navigation, redirects, popups, and sharing. */
object NavigationPolicy {
    const val HOME_URL = "https://aihot.news/"

    enum class Destination { INTERNAL, EXTERNAL, BLOCKED }

    fun classify(url: String?): Destination {
        if (url.isNullOrBlank()) return Destination.BLOCKED

        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return Destination.BLOCKED
        }

        val isHttps = uri.scheme.equals("https", ignoreCase = true)
        val isHttp = uri.scheme.equals("http", ignoreCase = true)
        if ((!isHttps && !isHttp) || uri.isOpaque || uri.host.isNullOrBlank()) {
            return Destination.BLOCKED
        }

        // URI accepts an empty explicit port and ports beyond the TCP range.
        // Reject those before sending an address to either WebView or a browser.
        if (uri.rawAuthority?.endsWith(':') == true ||
            (uri.port != -1 && uri.port !in 1..65535)
        ) {
            return Destination.BLOCKED
        }

        return if (isHttps &&
            uri.host.equals("aihot.news", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null
        ) {
            Destination.INTERNAL
        } else {
            Destination.EXTERNAL
        }
    }

    fun shareableUrl(url: String?): String? =
        url?.takeIf { classify(it) == Destination.INTERNAL }
}
