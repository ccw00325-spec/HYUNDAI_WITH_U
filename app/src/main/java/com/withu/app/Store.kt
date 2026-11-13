package com.withu.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.withu.core.Chunk
import com.withu.core.EventMark
import com.withu.core.Hash
import com.withu.core.HashChain
import com.withu.core.Retention
import com.withu.core.Sample
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 차량 안 암호화 저장소.
 *  - DB 비밀번호(32B 난수)를 Keystore AES-256-GCM 키로 감싸 보관 → SQLCipher로 DB 전체 암호화
 *  - 1분 묶음마다 SHA-256 사슬 + Keystore EC P-256 서명 (개인키는 Keystore 밖으로 나가지 않음)
 *  - 보관: 운행 단위 keep_until (종료+7일, 사고 확인·리포트 발급 시 +365일), 지울 때 secure_delete
 */
class Store(private val ctx: Context) {
    private val dbFile: File get() = ctx.getDatabasePath("withu.db")
    private var db: SQLiteDatabase? = null
    init { System.loadLibrary("sqlcipher") }

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun aesKey(): SecretKey {
        (ks().getKey(AES, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(AES, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }

    private fun signKey(): PrivateKey {
        (ks().getKey(EC, null) as? PrivateKey)?.let { return it }
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(KeyGenParameterSpec.Builder(EC, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        kpg.generateKeyPair(); L("STORE EC P-256 signing key created in Keystore")
        return ks().getKey(EC, null) as PrivateKey
    }

    fun publicKeyB64(): String { signKey(); return Base64.encodeToString(ks().getCertificate(EC).publicKey.encoded, Base64.NO_WRAP) }

    fun sign(hashHex: String): String {
        val s = Signature.getInstance("SHA256withECDSA"); s.initSign(signKey()); s.update(hashHex.toByteArray())
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    fun verifySig(c: Chunk): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA"); s.initVerify(ks().getCertificate(EC).publicKey); s.update(c.hash.toByteArray())
        s.verify(Base64.decode(c.sig, Base64.NO_WRAP))
    } catch (e: Exception) { false }

    private fun passphrase(): ByteArray {
        val prefs = ctx.getSharedPreferences("k", Context.MODE_PRIVATE)
        prefs.getString("wrapped", null)?.let { w ->
            val raw = Base64.decode(w, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            return c.doFinal(raw.copyOfRange(12, raw.size))
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, aesKey())
        prefs.edit().putString("wrapped", Base64.encodeToString(c.iv + c.doFinal(pass), Base64.NO_WRAP)).apply()
        L("STORE AES-256-GCM key created, DB passphrase wrapped")
        return pass
    }

    @Synchronized fun db(): SQLiteDatabase {
        db?.let { return it }
        dbFile.parentFile?.mkdirs()
        val d = SQLiteDatabase.openOrCreateDatabase(dbFile, passphrase(), null, null, null)
        d.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
        d.execSQL("CREATE TABLE IF NOT EXISTS trip(id INTEGER PRIMARY KEY, started INTEGER, ended INTEGER, keep_until INTEGER, pin_reason TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS chunk(id INTEGER PRIMARY KEY, trip_id INTEGER, m0 INTEGER, m1 INTEGER, n INTEGER, data TEXT, prev TEXT, hash TEXT, sig TEXT)")
        d.execSQL("CREATE INDEX IF NOT EXISTS chunk_time ON chunk(m0, m1)")
        d.execSQL("CREATE TABLE IF NOT EXISTS event(id INTEGER PRIMARY KEY, trip_id INTEGER, t INTEGER, kind TEXT, from_kph REAL, confirmed INTEGER, address TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS statement(id INTEGER PRIMARY KEY, event_t INTEGER, t INTEGER, opening TEXT, transcript TEXT, audio TEXT, audio_sha TEXT, duration_s INTEGER)")
        d.execSQL("CREATE TABLE IF NOT EXISTS report(id INTEGER PRIMARY KEY, rid TEXT UNIQUE, anchor INTEGER, sha TEXT, created INTEGER, chain_ok INTEGER, url TEXT, payer TEXT, json_path TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS deletion_log(id INTEGER PRIMARY KEY, t INTEGER, trip_id INTEGER, started INTEGER, ended INTEGER, chunks INTEGER, reason TEXT)")
        db = d; return d
    }

    private fun lastId(table: String) = db().rawQuery("SELECT max(id) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    // ---------- 운행 / 보관 ----------
    fun newTrip(start: Long): Long {
        db().execSQL("INSERT INTO trip(started,ended,keep_until,pin_reason) VALUES(?,?,?,?)", arrayOf(start, null, Retention.keepOnEnd(start), null))
        return lastId("trip")
    }
    fun endTrip(id: Long, end: Long) = db().execSQL("UPDATE trip SET ended=?, keep_until=max(keep_until, ?) WHERE id=?", arrayOf(end, Retention.keepOnEnd(end), id))
    /** 비정상 종료로 끝나지 않은 운행을 마지막 묶음 시각으로 마감 */
    fun closeDangling(exceptId: Long) {
        db().rawQuery("SELECT id, coalesce((SELECT max(m1) FROM chunk WHERE trip_id=trip.id), started) FROM trip WHERE ended IS NULL AND id<>?", arrayOf(exceptId.toString())).use { c ->
            val list = mutableListOf<Pair<Long, Long>>(); while (c.moveToNext()) list += c.getLong(0) to c.getLong(1)
            list.forEach { (id, end) -> endTrip(id, end); L("TRIP closed dangling id=$id") }
        }
    }
    fun pinTripAt(t: Long, now: Long, reason: String): Long? {
        val id = db().rawQuery("SELECT trip_id FROM chunk WHERE m0<=? AND m1>=? ORDER BY m0 DESC LIMIT 1", arrayOf(t.toString(), t.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: db().rawQuery("SELECT id FROM trip WHERE started<=? ORDER BY started DESC LIMIT 1", arrayOf(t.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: return null
        pin(id, now, reason); return id
    }
    /** 구간과 겹치는 모든 운행을 고정 (리포트 창이 두 운행에 걸칠 때 앞 운행이 7일 뒤 지워지지 않게) */
    fun pinTripsBetween(t0: Long, t1: Long, now: Long, reason: String): List<Long> {
        val ids = mutableListOf<Long>()
        db().rawQuery("SELECT DISTINCT trip_id FROM chunk WHERE m1 > ? AND m0 < ?", arrayOf(t0.toString(), t1.toString())).use { while (it.moveToNext()) ids += it.getLong(0) }
        ids.forEach { pin(it, now, reason) }
        return ids
    }
    fun pin(tripId: Long, now: Long, reason: String) {
        val cur = db().rawQuery("SELECT keep_until FROM trip WHERE id=?", arrayOf(tripId.toString())).use { if (it.moveToFirst()) it.getLong(0) else return }
        db().execSQL("UPDATE trip SET keep_until=?, pin_reason=? WHERE id=?", arrayOf(Retention.pin(cur, now), reason, tripId))
        L("RETENTION pin trip=$tripId 365d reason=$reason")
    }
    fun sweep(now: Long): List<Long> {
        val rows = mutableListOf<TripRow>()
        db().rawQuery("SELECT id,started,ended,keep_until,pin_reason FROM trip", null).use { c ->
            while (c.moveToNext()) rows += TripRow(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getLong(3), c.getString(4), 0) }
        val gone = rows.filter { Retention.expired(it.keepUntil, it.ended, now) }
        for (r in gone) {
            val n = db().rawQuery("SELECT count(*) FROM chunk WHERE trip_id=?", arrayOf(r.id.toString())).use { it.moveToFirst(); it.getInt(0) }
            db().execSQL("DELETE FROM chunk WHERE trip_id=?", arrayOf(r.id)); db().execSQL("DELETE FROM event WHERE trip_id=?", arrayOf(r.id))
            db().execSQL("DELETE FROM trip WHERE id=?", arrayOf(r.id))
            db().execSQL("INSERT INTO deletion_log(t,trip_id,started,ended,chunks,reason) VALUES(?,?,?,?,?,?)", arrayOf(now, r.id, r.started, r.ended, n, "retention_7d"))
        }
        L("RETENTION sweep deleted=${gone.map { it.id }} kept=${rows.size - gone.size}")
        return gone.map { it.id }
    }
    fun trips(): List<TripRow> {
        val out = mutableListOf<TripRow>()
        db().rawQuery("SELECT t.id,t.started,t.ended,t.keep_until,t.pin_reason,(SELECT count(*) FROM chunk c WHERE c.trip_id=t.id) FROM trip t ORDER BY started DESC", null).use { c ->
            while (c.moveToNext()) out += TripRow(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getLong(3), c.getString(4), c.getInt(5)) }
        return out
    }
    fun deletionLog(): List<String> {
        val out = mutableListOf<String>()
        db().rawQuery("SELECT t,trip_id,chunks,reason FROM deletion_log ORDER BY t DESC LIMIT 20", null).use { c -> while (c.moveToNext()) out += "${c.getLong(0)}|${c.getLong(1)}|${c.getInt(2)}|${c.getString(3)}" }
        return out
    }
    /** 시험용: 지난 운행 (8.2일 전 일반 · 3일 전 일반 · 10일 전 사고 고정) */
    fun seedOld(now: Long) {
        fun mk(ageDays: Double, pinned: Boolean) {
            val st = now - (ageDays * Retention.DAY).toLong(); val en = st + 40 * 60_000
            val keep = if (pinned) Retention.pin(Retention.keepOnEnd(en), now - 10 * Retention.DAY) else Retention.keepOnEnd(en)
            db().execSQL("INSERT INTO trip(started,ended,keep_until,pin_reason) VALUES(?,?,?,?)", arrayOf(st, en, keep, if (pinned) "accident_confirmed" else null))
            val id = lastId("trip"); var prev = HashChain.genesis(id, st)
            for (k in 0 until 3) {
                val data = Sample.HEADER + "\n" + Sample(st + k * 60_000, 42f, 'D', false, '0', 0f, true, false, false).csv() + "\n"
                val h = HashChain.seal(prev, data)
                db().execSQL("INSERT INTO chunk(trip_id,m0,m1,n,data,prev,hash,sig) VALUES(?,?,?,?,?,?,?,?)", arrayOf(id, st + k * 60_000, st + k * 60_000 + 100, 1, data, prev, h, sign(h))); prev = h
            }
        }
        mk(8.2, false); mk(3.0, false); mk(10.0, true)
        L("RETENTION seeded 3 old trips (8.2d plain / 3.0d plain / 10.0d pinned)")
    }

    // ---------- 묶음 ----------
    fun insertChunk(c: Chunk) = db().execSQL("INSERT INTO chunk(trip_id,m0,m1,n,data,prev,hash,sig) VALUES(?,?,?,?,?,?,?,?)", arrayOf(c.trip, c.m0, c.m1, c.n, c.data, c.prev, c.hash, c.sig))
    fun chunksBetween(t0: Long, t1: Long): List<Chunk> {
        val out = mutableListOf<Chunk>()
        db().rawQuery("SELECT trip_id,m0,m1,n,data,prev,hash,sig FROM chunk WHERE m1 > ? AND m0 < ? ORDER BY trip_id, m0, id", arrayOf(t0.toString(), t1.toString())).use { c ->
            while (c.moveToNext()) out += Chunk(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)) }
        return out
    }
    fun tamperNear(t: Long) {
        db().execSQL("UPDATE chunk SET data = replace(data, ',D,1,', ',D,0,') WHERE id = (SELECT id FROM chunk WHERE m0 <= ? AND m1 > ? LIMIT 1)", arrayOf(t, t))
        L("TAMPER flipped brake values in chunk covering $t (test only)")
    }
    fun storageBytes(): Long = dbFile.length() + File(ctx.filesDir, "statements").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun storageStats(): String {
        var n = 0; var raw = 0L
        db().rawQuery("SELECT length(data) FROM chunk", null).use { c -> while (c.moveToNext()) { n++; raw += c.getLong(0) } }
        return "chunks=$n raw=${raw}B db=${dbFile.length()}B"
    }

    // ---------- 사건 / 진술 / 리포트 ----------
    fun insertEvent(trip: Long, t: Long, kind: String, from: Float, confirmed: Boolean, address: String?) =
        db().execSQL("INSERT INTO event(trip_id,t,kind,from_kph,confirmed,address) VALUES(?,?,?,?,?,?)", arrayOf(trip, t, kind, from, if (confirmed) 1 else 0, address))
    fun events(since: Long): List<EventRow> {
        val out = mutableListOf<EventRow>()
        db().rawQuery("SELECT t,kind,confirmed,from_kph FROM event WHERE t>=? ORDER BY t DESC", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) out += EventRow(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getFloat(3)) }
        return out
    }
    /** 가장 최근에 확인한 사고 (프로세스가 다시 떠도 신고 가이드·진술 첫 문장이 이어지도록) */
    fun lastConfirmed(since: Long): Accident? =
        db().rawQuery("SELECT t,from_kph,address FROM event WHERE kind='ACCIDENT_CONFIRMED' AND t>=? ORDER BY t DESC LIMIT 1", arrayOf(since.toString())).use { c ->
            if (!c.moveToFirst()) null else { val from = c.getFloat(1); val addr = c.getString(2); Accident(c.getLong(0), from, "restored", addr, com.withu.core.RoadContext.isHighway(from, addr)) }
        }
    fun eventMarks(around: Long, win: Long): List<EventMark> = events(around - win).filter { it.t <= around + win }.map { EventMark(it.t, it.kind, it.confirmed) }
    fun insertStatement(r: StatementRow): Long {
        db().execSQL("INSERT INTO statement(event_t,t,opening,transcript,audio,audio_sha,duration_s) VALUES(?,?,?,?,?,?,?)", arrayOf(r.eventT, r.t, r.opening, r.transcript, r.audio, r.sha, r.durationS))
        return lastId("statement")
    }
    fun setTranscript(id: Long, text: String) = db().execSQL("UPDATE statement SET transcript=? WHERE id=?", arrayOf(text, id))
    fun statements(eventT: Long? = null): List<StatementRow> {
        val out = mutableListOf<StatementRow>()
        val q = if (eventT == null) "SELECT id,event_t,t,opening,transcript,audio,audio_sha,duration_s FROM statement ORDER BY t DESC"
                else "SELECT id,event_t,t,opening,transcript,audio,audio_sha,duration_s FROM statement WHERE abs(event_t-?)<=120000 ORDER BY t"
        db().rawQuery(q, if (eventT == null) null else arrayOf(eventT.toString())).use { c ->
            while (c.moveToNext()) out += StatementRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getInt(7)) }
        return out
    }
    fun insertReport(r: ReportRow, jsonPath: String) =
        db().execSQL("INSERT OR IGNORE INTO report(rid,anchor,sha,created,chain_ok,url,payer,json_path) VALUES(?,?,?,?,?,?,?,?)", arrayOf(r.rid, r.anchor, r.sha, r.created, if (r.chainOk) 1 else 0, r.url, r.payer, jsonPath))
    fun reportAt(anchor: Long): ReportRow? = reports().firstOrNull { it.anchor == anchor }
    fun reports(): List<ReportRow> {
        val out = mutableListOf<ReportRow>()
        db().rawQuery("SELECT rid,anchor,sha,created,chain_ok,url,payer FROM report ORDER BY created DESC", null).use { c ->
            while (c.moveToNext()) out += ReportRow(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3), c.getInt(4) == 1, c.getString(5), c.getString(6)) }
        return out
    }

    fun verifyDb() {
        val header = dbFile.inputStream().use { val b = ByteArray(16); it.read(b); String(b, Charsets.ISO_8859_1) }
        L("VERIFY db header plaintext_sqlite=${header.startsWith("SQLite format 3")} size=${dbFile.length()}B")
        try { SQLiteDatabase.openOrCreateDatabase(dbFile, ByteArray(32), null, null, null).use { it.rawQuery("SELECT count(*) FROM chunk", null).use { c -> c.moveToFirst() } }; L("VERIFY wrong_key_open=SUCCEEDED (BAD)") }
        catch (e: Exception) { L("VERIFY wrong_key_open=FAILED as expected (${e.javaClass.simpleName})") }
        L("VERIFY allowBackup=${(ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0} pubkey_sha=${Hash.sha256(publicKeyB64()).take(12)}")
    }

    companion object { const val AES = "withu_db_key"; const val EC = "withu_sign_key" }
}
