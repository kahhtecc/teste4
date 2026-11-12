package br.com.supervilab.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.sp
import br.com.supervilab.core.Equipamento
import br.com.supervilab.core.EstadoBomba
import br.com.supervilab.core.EstadoUI
import br.com.supervilab.core.EstadoValvula
import br.com.supervilab.core.PlantaStore
import kotlin.math.hypot

/** Diagrama simplificado da planta: rede → V3 → Tanque 1 → Bomba → V1 → Tanque 2 → V2 → dreno. */
@Composable
fun DiagramaPlanta(st: EstadoUI, modifier: Modifier = Modifier, aoTocar: (Equipamento) -> Unit) {
    val medidor = rememberTextMeasurer()
    val fase by rememberInfiniteTransition(label = "fluxo").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "fase")
    val rotor by rememberInfiniteTransition(label = "rotor").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "rot")
    val pulso by rememberInfiniteTransition(label = "pulso").animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p")
    val n1 by animateFloatAsState(((st.tanques[1]?.pct ?: 0.0) / 100.0).toFloat(), tween(700), label = "n1")
    val n2 by animateFloatAsState(((st.tanques[2]?.pct ?: 0.0) / 100.0).toFloat(), tween(700), label = "n2")
    val viva = st.plantaViva

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .semantics { contentDescription = "Diagrama da planta" }
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val l = Esquema(size.width.toFloat(), size.height.toFloat())
                    l.areas.firstOrNull { it.first.contains(p) }?.let { aoTocar(it.second) }
                }
            },
    ) {
        val l = Esquema(size.width, size.height)
        val tubo = Cores.Superficie3
        val esp = size.width * 0.018f
        // tubulações
        for (c in listOf(l.caminhoRede, l.caminhoBomba, l.caminhoDreno)) desenharPolilinha(c, tubo, esp)
        // fluxos
        if (viva) {
            if (st.valvulas[3] == EstadoValvula.ABERTA) desenharFluxo(l.caminhoRede, fase, Cores.AguaClara, esp)
            if (st.bomba == EstadoBomba.LIGADA && st.valvulas[1] == EstadoValvula.ABERTA) desenharFluxo(l.caminhoBomba, fase, Cores.AguaClara, esp)
            if (st.valvulas[2] == EstadoValvula.ABERTA) desenharFluxo(l.caminhoDreno, fase, Cores.AguaClara, esp)
        }
        // tanques
        desenharTanque(medidor, l.t1, "Tanque 1", if (viva) st.tanques[1]?.pct else null, n1, viva)
        desenharTanque(medidor, l.t2, "Tanque 2", if (viva) st.tanques[2]?.pct else null, n2, viva)
        // válvulas
        desenharValvula(medidor, l.v3, "V3", if (viva) st.valvulas[3] else null, pulso, false)
        desenharValvula(medidor, l.v1, "V1", if (viva) st.valvulas[1] else null, pulso, false)
        desenharValvula(medidor, l.v2, "V2", if (viva) st.valvulas[2] else null, pulso, false)
        // bomba
        desenharBomba(medidor, l.bomba, if (viva) st.bomba else null, rotor)
        // rótulos das pontas
        rotulo(medidor, "REDE", Offset(l.caminhoRede.first().x, l.caminhoRede.first().y - size.width * 0.045f), Cores.TextoMuitoFraco, 10.sp)
        rotulo(medidor, "DRENO", Offset(l.caminhoDreno.last().x - size.width * 0.115f, l.caminhoDreno.last().y - size.width * 0.03f), Cores.TextoMuitoFraco, 10.sp)
    }
}

private class Esquema(w: Float, h: Float) {
    val t1 = Rect(0.08f * w, 0.20f * h, 0.30f * w, 0.78f * h)
    val t2 = Rect(0.62f * w, 0.20f * h, 0.84f * w, 0.78f * h)
    val bomba = Offset(0.40f * w, 0.90f * h)
    val v1 = Offset(0.54f * w, 0.90f * h)
    val v3 = Offset(0.11f * w, 0.11f * h)
    val v2 = Offset(0.92f * w, 0.55f * h)
    val raio = 0.045f * w
    val caminhoRede = listOf(Offset(0.03f * w, 0.11f * h), Offset(0.19f * w, 0.11f * h), Offset(0.19f * w, t1.top))
    val caminhoBomba = listOf(Offset(0.19f * w, t1.bottom), Offset(0.19f * w, 0.90f * h), Offset(0.73f * w, 0.90f * h), Offset(0.73f * w, t2.bottom))
    val caminhoDreno = listOf(Offset(t2.right, 0.55f * h), Offset(0.985f * w, 0.55f * h), Offset(0.985f * w, 0.97f * h))
    val areas: List<Pair<Rect, Equipamento>> = listOf(
        t1 to Equipamento.T1, t2 to Equipamento.T2,
        Rect(bomba.x - raio * 1.6f, bomba.y - raio * 1.6f, bomba.x + raio * 1.6f, bomba.y + raio * 1.6f) to Equipamento.BOMBA,
        quad(v1, raio * 1.8f) to Equipamento.V1, quad(v2, raio * 1.8f) to Equipamento.V2, quad(v3, raio * 1.8f) to Equipamento.V3,
    )
    private fun quad(c: Offset, r: Float) = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
}

private fun DrawScope.desenharPolilinha(p: List<Offset>, cor: Color, esp: Float) {
    val path = Path().apply { moveTo(p[0].x, p[0].y); for (i in 1 until p.size) lineTo(p[i].x, p[i].y) }
    drawPath(path, cor, style = Stroke(esp, cap = StrokeCap.Round))
}

private fun DrawScope.desenharFluxo(p: List<Offset>, fase: Float, cor: Color, esp: Float) {
    val comp = (1 until p.size).map { hypot(p[it].x - p[it - 1].x, p[it].y - p[it - 1].y) }
    val total = comp.sum()
    val passo = esp * 4.5f
    var d = (fase * passo) % passo
    while (d < total) {
        var resto = d; var pos = p[0]
        for (i in comp.indices) {
            if (resto <= comp[i]) { val t = resto / comp[i]; pos = Offset(p[i].x + (p[i + 1].x - p[i].x) * t, p[i].y + (p[i + 1].y - p[i].y) * t); break }
            resto -= comp[i]
        }
        drawCircle(cor, esp * 0.38f, pos)
        d += passo
    }
}

private fun DrawScope.desenharTanque(m: TextMeasurer, r: Rect, nome: String, pct: Double?, frac: Float, viva: Boolean) {
    val canto = CornerRadius(r.width * 0.12f)
    drawRoundRect(Cores.Superficie2, r.topLeft, r.size, canto)
    if (pct != null) {
        val alt = r.height * frac.coerceIn(0f, 1f)
        val topo = r.bottom - alt
        val cor = corNivel(pct)
        drawRoundRect(cor.copy(alpha = 0.22f), Offset(r.left, topo), Size(r.width, alt), CornerRadius(r.width * 0.06f))
        drawRect(cor, Offset(r.left, topo), Size(r.width, 3f))
        drawRoundRect(cor.copy(alpha = 0.9f), r.topLeft, r.size, canto, style = Stroke(2.5f))
        rotulo(m, "${PlantaStore.fmt(pct)}%", Offset(r.center.x, r.center.y), Cores.Texto, 19.sp, centrado = true, negrito = true)
    } else {
        drawRoundRect(Cores.Desconhecido, r.topLeft, r.size, canto, style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        rotulo(m, "?", Offset(r.center.x, r.center.y), Cores.Desconhecido, 26.sp, centrado = true, negrito = true)
    }
    rotulo(m, nome, Offset(r.center.x, r.top + r.height * 0.14f), if (viva && pct != null) Cores.Tanque else Cores.Desconhecido, 11.sp, centrado = true)
}

private fun DrawScope.desenharValvula(m: TextMeasurer, c: Offset, nome: String, e: EstadoValvula?, pulso: Float, vertical: Boolean) {
    val r = size.width * 0.036f
    val cor = corValvula(e).let { if (e?.emMovimento == true) it.copy(alpha = pulso) else it }
    val p = Path().apply {
        moveTo(c.x - r, c.y - r * 0.8f); lineTo(c.x, c.y); lineTo(c.x - r, c.y + r * 0.8f); close()
        moveTo(c.x + r, c.y - r * 0.8f); lineTo(c.x, c.y); lineTo(c.x + r, c.y + r * 0.8f); close()
    }
    drawCircle(Cores.Fundo, r * 1.35f, c)
    if (e == EstadoValvula.ABERTA || e?.emMovimento == true) drawPath(p, cor) else drawPath(p, cor, style = Stroke(2.5f))
    // haste: aberta = alinhada ao tubo, fechada = perpendicular
    val ang = if (e == EstadoValvula.ABERTA) 0f else 90f
    rotate(ang, c) { drawLine(cor, Offset(c.x - r * 0.9f, c.y - r * 1.25f), Offset(c.x + r * 0.9f, c.y - r * 1.25f), 3f, StrokeCap.Round) }
    if (e == null) rotulo(m, "?", Offset(c.x, c.y), Cores.Desconhecido, 12.sp, centrado = true, negrito = true)
    rotulo(m, nome, Offset(c.x, c.y + r * 1.9f), cor, 10.sp, centrado = true)
}

private fun DrawScope.desenharBomba(m: TextMeasurer, c: Offset, e: EstadoBomba?, rotor: Float) {
    val r = size.width * 0.045f
    val cor = corBomba(e)
    drawCircle(Cores.Fundo, r * 1.25f, c)
    drawCircle(if (e == EstadoBomba.LIGADA) cor.copy(alpha = 0.18f) else Cores.Superficie2, r, c)
    drawCircle(cor, r, c, style = Stroke(2.5f))
    if (e == null) rotulo(m, "?", Offset(c.x, c.y), Cores.Desconhecido, 12.sp, centrado = true, negrito = true)
    else rotate(if (e == EstadoBomba.LIGADA) rotor else 0f, c) {
        for (i in 0 until 3) rotate(i * 120f, c) { drawLine(cor, c, Offset(c.x, c.y - r * 0.7f), 3f, StrokeCap.Round) }
    }
    rotulo(m, "BOMBA", Offset(c.x, c.y - r * 2.1f), cor, 10.sp, centrado = true)
}

private fun DrawScope.rotulo(m: TextMeasurer, texto: String, pos: Offset, cor: Color, tam: androidx.compose.ui.unit.TextUnit, centrado: Boolean = false, negrito: Boolean = false) {
    val estilo = TextStyle(fontFamily = if (negrito) Sora else Manrope, fontSize = tam, color = cor,
        fontWeight = if (negrito) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = if (negrito) 0.sp else 0.8.sp)
    val r = m.measure(texto, estilo)
    val tl = if (centrado) Offset(pos.x - r.size.width / 2f, pos.y - r.size.height / 2f) else pos
    drawText(r, topLeft = tl)
}
