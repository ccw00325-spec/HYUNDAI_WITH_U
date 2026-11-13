package com.withu.core

import java.security.MessageDigest

object Hash {
    fun sha256(s: String): String = sha256(s.toByteArray(Charsets.UTF_8))
    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

/** 1분 묶음 하나 */
data class Chunk(val trip: Long, val m0: Long, val m1: Long, val n: Int, val data: String, val prev: String, val hash: String, val sig: String)

data class ChunkCheck(val chunk: Chunk, val hashOk: Boolean, val linkOk: Boolean, val sigOk: Boolean) {
    val ok get() = hashOk && linkOk && sigOk
}

/**
 * 해시 사슬: hash = SHA256(prev ‖ SHA256(data)), 첫 prev = SHA256("trip:<id>:<시작시각>").
 * 한 묶음이라도 바뀌면 그 묶음의 hash가, 빠지거나 순서가 바뀌면 다음 묶음의 link가 어긋난다.
 */
object HashChain {
    fun genesis(trip: Long, start: Long) = Hash.sha256("trip:$trip:$start")
    fun seal(prev: String, data: String) = Hash.sha256(prev + Hash.sha256(data))

    fun verify(chunks: List<Chunk>, sigOk: (Chunk) -> Boolean): List<ChunkCheck> {
        val out = ArrayList<ChunkCheck>(chunks.size)
        var prev: Chunk? = null
        for (c in chunks) {
            val hashOk = seal(c.prev, c.data) == c.hash
            val linkOk = prev == null || prev.trip != c.trip || c.prev == prev.hash
            out += ChunkCheck(c, hashOk, linkOk, sigOk(c))
            prev = c
        }
        return out
    }
}

/** 1분 단위로 샘플을 모아 봉인한다. 저장·서명은 밖에서 넘겨받는다. */
class ChunkSealer(private val trip: Long, start: Long, private val sign: (String) -> String, private val emit: (Chunk) -> Unit) {
    var prev = HashChain.genesis(trip, start); private set
    private val buf = StringBuilder()
    private var first = -1L; private var last = -1L; private var n = 0; private var minute = Long.MIN_VALUE

    @Synchronized fun add(s: Sample) {
        val m = Math.floorDiv(s.t, 60_000L)
        if (n > 0 && m != minute) flush()
        minute = m
        if (n == 0) first = s.t
        buf.append(s.csv()).append('\n'); last = s.t; n++
    }

    /** 아직 봉인하지 않은 이번 분의 샘플 (미리보기용 · 봉인하지 않으므로 1분 묶음이 쪼개지지 않는다) */
    @Synchronized fun pending(): List<Sample> = Sample.parseAll(buf.toString())

    /** 모인 샘플을 묶음으로 봉인. 비어 있으면 null */
    @Synchronized fun flush(): Chunk? {
        if (n == 0) return null
        val data = Sample.HEADER + "\n" + buf
        val h = HashChain.seal(prev, data)
        val c = Chunk(trip, first, last + 100, n, data, prev, h, sign(h))
        emit(c); prev = h; buf.setLength(0); n = 0
        return c
    }
}

/** 보관 정책: 일반 운행 7일, 사고 확인·리포트 발급 1년 (고정 기간은 1일~3년 범위로만 받는다) */
object Retention {
    const val DAY = 86_400_000L
    const val KEEP_DAYS = 7
    const val PIN_DAYS = 365
    const val MAX_DAYS = 365 * 3

    fun keepOnEnd(end: Long) = end + KEEP_DAYS * DAY
    /** 고정: 지금보다 앞당기지는 않는다 */
    fun pin(currentKeep: Long, now: Long, days: Int = PIN_DAYS): Long = maxOf(currentKeep, now + days.coerceIn(1, MAX_DAYS) * DAY)
    /** 끝난 운행이고 보관 기한이 지났을 때만 지운다 (기한과 같은 순간은 아직 보관) */
    fun expired(keepUntil: Long, ended: Long?, now: Long) = ended != null && keepUntil < now
    fun daysLeft(keepUntil: Long, now: Long): Int = Math.floorDiv(keepUntil - now + DAY - 1, DAY).toInt()
}
