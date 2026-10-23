package me.goalong.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "no_backup/${JsonFileStore.FILE_NAME}")

    @Test fun writesReadsAndPersistsAcrossInstances() {
        val store = JsonFileStore(JvmAtomicFileIo(file()))
        assertNull(store.getString(StorageKeys.INSTALLATION_ID))
        assertFalse(store.getBoolean(StorageKeys.REFERRAL_APPLIED))
        store.edit {
            put(StorageKeys.INSTALLATION_ID, "abc")
            put(StorageKeys.REFERRAL_APPLIED, true)
            put(StorageKeys.APPLIED_PERK_EXPIRES_AT_MS, 4_000_000_000_000L)
        }
        val reopened = JsonFileStore(JvmAtomicFileIo(file()))
        assertEquals("abc", reopened.getString(StorageKeys.INSTALLATION_ID))
        assertTrue(reopened.getBoolean(StorageKeys.REFERRAL_APPLIED))
        assertEquals(4_000_000_000_000L, reopened.getLong(StorageKeys.APPLIED_PERK_EXPIRES_AT_MS))
        assertFalse("一時ファイルを残さない", File(file().parentFile, "${JsonFileStore.FILE_NAME}.new").exists())
    }

    @Test fun nullRemovesKey() {
        val store = JsonFileStore(JvmAtomicFileIo(file()))
        store.putString(StorageKeys.REFERRAL_OFFERING_KEY, "intro")
        store.putString(StorageKeys.REFERRAL_OFFERING_KEY, null)
        assertNull(JsonFileStore(JvmAtomicFileIo(file())).getString(StorageKeys.REFERRAL_OFFERING_KEY))
        assertFalse(file().readText().contains(StorageKeys.REFERRAL_OFFERING_KEY))
    }

    @Test fun usesIosKeyNames() {
        val store = JsonFileStore(JvmAtomicFileIo(file()))
        store.edit {
            put(StorageKeys.INSTALLATION_ID, "id")
            put(StorageKeys.INSTALLATION_REPORTED, true)
            put(StorageKeys.REFERRAL_APPLIED, true)
            put(StorageKeys.REFERRAL_OFFERING_KEY, "o")
            put(StorageKeys.RC_APP_USER_ID, "rc")
        }
        val json = org.json.JSONObject(file().readText())
        assertEquals(
            setOf(
                "goalong.installation_id", "goalong.installation_reported", "goalong.referral_applied",
                "goalong.referral_offering_key", "goalong.rc_app_user_id",
            ),
            json.keys().asSequence().toSet(),
        )
    }

    @Test fun corruptedFileIsTreatedAsEmptyAndRewritten() {
        file().parentFile!!.mkdirs()
        file().writeText("{not json")
        val store = JsonFileStore(JvmAtomicFileIo(file()))
        assertNull(store.getString(StorageKeys.INSTALLATION_ID))
        store.putString(StorageKeys.INSTALLATION_ID, "fresh")
        assertEquals("fresh", JsonFileStore(JvmAtomicFileIo(file())).getString(StorageKeys.INSTALLATION_ID))
    }

    @Test fun clientInstallationIdSurvivesRestartWithFileStore() = kotlinx.coroutines.test.runTest {
        val h1 = Harness(this, store = InMemoryStore())
        val fileStore = JsonFileStore(JvmAtomicFileIo(file()))
        val client = GoAlongClient(
            appId = "a", appSlug = null, baseUrl = "https://goalong.me", store = fileStore,
            transport = h1.transport, device = testDevice, referrerSource = null, subscriberCheck = null,
            scope = backgroundScope, sdkVersion = "0.1.0",
        )
        val id = client.installationId
        val reopened = GoAlongClient(
            appId = "a", appSlug = null, baseUrl = "https://goalong.me", store = JsonFileStore(JvmAtomicFileIo(file())),
            transport = h1.transport, device = testDevice, referrerSource = null, subscriberCheck = null,
            scope = backgroundScope, sdkVersion = "0.1.0",
        )
        assertEquals(id, reopened.installationId)
    }

    @Test fun inMemoryStoreBatchesEdits() {
        val store = InMemoryStore()
        store.edit {
            put("a", "1")
            put("b", true)
            put("c", null)
        }
        assertEquals(mapOf("a" to "1", "b" to true), store.values())
    }
}
