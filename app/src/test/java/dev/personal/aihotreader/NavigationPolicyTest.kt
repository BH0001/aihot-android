package dev.personal.aihotreader

import dev.personal.aihotreader.NavigationPolicy.Destination.BLOCKED
import dev.personal.aihotreader.NavigationPolicy.Destination.EXTERNAL
import dev.personal.aihotreader.NavigationPolicy.Destination.INTERNAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationPolicyTest {
    @Test
    fun homeAndArticlePathsStayInApp() {
        assertDestination(INTERNAL,
            NavigationPolicy.HOME_URL,
            "https://aihot.news",
            "https://aihot.news/items/story-123",
            "https://aihot.news/search?q=AI&sort=latest#results",
        )
    }

    @Test
    fun schemeAndDnsHostAreCaseInsensitive() {
        assertDestination(INTERNAL, "HTTPS://AIHOT.NEWS/items/CaseSensitivePath")
        assertDestination(EXTERNAL, "HTTP://AIHOT.NEWS/", "HtTpS://EXAMPLE.COM/")
    }

    @Test
    fun onlyStandardHttpsOriginStaysInApp() {
        assertDestination(INTERNAL, "https://aihot.news:443/")
        assertDestination(EXTERNAL,
            "http://aihot.news/",
            "https://aihot.news:8443/",
            "https://aihot.news:80/",
        )
    }

    @Test
    fun subdomainsAndHostSuffixLookalikesLeaveApp() {
        assertDestination(EXTERNAL,
            "https://www.aihot.news/",
            "https://aihot.news.evil.example/",
            "https://evil-aihot.news/",
            "https://aihot.news./",
            "https://example.com/aihot.news",
            "https://example.com/?next=https://aihot.news/",
        )
    }

    @Test
    fun userInfoNeverGrantsInternalAccess() {
        assertDestination(EXTERNAL,
            "https://aihot.news@evil.example/",
            "https://reader@aihot.news/",
            "https://reader:password@aihot.news/",
            "https://@aihot.news/",
            "https://aihot.news%40evil.example@evil.example/",
        )
    }

    @Test
    fun onlyHttpAndHttpsCanReachAnExternalBrowser() {
        assertDestination(BLOCKED,
            "javascript:alert(1)",
            "data:text/html,hello",
            "file:///sdcard/private.txt",
            "content://provider/private",
            "intent://aihot.news/#Intent;scheme=https;end",
            "mailto:reader@aihot.news",
            "ftp://aihot.news/",
            "about:blank",
        )
    }

    @Test
    fun missingOrAmbiguousHostsAreBlocked() {
        assertDestination(BLOCKED,
            "https:///items/123",
            "https:aihot.news/items/123",
            "//aihot.news/items/123",
            "/items/123",
            "https://",
            "https://aihot.news\\@evil.example/",
            "https://evil.example\\aihot.news/",
        )
    }

    @Test
    fun invalidPortsAreBlocked() {
        assertDestination(BLOCKED,
            "https://aihot.news:/",
            "https://aihot.news:-1/",
            "https://aihot.news:0/",
            "https://aihot.news:65536/",
            "https://aihot.news:four/",
            "https://example.com:2147483648/",
        )
        assertDestination(EXTERNAL, "https://example.com:65535/")
    }

    @Test
    fun emptyWhitespaceAndMalformedEscapesAreBlocked() {
        assertDestination(BLOCKED,
            null, "", " ", "\n", " https://aihot.news/",
            "https://aihot.news/ ", "https://aihot.news/\n",
            "https://aihot.news/%", "https://aihot.news/%GG",
        )
    }

    @Test
    fun encodedPathDoesNotChangeOrigin() {
        assertDestination(INTERNAL,
            "https://aihot.news/items/%E6%96%B0%E9%97%BB",
            "https://aihot.news/%2F%2Fevil.example/",
            "https://aihot.news/?redirect=https%3A%2F%2Fexample.com",
        )
    }

    @Test
    fun encodedAuthoritiesAreNotTrusted() {
        assertDestination(BLOCKED,
            "https://%61ihot.news/",
            "https://aihot%2Enews/",
            "https://aihot.news%2Fevil.example/",
            "https://aihot.news%5C@evil.example%2F/",
            "https://aihot.news%00.evil.example/",
        )
    }

    @Test
    fun ipv4AndIpv6ExternalUrlsAreSupported() {
        assertDestination(EXTERNAL,
            "https://127.0.0.1/",
            "https://[::1]/",
            "http://[2001:db8::1]:8080/",
        )
        assertDestination(BLOCKED, "https://[::1]:/", "https://[::1/")
    }

    @Test
    fun sharingPreservesTheExactInternalArticleAddress() {
        val article = "HTTPS://AIHOT.NEWS:443/items/123?source=a%2Fb#discussion"
        assertEquals(article, NavigationPolicy.shareableUrl(article))
    }

    @Test
    fun sharingNeverLeaksAnExternalOrInvalidAddress() {
        listOf(null, "", "https://example.com/", "http://aihot.news/",
            "https://user:secret@aihot.news/", "javascript:alert(1)")
            .forEach { assertNull(it, NavigationPolicy.shareableUrl(it)) }
    }

    private fun assertDestination(
        expected: NavigationPolicy.Destination,
        vararg urls: String?,
    ) {
        urls.forEach { assertEquals("Unexpected destination for $it", expected, NavigationPolicy.classify(it)) }
    }
}
