package com.withu.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.withu.core.AccidentDetector
import com.withu.core.ChunkSealer
import com.withu.core.Decision
import com.withu.core.DetectorConfig
import com.withu.core.GuideFlow
import com.withu.core.Hash
import com.withu.core.LogPrivacy
import com.withu.core.RoadType
import com.withu.core.Telltale
import com.withu.core.Cruise
import com.withu.core.SirenAnchor
import com.withu.core.SweepSchedule
import com.withu.core.RoadContext
import com.withu.core.Sample
import com.withu.core.StatementPrompt
import com.withu.core.Turn
import com.withu.core.Gear
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 시동부터 하차까지 화면과 상관없이 도는 포그라운드 서비스.
 * 차량 신호 수집 → 1분 묶음 봉인·서명 → 사고 추정 → 대응 안내(TTS) → 진술 녹음 → 리포트 발급을 모두 맡고, 화면은 AppState를 보여 주기만 한다.
 */
class RecorderService : Service() {
    companion object {
        @Volatile var inst: RecorderService? = null
        /** 이 시간 안에 확인한 사고는 프로세스가 다시 떠도 이어서 보여 준다 */
        const val RESTORE_WINDOW_MS = 3_600_000L
    }

    private lateinit var h: Handler
    private val ui = Handler(Looper.getMainLooper())
    lateinit var store: Store; private set
    lateinit var settings: Settings; private set
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private var fused: FusedLocation? = null
    private var navi: NaviHelper? = null
    private var tts: ai.pleos.playground.tts.TextToSpeech? = null
    private var stt: ai.pleos.playground.stt.SpeechToText? = null
    private var detector = AccidentDetector()
    private val guide = GuideFlow()
    private var sealer: ChunkSealer? = null
    var tripId = -1L; private set
    var bubble: Bubble? = null; private set
    private var ticks = 0L
    private var lastSpeedForGuide = 0f
    private var lastSweep = 0L

    override fun onBind(i: Intent?): IBinder? = null

    /** 기록 스레드에서 난 예외가 서비스 전체를 죽이지 않게 막고 로그만 남긴다 */
    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { L("ERROR $what ${e.javaClass.simpleName}: ${e.message}") }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        L("SVC onStartCommand from=${i?.getStringExtra("from") ?: if (i == null) "system-restart" else "activity"}")
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate(); inst = this
        LogPrivacy.redact = !BuildConfig.DEBUG
        settings = Settings(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "주행 기록", NotificationManager.IMPORTANCE_LOW))
        startFg(false)
        bubble = Bubble(this)
        ui.postDelayed({ if (!MainActivity.visible) bubble?.show("service started without screen") }, 2000)
        val ht = HandlerThread("withu-rec").apply { start() }; h = Handler(ht.looper)
        store = Store(this)
        h.post { safe("trip start") {
            val now = System.currentTimeMillis()
            tripId = store.newTrip(now); store.closeDangling(tripId); store.sweep(now); lastSweep = now
            sealer = ChunkSealer(tripId, now, store::sign) { c -> store.insertChunk(c); L("CHUNK trip=${c.trip} n=${c.n} hash=${c.hash.take(8)} prev=${c.prev.take(8)} signed") }
            L("TRIP start id=$tripId (keep 7d unless accident/report)")
            if (AppState.accident.value == null) store.lastConfirmed(now - RESTORE_WINDOW_MS)?.let { AppState.accident.value = it; L("ACCIDENT restored from DB t=${it.t} highway=${it.highway}") }
            refresh()
        } }
        setupVehicle(); setupCar(); setupFused(); setupNavi(); setupTts(); setupStt()
        h.postDelayed(object : Runnable {
            override fun run() {
                try { step() } catch (e: Exception) { L("ERROR tick ${e.javaClass.simpleName}: ${e.message}") }
                h.postDelayed(this, 100)
            }
            fun step() {
                val now = System.currentTimeMillis()
                val s = Sample(now, Sig.speedKph, Sig.gear, Sig.brake, Sig.turn, Sig.steer, Sig.lowBeam, Sig.wiper, Sig.driverDoor, Sig.lat, Sig.lon, Sig.speedLimit, Sig.cruise, Sig.telltales)
                sealer?.add(s)
                Sig.speedKph?.let { if (it > 1f) lastSpeedForGuide = it; if (it > 5f) lastMovingAt = now }
                for (d in detector.tick(s, Sig.airbag)) onDecision(d)
                ticks++
                if (ticks % 10 == 0L) AppState.tick.value = now
                if (ticks % 100 == 0L) { L("TICK spd=${Sig.speedKph} gear=${Sig.gear} brk=${Sig.brake} turn=${Sig.turn} door=${Sig.driverDoor} loc=${LogPrivacy.loc(Sig.lat, Sig.lon)}"); refresh() }
                if (SweepSchedule.due(lastSweep, now)) { lastSweep = now; safe("periodic sweep") { store.sweep(now); refresh() } }
            }
        }, 500)
        AppState.serviceAlive.value = true
    }

    override fun onDestroy() {
        L("SVC onDestroy")
        try { sealer?.flush(); store.endTrip(tripId, System.currentTimeMillis()) } catch (_: Exception) {}
        bubble?.hide("service destroyed"); AppState.serviceAlive.value = false; inst = null
        super.onDestroy()
    }

    private fun startFg(mic: Boolean) {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "rec").setContentTitle("WiTH U 주행 기록 중").setContentText("7일 보관 후 자동 삭제 · 사고 기록은 1년 보존")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(pi).setOngoing(true).build()
        val type = if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try { startForeground(1, n, type); L("FGS started mic=$mic") } catch (e: Exception) { L("FGS start FAILED mic=$mic ${e.javaClass.simpleName}: ${e.message}") }
    }

    /** 목록 상태를 화면으로 */
    fun refresh() = h.post {
        try {
            val now = System.currentTimeMillis()
            AppState.trips.value = store.trips()
            AppState.events.value = store.events(now - 7 * 86_400_000L)
            AppState.reports.value = store.reports()
            AppState.statements.value = store.statements()
            AppState.storageBytes.value = store.storageBytes()
        } catch (e: Exception) { L("REFRESH failed $e") }
    }

    // ---------------- 사고 추정 ----------------
    private fun onDecision(d: Decision) {
        when (d) {
            is Decision.HardBrake -> { lastBrakeAt = d.t; lastBrakeFrom = d.fromKph; L("EVENT hard_brake ${d.fromKph}→${d.toKph}kph (mark only)"); store.insertEvent(tripId, d.t, "HARD_BRAKE", d.fromKph, false, null) }
            is Decision.Stopped -> L("STAGE stopped ${d.afterMs}ms after hard brake gear=${Sig.gear}")
            is Decision.NoPrompt -> L("DECISION no prompt reason=${d.reason} (kept in 7-day log)")
            is Decision.Suspect -> { L("DECISION suspect: hard brake + stop + P (${d.parkAfterStopMs}ms after stop)"); ask(Pending(d.eventT, d.fromKph, "hard_brake+stop+P")) }
            is Decision.Airbag -> { L("DECISION airbag deployed -> accident without question"); store.insertEvent(tripId, d.t, "AIRBAG", Sig.speedKph ?: 0f, false, null); confirm(Pending(d.t, lastSpeedForGuide, "airbag")) }
        }
    }

    /** "지금 사고가 난 상황인가요?" — 이미 묻는 중이면 새 질문을 겹쳐 띄우지 않는다 */
    fun ask(p: Pending) {
        if (AppState.pending.value != null) { L("ASK ignored: already pending ${AppState.pending.value}"); return }
        AppState.pending.value = p
        bubble?.setState("alert")
        say("지금 사고가 난 상황인가요?")
        ui.post {
            AppState.go(Route.CONFIRM)
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                L("EVENT_UI confirm card launched why=${p.why} activityVisible=${MainActivity.visible}")
            } catch (e: Exception) { L("EVENT_UI launch FAILED ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    fun siren(src: String) = h.post { safe("siren") { sirenInner(src) } }
    private fun sirenInner(src: String) {
        val now = System.currentTimeMillis()
        L("SIREN pressed src=$src gear=${Sig.gear} spd=${Sig.speedKph}")
        store.insertEvent(tripId, now, "SIREN", Sig.speedKph ?: 0f, false, null)
        // 급제동 뒤 3분 안이고 그 뒤로 다시 달리지 않았다면, 급제동 직전 속도를 사고 속도로 본다 (고속도로 판단에 사용)
        val a = SirenAnchor.resolve(now, Sig.speedKph, lastBrakeAt, lastBrakeFrom, lastMovingAt)
        L("SIREN anchor sameStop=${a.sameStop} t=${a.t} from=${a.fromKph}kph")
        ask(Pending(a.t, a.fromKph, "siren"))
    }
    @Volatile private var lastBrakeAt = 0L
    @Volatile private var lastBrakeFrom = 0f
    @Volatile private var lastMovingAt = 0L

    fun answer(yes: Boolean) = h.post { safe("answer") { answerInner(yes) } }
    private fun answerInner(yes: Boolean) {
        val p = AppState.pending.value ?: Pending(System.currentTimeMillis(), Sig.speedKph ?: 0f, "manual")
        AppState.pending.value = null; bubble?.setState("rec")
        if (!yes) { store.insertEvent(tripId, p.t, "DISMISSED", p.fromKph, false, null); L("ANSWER no -> not pinned, stays 7 days"); ui.post { AppState.home() }; refresh(); return }
        confirm(p)
    }

    private fun confirm(p: Pending) {
        try { navi?.getCurrentLocationInfo() } catch (_: Exception) {}
        val now = System.currentTimeMillis()
        store.insertEvent(tripId, p.t, "ACCIDENT_CONFIRMED", p.fromKph, true, AppState.address.value)
        store.pin(tripId, now, "accident_confirmed")
        store.pinTripsBetween(p.t - 300_000, now, now, "accident_confirmed")
        h.postDelayed({ safe("guide start") {
            val addr = AppState.address.value
            val road = RoadContext.classify(p.fromKph, addr); val hw = road != RoadType.GENERAL
            AppState.accident.value = Accident(p.t, p.fromKph, p.why, addr, hw)
            val first = guide.start(road); pushGuide()
            say(when (road) {
                RoadType.HIGHWAY -> "고속도로 사고로 보여요. 2차 사고를 막는 순서로 안내할게요. 비상등, 트렁크, 가드레일 밖, 신고 순서예요."
                RoadType.TUNNEL -> "터널 안 사고로 보여요. 차를 세우고 유도등을 따라 비상구로 나가는 순서로 안내할게요."
                else -> "일반 도로 사고로 보여요. 순서대로 안내할게요."
            })
            say(first.speech)
            L("ACCIDENT confirmed why=${p.why} from=${p.fromKph}kph road=$road address=${LogPrivacy.text(addr)} -> trip pinned 365d, guide=${guide.steps}")
            ui.post { AppState.back.clear(); AppState.route.value = Route.GUIDE }
            refresh()
        } }, 700)
    }

    // ---------------- 대응 안내 ----------------
    private fun pushGuide() { AppState.guide.value = GuideUi(guide.steps, guide.index, guide.paused, guide.highway, guide.done, guide.road.ko) }
    fun guideNext(src: String) = h.post {
        if (guide.next()) { L("GUIDE next by $src -> ${guide.current}"); guide.current?.let { say(it.speech) } ?: L("GUIDE done") }
        else L("GUIDE next ignored (paused=${guide.paused} active=${guide.active})")
        pushGuide()
    }
    private fun onDoor(open: Boolean) = h.post {
        val wasPaused = guide.paused
        val speak = guide.onDoor(open)
        if (guide.paused && !wasPaused) L("GUIDE paused: driver door opened")
        if (wasPaused && !guide.paused) { L("GUIDE resumed: driver door closed -> ${guide.current}"); if (speak) guide.current?.let { say(it.speech) } }
        pushGuide()
    }

    // ---------------- 진술 녹음 (AudioRecord → WAV + Pleos STT 받아쓰기) ----------------
    @Volatile private var recording = false
    private var recThread: Thread? = null
    private var curStatementId = -1L

    fun statementOpening(): String {
        val a = AppState.accident.value
        return StatementPrompt.opening(a?.t ?: System.currentTimeMillis(), a?.address ?: AppState.address.value ?: Sig.lat?.let { "%.4f, %.4f".format(it, Sig.lon) })
    }

    fun startRecording(maxSec: Int = 90) {
        if (recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { AppState.toast.value = "마이크 권한이 필요해요"; L("REC denied: RECORD_AUDIO not granted"); return }
        startFg(true)
        val opening = statementOpening()
        val evT = AppState.accident.value?.t ?: System.currentTimeMillis()
        val f = File(filesDir, "statements/st_${System.currentTimeMillis()}.wav").also { it.parentFile?.mkdirs() }
        recording = true; AppState.rec.value = RecState(true, 0, 0f)
        try { stt?.request() } catch (e: Exception) { L("STT request FAILED $e") }
        recThread = Thread {
            val rate = 16_000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var total = 0L
            try {
                @Suppress("MissingPermission")
                val ar = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
                RandomAccessFile(f, "rw").use { raf ->
                    raf.write(ByteArray(44)); ar.startRecording(); L("REC start mic -> ${f.name} opening=${LogPrivacy.text(opening)}")
                    val buf = ByteArray(3200); val t0 = System.currentTimeMillis()
                    while (recording && System.currentTimeMillis() - t0 < maxSec * 1000L) {
                        val n = ar.read(buf, 0, buf.size); if (n <= 0) continue
                        raf.write(buf, 0, n); total += n
                        try { stt?.sendAudio(buf.copyOf(n)) } catch (_: Exception) {}
                        var sum = 0.0; var i = 0; while (i + 1 < n) { val v = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8); sum += v.toShort() * v.toShort().toDouble(); i += 2 }
                        AppState.rec.value = RecState(true, ((System.currentTimeMillis() - t0) / 1000).toInt(), (sqrt(sum / (n / 2)) / 8000).toFloat().coerceIn(0f, 1f))
                    }
                    ar.stop(); ar.release()
                    writeWavHeader(raf, total, rate)
                }
            } catch (e: Exception) { L("REC FAILED ${e.javaClass.simpleName}: ${e.message}") }
            recording = false
            try { stt?.completeAudioSend() } catch (_: Exception) {}
            finishStatement(f, evT, opening, (total / 32_000).toInt())
        }.also { it.start() }
    }
    fun stopRecording() { recording = false }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Long, rate: Int) {
        fun le32(v: Long) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
        raf.seek(0)
        raf.write("RIFF".toByteArray()); raf.write(le32(36 + dataLen)); raf.write("WAVEfmt ".toByteArray()); raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(1))
        raf.write(le32(rate.toLong())); raf.write(le32(rate * 2L)); raf.write(le16(2)); raf.write(le16(16)); raf.write("data".toByteArray()); raf.write(le32(dataLen))
    }

    private fun finishStatement(f: File, evT: Long, opening: String, durS: Int) = h.post { safe("statement save") { finishStatementInner(f, evT, opening, durS) } }
    private fun finishStatementInner(f: File, evT: Long, opening: String, durS: Int) {
        if (!f.exists() || f.length() <= 44) {
            AppState.rec.value = RecState(false, 0, 0f); AppState.toast.value = "녹음을 저장하지 못했어요. 마이크를 확인해 주세요"
            L("REC nothing recorded file=${f.name} exists=${f.exists()}"); f.delete(); startFg(false); return
        }
        val sha = Hash.sha256(f.readBytes())
        curStatementId = store.insertStatement(StatementRow(0, evT, System.currentTimeMillis(), opening, "", f.absolutePath, sha, durS))
        AppState.rec.value = RecState(false, durS, 0f, transcribing = true)
        L("REC done file=${f.name} bytes=${f.length()} dur=${durS}s sha256=$sha")
        startFg(false)
        h.postDelayed({ if (AppState.rec.value.transcribing) { AppState.rec.value = AppState.rec.value.copy(transcribing = false); L("STT no final result within 15s (transcript empty, audio kept)") } }, 15_000)
        refresh()
    }

    /** 시험용: 에뮬레이터 마이크 대신 저장된 진술 음성 파일을 녹음 결과로 사용 */
    fun recordFromAsset(asset: String) = h.post {
        val f = File(filesDir, "statements/st_${System.currentTimeMillis()}.wav").also { it.parentFile?.mkdirs() }
        assets.open(asset).use { inp -> f.outputStream().use { inp.copyTo(it) } }
        val bytes = f.readBytes(); val pcm = bytes.copyOfRange(44, bytes.size)
        val evT = AppState.accident.value?.t ?: System.currentTimeMillis()
        L("REC (asset) $asset bytes=${bytes.size}")
        try {
            stt!!.request()
            h.postDelayed({
                var off = 0
                while (off < pcm.size) { val e = minOf(off + 3200, pcm.size); stt!!.sendAudio(pcm.copyOfRange(off, e)); off = e; Thread.sleep(100) }
                stt!!.completeAudioSend(); L("STT fed ${pcm.size}B")
            }, 400)
        } catch (e: Exception) { L("STT feed FAILED $e") }
        finishStatement(f, evT, statementOpening(), pcm.size / 32_000)
    }

    private fun onSttFinal(text: String) = h.post {
        L("STT final=${LogPrivacy.text(text)}")
        if (curStatementId > 0 && AppState.rec.value.transcribing) {
            store.setTranscript(curStatementId, text); AppState.rec.value = AppState.rec.value.copy(transcribing = false)
            L("STATEMENT transcript saved id=$curStatementId chars=${text.length}"); refresh()
        } else if (guide.active && (text.contains("다음") || text.contains("넘어"))) guideNext("voice")
    }
    fun listenForNext() { try { stt?.request(); L("STT listening for '다음 단계'") } catch (e: Exception) { L("STT request FAILED $e") } }
    fun feedAsset(asset: String) = h.post {
        try {
            val b = assets.open(asset).use { it.readBytes() }; val pcm = b.copyOfRange(44, b.size)
            stt!!.request()
            h.postDelayed({ var off = 0; while (off < pcm.size) { val e = minOf(off + 3200, pcm.size); stt!!.sendAudio(pcm.copyOfRange(off, e)); off = e; Thread.sleep(100) }; stt!!.completeAudioSend() }, 400)
        } catch (e: Exception) { L("STT feed FAILED $e") }
    }

    // ---------------- 리포트 ----------------
    fun issueReport(userT: Long, onDone: (String) -> Unit = {}) = h.post {
        try {
            sealer?.flush()
            val r = ReportService.issue(this, store, settings, userT, AppState.address.value)
            onDone(r)
        } catch (e: Exception) { L("ERROR report ${e.javaClass.simpleName}: ${e.message}"); AppState.toast.value = "리포트를 만들지 못했어요. 잠시 뒤 다시 눌러 주세요"; onDone("ERROR") }
        refresh()
    }
    /** 봉인 전 샘플까지 합쳐 보여 준다. 미리보기 때문에 묶음을 강제로 봉인하지 않는다(1분 묶음 유지). */
    fun pendingSamples(): List<Sample> = try { sealer?.pending() ?: emptyList() } catch (e: Exception) { emptyList() }
    fun previewWindow(anchor: Long): List<Sample> = try {
        (store.chunksBetween(anchor - 300_000, anchor + 300_000).flatMap { Sample.parseAll(it.data) } + pendingSamples()).filter { it.t in (anchor - 300_000)..(anchor + 300_000) }
    } catch (e: Exception) { emptyList() }
    fun flushNow() = h.post { sealer?.flush() }

    // ---------------- TTS / STT ----------------
    private var ttsReady = false
    fun say(text: String) { L("SAY ${LogPrivacy.text(text)} ready=$ttsReady"); try { tts?.speak(text, true) } catch (e: Exception) { L("TTS speak FAILED $e") } }
    private fun setupTts() {
        try {
            tts = ai.pleos.playground.tts.TextToSpeech(this, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
            tts!!.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                override fun onSuccess() { ttsReady = true; tts!!.setLocale(Locale.KOREA); L("TTS init OK (ON_DEVICE, ko_KR)") }
                override fun onFailure(p0: ai.pleos.playground.tts.constant.ErrorType) { L("TTS init FAILED $p0") } })
        } catch (e: Exception) { L("TTS setup FAILED $e") }
    }
    private fun setupStt() {
        try {
            stt = ai.pleos.playground.stt.SpeechToText(this, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
            stt!!.addListener(object : ai.pleos.playground.stt.listener.ResultListener {
                override fun onUpdated(p0: String, p1: Boolean) { if (p1 && p0.isNotBlank()) onSttFinal(p0) }
                override fun onUpdatedEpdData(p0: Long, p1: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(p0: ai.pleos.playground.stt.constant.ErrorType) { L("STT onError $p0") }
                override fun onReady() {}
                override fun onUpdatedRms(p0: Double) {} })
            stt!!.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() { stt!!.setLocale(Locale.KOREA); L("STT init OK (ON_DEVICE, ko_KR)") }
                override fun onFailure(p0: ai.pleos.playground.stt.constant.ErrorType) { L("STT init FAILED $p0") } })
        } catch (e: Exception) { L("STT setup FAILED $e") }
    }

    // ---------------- 차량 신호 ----------------
    private fun fail(name: String) = { e: Exception -> L("VEH $name FAILED ${e.javaClass.simpleName}: ${e.message}") }
    private fun setupVehicle() {
        vehicle = Vehicle(this)
        try { vehicle.initialize() } catch (e: Exception) { L("VEH initialize FAILED $e") }
        h.postDelayed({ registerVehicle() }, 3000)
    }
    /** 크루즈·경고등 1초 조회. 리스너가 첫 값만 주는 경우(에뮬레이터 확인)와 실시간 등록 권한이 없는 경고등을 함께 덮는다 */
    private var telltaleDenied = false
    private fun pollAux() {
        try {
            vehicle.getDrivingMode().getCruiseControlState({ c -> val v = Cruise.fromSdk(c?.name); if (v != Sig.cruise) L("SIG cruise=$v"); Sig.cruise = v }, { _ -> })
        } catch (_: Exception) {}
        if (!telltaleDenied) try {
            vehicle.getSafety().getTelltalesInfo({ p0 ->
                val on = p0?.telltales?.filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }?.map { it.type.name }
                if (on != null) { val f = Telltale.flags(on); if (f != Sig.telltales) L("SIG telltales=$f"); Sig.telltales = f }
            }, { e -> telltaleDenied = true; L("VEH telltale unavailable: ${e.message}") })
        } catch (e: Exception) { telltaleDenied = true; L("VEH telltale EXC ${e.javaClass.simpleName}: ${e.message}") }
        h.postDelayed({ pollAux() }, 1_000)
    }

    private fun registerVehicle() {
        fun cap(name: String, call: () -> Unit) = try { call() } catch (e: Exception) { L("VEH $name EXC ${e.javaClass.simpleName}: ${e.message}") }
        cap("brake") { vehicle.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(p0: Boolean?) { Sig.brake = p0 }
            override fun onFailed(p0: Exception) { fail("brake")(p0) } }) }
        cap("gear") { vehicle.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(p0: VehicleGear?) { Sig.gear = Gear.fromSdk(p0?.name); L("SIG gear=${Sig.gear}") }
            override fun onFailed(p0: Exception) { fail("gear")(p0) } }) }
        cap("turn") { vehicle.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) { Sig.turn = Turn.fromSdk(p0?.toString()); L("SIG turn=${Sig.turn}") }
            override fun onFailed(p0: Exception) { fail("turn")(p0) } }) }
        cap("steer") { vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(p0: Float?) { Sig.steer = p0?.let { -it } }      // 에뮬레이터에서 콘솔과 부호가 반대(검증 결과)
            override fun onFailed(p0: Exception) { fail("steer")(p0) } }) }
        cap("light") { vehicle.getLight().registerLowBeamLightState(object : LightStateListener {
            override fun onLightStateUpdated(p0: LightState?) { Sig.lowBeam = p0?.let { it.name == "ON" } }
            override fun onFailed(p0: Exception) { fail("lowBeam")(p0) } }) }
        cap("wiper") { vehicle.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), object : WiperStateListener {
            override fun onWiperStateUpdated(p0: WiperState?) { Sig.wiper = p0?.let { it.name != "OFF" } }
            override fun onFailed(p0: Exception) { fail("wiper")(p0) } }) }
        cap("door") { vehicle.getDoor().registerDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), object : DoorPositionListener {
            override fun onPositionUpdated(p0: Int?) { val open = p0?.let { it > 0 }; Sig.driverDoor = open; L("SIG driverDoor=$p0"); open?.let { onDoor(it) } }
            override fun onFailed(p0: Exception) { fail("door")(p0) } }) }
        cap("airbag") {
            vehicle.getSafety().checkAirbagDeployedCapabilities({ c, a -> L("CAP airbagDeployed=$c areas=$a") }, fail("airbag.cap"))
            vehicle.getSafety().registerSeatAirbagDeployed(SeatArea(SeatValue.ROW_1_LEFT), object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(p0: Boolean?) { Sig.airbag = p0 }
                override fun onFailed(p0: Exception) { fail("airbag")(p0) } })
        }
        // 급발진·페달 오조작 소명의 보조 지표: 크루즈 상태, FCA·ESC·ABS·오토홀드 경고등 (가속 페달 개도는 SDK 미제공)
        cap("cruise") { vehicle.getDrivingMode().registerCruiseControlState(object : CruiseControlStateListener {
            override fun onCruiseControlStateUpdated(p0: CruiseControl?) { val c = Cruise.fromSdk(p0?.name); if (c != Sig.cruise) L("SIG cruise=$c"); Sig.cruise = c }
            override fun onFailed(p0: Exception) { fail("cruise")(p0) } }) }
        // 경고등 실시간 등록(registerTelltales)은 PRIVATE_CAR_SAFETY가 필요하다 → 조회로 시도하고, 거절되면 그 뒤로는 묻지 않는다
        pollAux()
        L("VEH register done")
    }
    private fun setupCar() {
        try {
            car = Car.createCar(this)
            val cpm = car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val ok = cpm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(v: CarPropertyValue<*>) {
                    if (v.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                        val k = (v.value as Float) * 3.6f
                        if (Sig.speedKph == null || abs((Sig.speedKph ?: 0f) - k) >= 10f) L("SIG speed=${"%.1f".format(k)}kph")
                        Sig.speedKph = k
                    }
                }
                override fun onErrorEvent(id: Int, zone: Int) { L("AAOS error prop=$id") }
            }, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)
            L("AAOS speed registered ok=$ok")
        } catch (e: Exception) { L("AAOS setup FAILED ${e.javaClass.simpleName}: ${e.message}") }
    }
    private fun setupFused() {
        try {
            fused = FusedLocation(this).also { it.initialize() }
            h.postDelayed({
                try {
                    fused!!.registerFusedLocationCallback(object : FusedLocationListener {
                        override fun onChanged(p0: LocationData?) { if (p0 == null) return; Sig.lat = p0.latitude; Sig.lon = p0.longitude }
                        override fun onError(p0: Exception) { L("FUSED error ${p0.message}") } })
                    L("FUSED registered")
                } catch (e: Exception) { L("FUSED FAILED ${e.javaClass.simpleName}: ${e.message}") }
            }, 3000)
        } catch (e: Exception) { L("FUSED init FAILED $e") }
    }
    private fun setupNavi() {
        try {
            navi = NaviHelper(this)
            navi!!.initialize({ L("NAVI initialized"); try { navi?.getCurrentLocationInfo() } catch (_: Exception) {} }, { t -> L("NAVI init FAILED ${t.message}") })
            navi!!.addListener(object : NaviHelperEventListener {
                override fun onCurrentLocationInfo(info: CurrentLocationInfo) { AppState.address.value = info.address; L("NAVI address=${LogPrivacy.text(info.address)}") }
                override fun onBookmarkInfo(list: List<ai.pleos.playground.navi.data.BookmarkInfo>) {}
                override fun onRouteStarted(p0: ai.pleos.playground.navi.data.RouteStartInfo) {}
                override fun onRoutingCompleted() {}
                override fun onDestinationChanged() {}
                override fun onError(p0: NaviErrorCode) { L("NAVI error $p0") }
                // 과속 수정요소의 근거: 현재 도로 제한속도 (0 이하는 정보 없음)
                override fun onCurrentRoadSpeedLimit(p0: Int) { val v = p0.takeIf { it in 1..200 }; if (v != Sig.speedLimit) L("NAVI speedLimit=$v"); Sig.speedLimit = v }
            })
        } catch (e: Exception) { L("NAVI setup FAILED $e") }
    }
    fun refreshAddress() { try { navi?.getCurrentLocationInfo() } catch (e: Exception) { L("NAVI call FAILED $e") } }

    // ---------------- 시험 명령 ----------------
    fun handle(cmd: String, arg: String?) {
        L("CMD $cmd ${arg ?: ""}")
        try { handleInner(cmd, arg) } catch (e: Exception) { L("CMD $cmd FAILED ${e.javaClass.simpleName}: ${e.message}") }
    }
    private fun handleInner(cmd: String, arg: String?) {
        when (cmd) {
            "siren" -> siren(arg ?: "cmd")
            "yes" -> answer(true)
            "no" -> answer(false)
            "next" -> guideNext("cmd")
            "stt_next" -> feedAsset("next.wav")
            "rec_asset" -> recordFromAsset(arg ?: "statement.wav")
            "rec_start" -> startRecording((arg ?: "8").toInt())
            "rec_stop" -> stopRecording()
            "report" -> issueReport(when { arg == null -> AppState.accident.value?.t ?: System.currentTimeMillis(); arg.matches(Regex("\\d{13}")) -> arg.toLong(); else -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }.parse(arg)!!.time }) { L("REPORT result $it") }
            "tamper" -> h.post { store.tamperNear((AppState.accident.value?.t ?: System.currentTimeMillis()) - 3_000) }
            "seed" -> h.post { store.seedOld(System.currentTimeMillis()); refresh() }
            "sweep" -> h.post { store.sweep(System.currentTimeMillis()); refresh() }
            "trips" -> h.post { store.trips().forEach { L("TRIP id=${it.id} started=${it.started} ended=${it.ended} keepDays=${(it.keepUntil - System.currentTimeMillis()) / 86_400_000.0} pin=${it.pin} chunks=${it.chunks}") }; store.deletionLog().forEach { L("DELETION_LOG $it") } }
            "trip_end" -> h.post { sealer?.flush(); store.endTrip(tripId, System.currentTimeMillis()); val now = System.currentTimeMillis(); tripId = store.newTrip(now); sealer = ChunkSealer(tripId, now, store::sign) { c -> store.insertChunk(c) }; store.sweep(now); refresh(); L("TRIP rolled -> $tripId") }
            "export" -> h.post {
                val out = getExternalFilesDir("export")!!
                listOf("reports", "statements").forEach { d -> File(filesDir, d).listFiles()?.forEach { f -> f.copyTo(File(out, f.name), overwrite = true) } }
                // 시험용 반출: 공용 다운로드(MediaStore)에도 복사해 adb로 꺼낼 수 있게 한다
                listOf("reports", "statements").forEach { d -> File(filesDir, d).listFiles()?.forEach { f ->
                    try {
                        val cv = android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name); put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/WiTHU") }
                        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        if (uri != null) { contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }; L("EXPORT media $uri ${f.name}") }
                    } catch (e: Exception) { L("EXPORT media FAILED ${f.name} $e") }
                } }
                L("EXPORT copied to ${out.absolutePath}: ${out.list()?.joinToString()}")
            }
            "stats" -> h.post { L("STORAGE ${store.storageStats()}") }
            "verify" -> h.post { store.verifyDb() }
            "addr" -> refreshAddress()
            "park_within" -> { detector = AccidentDetector(DetectorConfig(parkWithinMs = (arg ?: "180").toLong() * 1000)); L("PARAM parkWithin=${arg}s") }
            "route" -> ui.post { AppState.go(Route.valueOf(arg ?: "HOME")) }
            "state" -> L("STATE route=${AppState.route.value} pending=${AppState.pending.value} accident=${AppState.accident.value} guide=${AppState.guide.value} bubble=${bubble?.shown} visible=${MainActivity.visible}")
            "say" -> say(arg ?: "테스트 음성입니다.")
            "verbose" -> { LogPrivacy.redact = arg != "on"; L("LOG privacy redact=${LogPrivacy.redact}") }
        }
    }
}
