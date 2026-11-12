package com.withu.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.withu.app.ui.WithUApp

class MainActivity : ComponentActivity() {
    companion object { @Volatile var visible = false }
    lateinit var settings: Settings
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> L("PERM result $r") }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        settings = Settings(this)
        if (!settings.onboarded) AppState.route.value = Route.ONBOARD
        startForegroundService(Intent(this, RecorderService::class.java))
        setContent {
            val route by AppState.route.collectAsState()
            BackHandler(enabled = route != Route.HOME && route != Route.ONBOARD) { if (route == Route.CONFIRM) AppState.home() else if (!AppState.pop()) AppState.home() }
            WithUApp(this)
        }
        L("ACT created route=${AppState.route.value}")
    }

    fun requestRuntimePermissions() = perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, "android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION", Manifest.permission.POST_NOTIFICATIONS))

    /** 최소화: Pleos 앱 영역에서는 moveTaskToBack·홈 인텐트가 듣지 않아(에뮬레이터 확인) 화면만 닫고 버블로 다시 연다. 기록은 서비스가 계속 한다. */
    fun minimize() { L("ACT minimize -> finish (service keeps recording)"); finish() }

    override fun onStart() { super.onStart(); visible = true; RecorderService.inst?.bubble?.hide("activity visible"); RecorderService.inst?.refresh() }
    override fun onStop() { super.onStop(); visible = false; if (!isChangingConfigurations) RecorderService.inst?.bubble?.show("activity hidden") }
}

class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = RecorderService.inst ?: run { L("CMD no service"); return }
        s.handle(i.getStringExtra("cmd") ?: "", i.getStringExtra("arg"))
    }
}

/** 시동(부팅) 완료 → 앱을 열지 않아도 기록 시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        L("BOOT ${i.action} -> start recorder")
        try { c.startForegroundService(Intent(c, RecorderService::class.java).putExtra("from", "boot")) } catch (e: Exception) { L("BOOT start FAILED $e") }
    }
}
