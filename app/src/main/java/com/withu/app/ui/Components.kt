package com.withu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.withu.app.R
import kotlin.math.abs
import kotlin.math.sin

/** 기획서 시안(common.js)과 같은 색 */
object C {
    val brand = Color(0xFF1463F3); val brand2 = Color(0xFF4C8DFF); val navy = Color(0xFF0B1F3A); val teal = Color(0xFF12B5A6)
    val warn = Color(0xFFFF5A36); val amber = Color(0xFFF5A524); val bg = Color(0xFFF4F6FA); val card = Color.White
    val line = Color(0xFFE3E8F0); val sub = Color(0xFF5B6B80); val mute = Color(0xFF8A97A8); val soft = Color(0xFFEAF1FF)
    val okBg = Color(0xFFE6F7F4); val okFg = Color(0xFF0B8577); val recBg = Color(0xFFFFEDE8); val sec = Color(0xFFE9EEF6)
    val navySub = Color(0xFFC9D6EA); val navyAccent = Color(0xFF9EC1FF)
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal), Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold), Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

@Composable fun T(text: String, size: TextUnit = 26.sp, color: Color = C.navy, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier,
                  lineHeight: TextUnit = TextUnit.Unspecified, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, color = color, maxLines = maxLines, style = TextStyle(fontFamily = Pretendard, fontSize = size, fontWeight = weight,
        lineHeight = if (lineHeight == TextUnit.Unspecified) (size.value * 1.35f).sp else lineHeight, letterSpacing = (-0.01 * size.value).sp), textAlign = align)

@Composable fun H1(text: String, size: TextUnit = 58.sp, color: Color = C.navy, modifier: Modifier = Modifier) = T(text, size, color, FontWeight.ExtraBold, modifier, (size.value * 1.2f).sp)
@Composable fun H2(text: String, color: Color = C.navy, size: TextUnit = 34.sp) = T(text, size, color, FontWeight.ExtraBold)
@Composable fun K(text: String, color: Color = C.sub, size: TextUnit = 24.sp, modifier: Modifier = Modifier) = T(text, size, color, FontWeight.SemiBold, modifier)
@Composable fun D(text: String, color: Color = C.sub, size: TextUnit = 26.sp, modifier: Modifier = Modifier) = T(text, size, color, modifier = modifier, lineHeight = (size.value * 1.5f).sp)

@Composable fun WCard(modifier: Modifier = Modifier, bg: Color = C.card, pad: Dp = 34.dp, fill: Boolean = true, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = C.navy.copy(alpha = .08f), spotColor = C.navy.copy(alpha = .10f))
        .clip(RoundedCornerShape(28.dp)).background(bg).padding(horizontal = pad + 2.dp, vertical = pad), content = content)

enum class ChipKind { OK, REC, INFO, PLAIN }
@Composable fun Chip(text: String, kind: ChipKind, dot: Boolean = false, onClick: (() -> Unit)? = null) {
    val (bg, fg) = when (kind) { ChipKind.OK -> C.okBg to C.okFg; ChipKind.REC -> C.recBg to C.warn; ChipKind.INFO -> C.soft to C.brand; ChipKind.PLAIN -> C.sec to C.navy }
    Row(Modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(bg).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (dot) Box(Modifier.size(14.dp).clip(CircleShape).background(fg))
        T(text, 24.sp, fg, FontWeight.Bold, maxLines = 1)
    }
}

@Composable fun Tag(text: String, bg: Color = C.soft, fg: Color = C.brand, size: TextUnit = 22.sp) =
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 16.dp, vertical = 6.dp)) { T(text, size, fg, FontWeight.ExtraBold, maxLines = 1) }

enum class BtnKind { PRI, SEC, WARN, NAVY, GHOST }
@Composable fun Btn(text: String, kind: BtnKind, modifier: Modifier = Modifier, height: Dp = 112.dp, size: TextUnit = 34.sp, enabled: Boolean = true, onClick: () -> Unit) {
    val (bg0, fg0) = when (kind) { BtnKind.PRI -> C.brand to Color.White; BtnKind.SEC -> C.sec to C.navy; BtnKind.WARN -> C.warn to Color.White; BtnKind.NAVY -> C.navy to Color.White; BtnKind.GHOST -> Color(0xFF243650) to Color.White }
    val bg = if (enabled) bg0 else C.sec; val fg = if (enabled) fg0 else C.mute
    Box(modifier.height(height).clip(RoundedCornerShape(24.dp)).background(bg)
        .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = text }.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        T(text, size, fg, FontWeight.ExtraBold, align = TextAlign.Center, maxLines = 2)
    }
}

enum class Mood { HAPPY, WORRY, GUIDE, CALM }
@Composable fun Wedi(mood: Mood, size: Dp, modifier: Modifier = Modifier) {
    val res = when (mood) { Mood.HAPPY -> R.drawable.wedi_happy; Mood.WORRY -> R.drawable.wedi_worry; Mood.GUIDE -> R.drawable.wedi_guide; Mood.CALM -> R.drawable.wedi_calm }
    Image(painterResource(res), contentDescription = "위디", modifier = modifier.width(size).height(size * 1.15f))
}

/** 단순 기하 아이콘 (시안 screens.js의 SVG와 같은 모양) */
enum class Ico { BRAKE, TURN, LIGHT, BELT, DOOR, PIN, WIPER, STEER, SHIELD, PHONE, GEAR, SPEED, SIREN, DOC, ARCHIVE, MIC, CLOCK, CAR }
@Composable fun IconBox(kind: Ico, bg: Color, fg: Color, size: Dp = 64.dp, round: Boolean = false) {
    Box(Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(18.dp)).background(bg), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.53f)) {
            val u = this.size.width / 24f
            fun o(x: Float, y: Float) = Offset(x * u, y * u)
            val st = Stroke(width = 2.6f * u, cap = StrokeCap.Round)
            when (kind) {
                Ico.BRAKE -> { drawCircle(fg, 9 * u, o(12f, 12f), style = st); drawLine(fg, o(12f, 7f), o(12f, 13f), 2.8f * u, StrokeCap.Round); drawCircle(fg, 1.6f * u, o(12f, 16.5f)) }
                Ico.TURN -> { drawLine(fg, o(5f, 12f), o(17f, 12f), 2.8f * u, StrokeCap.Round); drawLine(fg, o(12f, 6f), o(18f, 12f), 2.8f * u, StrokeCap.Round); drawLine(fg, o(12f, 18f), o(18f, 12f), 2.8f * u, StrokeCap.Round) }
                Ico.LIGHT -> { drawCircle(fg, 5.5f * u, o(9f, 12f)); for (y in listOf(8f, 12f, 16f)) drawLine(fg, o(16f, y), o(21f, y), 2.4f * u, StrokeCap.Round) }
                Ico.BELT -> { drawCircle(fg, 3 * u, o(12f, 5f)); drawArc(fg, 180f, 180f, false, o(7f, 8f), androidx.compose.ui.geometry.Size(10 * u, 10 * u), style = st); drawLine(fg, o(8f, 10f), o(16f, 19f), 2.4f * u, StrokeCap.Round) }
                Ico.DOOR -> { val p = Path().apply { moveTo(5 * u, 20 * u); lineTo(5 * u, 8 * u); lineTo(12 * u, 4 * u); lineTo(19 * u, 4 * u); lineTo(19 * u, 20 * u); close() }; drawPath(p, fg, style = st); drawLine(fg, o(14f, 12f), o(16f, 12f), 2.4f * u, StrokeCap.Round) }
                Ico.PIN -> { drawCircle(fg, 7 * u, o(12f, 10f)); val p = Path().apply { moveTo(6 * u, 12 * u); lineTo(12 * u, 22 * u); lineTo(18 * u, 12 * u); close() }; drawPath(p, fg); drawCircle(bg, 2.6f * u, o(12f, 10f)) }
                Ico.WIPER -> { drawArc(fg, 180f, 180f, false, o(3f, 9f), androidx.compose.ui.geometry.Size(18 * u, 18 * u), style = st); drawLine(fg, o(12f, 18f), o(7f, 8f), 2.6f * u, StrokeCap.Round) }
                Ico.STEER -> { drawCircle(fg, 9 * u, o(12f, 12f), style = st); drawCircle(fg, 2.4f * u, o(12f, 12f)); drawLine(fg, o(3.5f, 11f), o(9.5f, 11f), 2.4f * u); drawLine(fg, o(14.5f, 11f), o(20.5f, 11f), 2.4f * u); drawLine(fg, o(12f, 14f), o(12f, 21f), 2.4f * u) }
                Ico.SHIELD -> { val p = Path().apply { moveTo(12 * u, 2 * u); lineTo(20 * u, 5 * u); lineTo(20 * u, 11 * u); cubicTo(20 * u, 16 * u, 16.5f * u, 20 * u, 12 * u, 22 * u); cubicTo(7.5f * u, 20 * u, 4 * u, 16 * u, 4 * u, 11 * u); lineTo(4 * u, 5 * u); close() }; drawPath(p, fg)
                    drawLine(bg, o(8f, 12f), o(11f, 15f), 2.4f * u, StrokeCap.Round); drawLine(bg, o(11f, 15f), o(16f, 9f), 2.4f * u, StrokeCap.Round) }
                Ico.PHONE -> { val p = Path().apply { moveTo(6 * u, 3 * u); lineTo(10 * u, 3 * u); lineTo(12 * u, 8 * u); lineTo(9 * u, 10 * u); cubicTo(10 * u, 12.5f * u, 11.5f * u, 14 * u, 14 * u, 15 * u); lineTo(16 * u, 12 * u); lineTo(21 * u, 14 * u); lineTo(21 * u, 18 * u); cubicTo(21 * u, 19.5f * u, 20 * u, 20.5f * u, 19 * u, 20.5f * u); cubicTo(11 * u, 20 * u, 4 * u, 13 * u, 3.5f * u, 5 * u); cubicTo(3.5f * u, 4 * u, 4.5f * u, 3 * u, 6 * u, 3 * u); close() }; drawPath(p, fg) }
                Ico.GEAR -> { drawCircle(fg, 9 * u, o(12f, 12f), style = st); drawLine(fg, o(9f, 7f), o(9f, 17f), 2.6f * u, StrokeCap.Round); drawArc(fg, -90f, 180f, false, o(6f, 7f), androidx.compose.ui.geometry.Size(8 * u, 6 * u), style = st) }
                Ico.SPEED -> { drawArc(fg, 160f, 220f, false, o(3f, 4f), androidx.compose.ui.geometry.Size(18 * u, 18 * u), style = st); drawLine(fg, o(12f, 13f), o(17f, 8f), 2.6f * u, StrokeCap.Round) }
                Ico.SIREN -> { val p = Path().apply { moveTo(6 * u, 18 * u); lineTo(6 * u, 11 * u); cubicTo(6 * u, 7 * u, 9 * u, 5 * u, 12 * u, 5 * u); cubicTo(15 * u, 5 * u, 18 * u, 7 * u, 18 * u, 11 * u); lineTo(18 * u, 18 * u); close() }; drawPath(p, fg)
                    drawLine(fg, o(4f, 20f), o(20f, 20f), 2.6f * u, StrokeCap.Round); drawLine(fg, o(12f, 1f), o(12f, 2.5f), 2.4f * u, StrokeCap.Round); drawLine(fg, o(3f, 5f), o(4.5f, 6.5f), 2.4f * u, StrokeCap.Round); drawLine(fg, o(21f, 5f), o(19.5f, 6.5f), 2.4f * u, StrokeCap.Round) }
                Ico.DOC -> { drawRoundRect(fg, o(5f, 3f), androidx.compose.ui.geometry.Size(14 * u, 18 * u), androidx.compose.ui.geometry.CornerRadius(2 * u), style = st); for (y in listOf(8f, 12f, 16f)) drawLine(fg, o(8f, y), o(16f, y), 2.2f * u, StrokeCap.Round) }
                Ico.ARCHIVE -> { drawRoundRect(fg, o(3f, 4f), androidx.compose.ui.geometry.Size(18 * u, 5 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u)); drawRoundRect(fg, o(4.5f, 9f), androidx.compose.ui.geometry.Size(15 * u, 11 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = st); drawLine(fg, o(10f, 13f), o(14f, 13f), 2.4f * u, StrokeCap.Round) }
                Ico.MIC -> { drawRoundRect(fg, o(9f, 2f), androidx.compose.ui.geometry.Size(6 * u, 12 * u), androidx.compose.ui.geometry.CornerRadius(3 * u)); drawArc(fg, 0f, 180f, false, o(5f, 6f), androidx.compose.ui.geometry.Size(14 * u, 12 * u), style = st); drawLine(fg, o(12f, 18f), o(12f, 22f), 2.4f * u, StrokeCap.Round) }
                Ico.CLOCK -> { drawCircle(fg, 9 * u, o(12f, 12f), style = st); drawLine(fg, o(12f, 7f), o(12f, 12f), 2.4f * u, StrokeCap.Round); drawLine(fg, o(12f, 12f), o(16f, 14f), 2.4f * u, StrokeCap.Round) }
                Ico.CAR -> { drawRoundRect(fg, o(3f, 10f), androidx.compose.ui.geometry.Size(18 * u, 7 * u), androidx.compose.ui.geometry.CornerRadius(2 * u)); val p = Path().apply { moveTo(6 * u, 10 * u); lineTo(8 * u, 5 * u); lineTo(16 * u, 5 * u); lineTo(18 * u, 10 * u); close() }; drawPath(p, fg); drawCircle(bg, 1.6f * u, o(7f, 13.5f)); drawCircle(bg, 1.6f * u, o(17f, 13.5f)) }
            }
        }
    }
}

/** 음성 파형 (level 0~1) */
@Composable fun Wave(n: Int = 18, seed: Int = 3, color: Color = C.brand, height: Dp = 40.dp, level: Float = 1f, barW: Dp = 7.dp) {
    Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(n) { i -> Box(Modifier.width(barW).height(height * (0.25f + 0.75f * abs(sin(i * seed.toDouble())).toFloat() * level.coerceIn(0.15f, 1f))).clip(RoundedCornerShape(4.dp)).background(color)) }
    }
}

@Composable fun RowScope.Spacer1() = Box(Modifier.weight(1f))
@Composable fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
@Composable fun KV(k: String, v: String, vColor: Color = C.navy) = Column {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) { K(k); Spacer1(); T(v, 25.sp, vColor, FontWeight.Bold, align = TextAlign.End) }
    Divider()
}
@Composable fun Bordered(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(modifier.border(4.dp, C.navy, RoundedCornerShape(18.dp)).padding(12.dp)) { content() }
