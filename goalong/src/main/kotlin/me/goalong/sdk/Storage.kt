package me.goalong.sdk

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/** 端末に保存するキー(iOS と同じ名前。Install Referrer の 2 つは Android のみ) */
internal object StorageKeys {
    const val INSTALLATION_ID = "goalong.installation_id"
    const val INSTALLATION_REPORTED = "goalong.installation_reported"
    const val REFERRAL_APPLIED = "goalong.referral_applied"
    const val REFERRAL_OFFERING_KEY = "goalong.referral_offering_key"
    const val REFERRAL_PERK_LABEL = "goalong.referral_perk_label"
    const val RC_APP_USER_ID = "goalong.rc_app_user_id"
    const val APPLIED_PERK_LABEL = "goalong.applied_perk_label"
    const val APPLIED_PERK_EXPIRES_AT_MS = "goalong.applied_perk_expires_at_ms"
    const val APPLIED_PERK_ENTITLEMENT = "goalong.applied_perk_entitlement"
    const val INSTALL_REFERRER_STATE = "goalong.install_referrer_state"
    const val INSTALL_REFERRER_CODE = "goalong.install_referrer_code"
}

/**
 * 小さなキー・値の保存先。値は String / Boolean / Long のみ。
 * 書き込みはディスクへの保存に成功したときだけ反映する。失敗したら [StorageException] を投げ、状態は変えない
 */
internal interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean
    fun getLong(key: String): Long?

    /** 複数の書き込みを 1 回の保存にまとめる(値が null のキーは消す) */
    @Throws(StorageException::class)
    fun edit(block: MutableMap<String, Any?>.() -> Unit)

    fun putString(key: String, value: String?) = edit { put(key, value) }
    fun putBoolean(key: String, value: Boolean) = edit { put(key, value) }
}

/** 保存(または保存前の読み込み)に失敗した */
internal class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 共通の読み取り。保存先の実装はマップの読み書きだけを提供する */
internal abstract class MapBackedStore : KeyValueStore {
    protected abstract fun snapshot(): Map<String, Any?>

    override fun getString(key: String): String? = snapshot()[key] as? String
    override fun getBoolean(key: String): Boolean = snapshot()[key] as? Boolean ?: false
    override fun getLong(key: String): Long? = (snapshot()[key] as? Number)?.toLong()
}

/** テスト・プレビュー用のメモリ内ストレージ */
internal class InMemoryStore(initial: Map<String, Any?> = emptyMap()) : MapBackedStore() {
    private val values = initial.filterValues { it != null }.toMutableMap()

    /** テスト用: true の間は保存に失敗する */
    @Volatile var failWrites: Boolean = false

    @Synchronized override fun snapshot(): Map<String, Any?> = values.toMap()

    @Synchronized override fun edit(block: MutableMap<String, Any?>.() -> Unit) {
        val next = values.toMutableMap<String, Any?>()
        next.block()
        if (failWrites) throw StorageException("write failed (test)")
        values.clear()
        next.forEach { (k, v) -> if (v != null) values[k] = v }
    }

    @Synchronized fun values(): Map<String, Any?> = values.toMap()
}

/**
 * ファイルを原子的に読み書きする。本番は [AndroidAtomicFileIo](一時ファイル + fsync + rename)、
 * JVM テストでは一時ファイル + ATOMIC_MOVE の実装に差し替える
 */
internal interface AtomicFileIo {
    /** ファイルが無ければ null。読めなければ IOException */
    @Throws(IOException::class)
    fun read(): ByteArray?

    /** 全体を書き、ディスクに同期してから置き換える。失敗したら旧ファイルを残して IOException */
    @Throws(IOException::class)
    fun write(bytes: ByteArray)
}

/**
 * 一時ファイルに全体を書いて fsync し、rename で置き換える。android.util.AtomicFile は
 * finishWrite の fsync・rename の失敗を例外にしない版があり、保存できなかったのに成功に見えるので使わない
 * (Codex レビュー 2026-10-21)。rename の失敗は戻り値で検出し、旧ファイルは残す
 */
internal class AndroidAtomicFileIo(private val file: File) : AtomicFileIo {
    private val tmp = File(file.path + ".tmp")

    override fun read(): ByteArray? = try {
        file.readBytes()
    } catch (_: FileNotFoundException) {
        null
    }

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("rename failed: ${tmp.path} -> ${file.path}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (e: RuntimeException) {
            tmp.delete()
            throw IOException(e)
        }
    }
}

/**
 * `noBackupFilesDir` に置く JSON ファイル。
 * SharedPreferences は Auto Backup・端末移行で復元され、installation ID や適用済みの状態が
 * 別端末に引き継がれてしまうため使わない(計画書 §2-2)。
 * 保存に成功したときだけメモリのキャッシュを確定する(保存できなかった状態を「保存済み」に見せない)。
 */
internal class JsonFileStore(private val io: AtomicFileIo) : MapBackedStore() {
    constructor(file: File) : this(AndroidAtomicFileIo(file))

    private var cache: Map<String, Any?>? = null

    @Synchronized override fun snapshot(): Map<String, Any?> = try {
        load()
    } catch (e: StorageException) {
        // 読めないときは空として振る舞う(書き込みは edit が拒否するので、既存のファイルは壊さない)
        GoAlongLog.log("読み込みに失敗: ${e.message}")
        emptyMap()
    }

    @Synchronized override fun edit(block: MutableMap<String, Any?>.() -> Unit) {
        val next = load().toMutableMap()
        next.block()
        val cleaned = next.filterValues { it != null }
        val json = JSONObject()
        cleaned.forEach { (k, v) -> json.put(k, v) }
        try {
            io.write(json.toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw StorageException("保存に失敗: ${e.message}", e)
        }
        cache = cleaned
    }

    /** 読めなかったときはキャッシュしない(次の操作で読み直す) */
    private fun load(): Map<String, Any?> {
        cache?.let { return it }
        val bytes = try {
            io.read()
        } catch (e: IOException) {
            throw StorageException("読み込みに失敗: ${e.message}", e)
        }
        val loaded = mutableMapOf<String, Any?>()
        if (bytes != null) {
            try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                for (key in json.keys()) {
                    when (val value = json.get(key)) {
                        is String, is Boolean -> loaded[key] = value
                        is Number -> loaded[key] = value.toLong()
                    }
                }
            } catch (_: JSONException) {
                // 壊れた内容は空として扱い、次の保存で作り直す
            }
        }
        cache = loaded
        return loaded
    }

    companion object {
        const val FILE_NAME = "goalong.json"
    }
}
