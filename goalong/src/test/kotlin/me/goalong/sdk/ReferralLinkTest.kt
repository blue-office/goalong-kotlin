package me.goalong.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS(GoAlongTests.swift)と Flutter(link_test.dart)のケースをすべて移植 */
class ReferralLinkTest {
    private fun extract(text: String, host: String = "goalong.me", slug: String? = null) =
        ReferralLink.extract(text, host, slug)

    @Test fun extractsCodeFromLpUrl() {
        assertEquals("MIKA", extract("https://goalong.me/booknotion/MIKA"))
        assertEquals("MIKA", extract("https://goalong.me/booknotion/mika"))
    }

    @Test fun fullWidthIsNormalizedByNfkc() {
        assertEquals("MIKA", extract("https://goalong.me/app/%EF%BC%AD%EF%BC%A9%EF%BC%AB%EF%BC%A1"))
    }

    @Test fun rejectsSingleSegmentAndInvalidCharset() {
        assertNull(extract("https://goalong.me/MIKA"))
        assertNull(extract("https://goalong.me/app/mi-ka"))
        assertNull(extract("https://goalong.me/app/ab"))
    }

    @Test fun extractsFromPastedTextAndIgnoresPlainCode() {
        assertEquals("MIKA", extract("https://goalong.me/booknotion/MIKA"))
        assertEquals("MIKA", extract("  https://goalong.me/booknotion/mika  "))
        assertNull(extract("MIKA"))
    }

    @Test fun rejectsForeignHosts() {
        assertNull(extract("https://example.com/private/SECRET123"))
        assertNull(extract("https://evil.example/goalong.me/MIKA"))
    }

    @Test fun rejectsHttpPortAndUserInfo() {
        assertNull(extract("http://goalong.me/booknotion/MIKA"))
        assertNull(extract("https://goalong.me:8443/booknotion/MIKA"))
        assertNull(extract("https://user@goalong.me/booknotion/MIKA"))
        assertNull(extract("https://user:pw@goalong.me/booknotion/MIKA"))
    }

    @Test fun rejectsExtraPathComponents() {
        assertNull(extract("https://goalong.me/a/b/MIKA"))
    }

    @Test fun enforcesConfiguredSlugCaseInsensitively() {
        assertEquals("MIKA", extract("https://goalong.me/booknotion/MIKA", slug = "booknotion"))
        assertEquals("MIKA", extract("https://goalong.me/BookNotion/MIKA", slug = "booknotion"))
        assertNull(extract("https://goalong.me/another-app/MIKA", slug = "booknotion"))
    }

    @Test fun withoutSlugStillRequiresTwoComponents() {
        assertEquals("MIKA", extract("https://goalong.me/whatever/MIKA"))
        assertNull(extract("https://goalong.me/MIKA"))
    }

    @Test fun acceptsCaseInsensitiveHttpsAndDecodedSegments() {
        for (input in listOf(
            "HTTPS://GOALONG.ME/MyApp/mika",
            "hTtPs://a-1.goalong.me/myapp/MIKA?utm=1/extra#fragment/path",
            "  https://goalong.me/my%61pp/%4D%49%4B%41  \n",
            "https://goalong.me/myapp/%EF%BC%AD%EF%BC%A9%EF%BC%AB%EF%BC%A1",
            "https://goalong.me/myapp/ｍｉｋａ",
            "https://goalong.me/myapp/%20mika%09",
        )) {
            assertEquals(input, "MIKA", extract(input, slug = "myapp"))
        }
    }

    @Test fun rejectsInvalidAuthorities() {
        for (authority in listOf(
            "goalong.me:443", "goalong.me:", "user@goalong.me", "user:pw@goalong.me",
            "goal%6Fng.me", "evil.example\\.goalong.me", "bad host.goalong.me",
            "bad\thost.goalong.me", "bad\nhost.goalong.me", "bad host.goalong.me",
            "bad_host.goalong.me", ".goalong.me", "www..goalong.me", "goalong.me.",
            "日本語.goalong.me", "evilgoalong.me", "goalong.me.evil.example",
        )) {
            val input = "https://$authority/app/MIKA"
            assertNull(input, extract(input))
        }
    }

    @Test fun rejectsEmptyExtraAndInvalidDecodedSegments() {
        for (path in listOf(
            "", "/", "/MIKA", "//MIKA", "/app/", "//app/MIKA", "/app//MIKA",
            "/app/MIKA/", "/app/MIKA/extra", "/./MIKA", "/../MIKA",
            "/%2e/MIKA", "/%2E%2E/MIKA", "/a%2Fb/MIKA", "/a%5Cb/MIKA",
            "/a\\b/MIKA", "/app%/MIKA", "/app%GG/MIKA", "/%FF/MIKA",
            "/app/MIKA%", "/app/MIKA%GG", "/app/%C3%28", "/app/MI%2FKA",
            "/app/MI%5CKA", "/app/%2520MIKA", "/app/AB", "/app/MI-KA",
        )) {
            val input = "https://goalong.me$path"
            assertNull(input, extract(input))
        }
    }

    @Test fun honorsConfiguredHostAndDecodedSlug() {
        val host = "referrals.example"
        assertEquals("MIKA", extract("https://a-1.referrals.example/my%61pp/MIKA", host, "myapp"))
        assertNull(extract("https://goalong.me/myapp/MIKA", host, "myapp"))
        assertNull(extract("https://referrals.example/otherapp/MIKA", host, "myapp"))
    }

    // Flutter link_test.dart

    @Test fun flutterNormalizesHostSlugAndCompatibilityCharacters() {
        for (value in listOf(
            "https://goalong.me/booknotion/mika",
            " HTTPS://GOALONG.ME/BookNotion/ｍｉｋａ ",
            "https://links.goalong.me/booknotion/ＭＩＫＡ?utm_source=video#ref",
            "https://goalong.me/booknotion/%EF%BC%AD%EF%BC%A9%EF%BC%AB%EF%BC%A1",
        )) {
            assertEquals(value, "MIKA", extract(value, slug = "booknotion"))
        }
        assertEquals("1FFI", extract("https://goalong.me/booknotion/①ﬃ", slug = "booknotion"))
    }

    @Test fun flutterRejectsInvalidBoundaries() {
        for (value in listOf(
            "http://goalong.me/booknotion/MIKA",
            "goalong://booknotion/MIKA",
            "https://goalong.me:443/booknotion/MIKA",
            "https://goalong.me:8443/booknotion/MIKA",
            "https://goalong.me:/booknotion/MIKA",
            "https://user@goalong.me/booknotion/MIKA",
            "https://@goalong.me/booknotion/MIKA",
            "https://user:password@goalong.me/booknotion/MIKA",
            "https://example.com/booknotion/MIKA",
            "https://evilgoalong.me/booknotion/MIKA",
            "https://goalong.me.evil.test/booknotion/MIKA",
            "https://goalong.me./booknotion/MIKA",
            "https://goalong%2eme/booknotion/MIKA",
            "https://goalong.me/booknotion/MIKA/extra",
            "https://goalong.me/booknotion/MIKA/",
            "https://goalong.me//booknotion/MIKA",
            "https://goalong.me/booknotion",
            "https://goalong.me/other/MIKA",
            "https://goalong.me/booknotion/AB",
            "https://goalong.me/booknotion/ABCDEFGHIJKLMNOPQRSTU",
            "https://goalong.me/booknotion/AB-CD",
            "https://goalong.me/booknotion/MI%2FKA",
            "https://goalong.me/booknotion/MI%5CKA",
            "https://goalong.me/booknotion/%FF",
            "https://goalong.me/booknotion/%ZZ",
            "https://goalong.me/extra/../booknotion/MIKA",
            "https://goalong.me/booknotion/日本語",
        )) {
            assertNull(value, extract(value, slug = "booknotion"))
        }
        assertEquals("ABC", extract("https://goalong.me/booknotion/ABC", slug = "booknotion"))
        assertEquals(
            "ABCDEFGHIJKLMNOPQRST",
            extract("https://goalong.me/booknotion/ABCDEFGHIJKLMNOPQRST", slug = "booknotion"),
        )
    }

    @Test fun flutterAllowsOnlyBaseHostAndSubdomains() {
        val host = "ref.example.com"
        assertEquals("ABC", extract("https://ref.example.com/booknotion/ABC", host))
        assertEquals("ABC", extract("https://links.ref.example.com/booknotion/ABC", host))
        assertNull(extract("https://goalong.me/booknotion/ABC", host))
        assertNull(extract("https://example.com/booknotion/ABC", host))
    }

    @Test fun nfkcHandlesCompatibilityAndComposition() {
        assertEquals("MIKA", ReferralLink.normalize("　ｍｉｋａ　"))
        assertEquals("1FFIK", ReferralLink.normalize("①ﬃK"))
        assertEquals("SSAB", ReferralLink.normalize("ßab"))
        assertEquals("MIKA", ReferralLink.normalize("𝕄𝕚𝕜𝕒"))
        assertFalse(ReferralLink.isValidCode(ReferralLink.normalize("ÅBC")))
        assertFalse(ReferralLink.isValidCode(ReferralLink.normalize("AB​C")))
    }

    @Test fun normalizedCodeRoutesLinksAndPlainCodes() {
        assertEquals("MIKA", ReferralLink.normalizedCode("　ｍｉｋａ　", "goalong.me", null))
        assertEquals("MIKA", ReferralLink.normalizedCode("https://goalong.me/x/mika", "goalong.me", null))
        assertNull(ReferralLink.normalizedCode("https://private.example/account/SECRET", "goalong.me", null))
        assertNull(ReferralLink.normalizedCode("AB", "goalong.me", null))
        assertNull(ReferralLink.normalizedCode("A-B-C", "goalong.me", null))
    }
}
