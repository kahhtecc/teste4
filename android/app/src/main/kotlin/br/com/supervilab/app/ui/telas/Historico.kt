package br.com.supervilab.app.ui.telas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.supervilab.app.ui.*
import br.com.supervilab.core.*

@Composable
fun TelaHistorico(st: EstadoUI) {
    var janela by rememberSaveable { mutableStateOf(10) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Cabecalho("Histórico", st) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(2, 5, 10, 15)) {
                    val sel = m == janela
                    Box(Modifier.clip(Formas.Chip).background(if (sel) Cores.Tanque else Cores.Superficie2).clickable { janela = m }
                        .semantics { contentDescription = "Janela de $m minutos" }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("$m min", style = Tipo.Legenda.copy(color = if (sel) Cores.Fundo else Cores.TextoFraco))
                    }
                }
            }
        }
        item {
            Cartao {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Rotulo("Nível dos tanques", Modifier.weight(1f))
                    Legenda("Tanque 1", Cores.Tanque); Spacer(Modifier.width(10.dp)); Legenda("Tanque 2", Cores.Bomba)
                }
                Espaco(12)
                GraficoNiveis(st, listOf(1, 2), janela, Modifier.fillMaxWidth().height(240.dp))
                Espaco(8)
                Text("Linhas tracejadas: limites de alarme (10 % e 90 %). Lacunas = sem dados (conexão perdida).", style = Tipo.Legenda)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (n in 1..2) {
                    val h = st.historico[n].orEmpty()
                    Cartao(Modifier.weight(1f)) {
                        Rotulo("Tanque $n"); Espaco(6)
                        if (h.isEmpty()) Text("sem dados", style = Tipo.CorpoFraco) else {
                            val ult = h.last().pct; val min = h.minOf { it.pct }; val max = h.maxOf { it.pct }
                            Text("${PlantaStore.fmt(ult)}%", style = Tipo.Numero.copy(color = corNivel(ult)))
                            Text("mín ${PlantaStore.fmt(min)} % · máx ${PlantaStore.fmt(max)} %", style = Tipo.Legenda)
                            Text("${h.size} amostras", style = Tipo.Legenda)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legenda(txt: String, cor: Color) = Row(verticalAlignment = Alignment.CenterVertically) { PontoEstado(cor, 8); Spacer(Modifier.width(4.dp)); Text(txt, style = Tipo.Legenda) }

/** Gráfico de linhas em Canvas: eixo de tempo (janela em minutos até agora), 0–100 %. */
@Composable
fun GraficoNiveis(st: EstadoUI, tanques: List<Int>, janelaMin: Int, modifier: Modifier) {
    val medidor = rememberTextMeasurer()
    val cores = mapOf(1 to Cores.Tanque, 2 to Cores.Bomba)
    val agora = System.currentTimeMillis()
    val estiloEixo = TextStyle(fontFamily = Manrope, fontSize = 10.sp, color = Cores.TextoMuitoFraco)
    Canvas(modifier.semantics { contentDescription = "Gráfico de nível" }) {
        val margemEsq = 34f; val margemInf = 20f
        val w = size.width - margemEsq; val h = size.height - margemInf
        val ini = agora - janelaMin * 60_000L
        fun x(ts: Long) = margemEsq + w * ((ts - ini).toFloat() / (agora - ini).toFloat()).coerceIn(0f, 1f)
        fun y(pct: Double) = h * (1f - (pct / 100.0).toFloat())
        // grade
        for (p in listOf(0, 25, 50, 75, 100)) {
            drawLine(Cores.Borda, Offset(margemEsq, y(p.toDouble())), Offset(size.width, y(p.toDouble())), 1f)
            val r = medidor.measure("$p", estiloEixo); drawText(r, topLeft = Offset(0f, y(p.toDouble()) - r.size.height / 2f))
        }
        for (lim in listOf(Protocolo.NIVEL_BAIXO_PCT, Protocolo.NIVEL_ALTO_PCT))
            drawLine(Cores.Alerta.copy(alpha = 0.6f), Offset(margemEsq, y(lim)), Offset(size.width, y(lim)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        // eixo do tempo
        val passos = 4
        for (i in 0..passos) {
            val ts = ini + (agora - ini) * i / passos
            val txt = if (i == passos) "agora" else "-${janelaMin * (passos - i) / passos} min"
            val r = medidor.measure(txt, estiloEixo)
            drawText(r, topLeft = Offset((x(ts) - r.size.width / 2f).coerceIn(margemEsq, size.width - r.size.width), h + 4f))
        }
        // séries
        for (n in tanques) {
            val pontos = st.historico[n].orEmpty().filter { it.ts >= ini }
            if (pontos.isEmpty()) continue
            val cor = cores[n] ?: Cores.Texto
            val path = Path(); var anterior: Ponto? = null
            for (p in pontos) {
                val px = x(p.ts); val py = y(p.pct)
                if (anterior == null || p.ts - anterior.ts > 5_000) path.moveTo(px, py) else path.lineTo(px, py)
                anterior = p
            }
            drawPath(path, cor, style = Stroke(2.5f, cap = StrokeCap.Round))
            val ult = pontos.last()
            if (st.plantaViva && agora - ult.ts < 5_000) drawCircle(cor, 5f, Offset(x(ult.ts), y(ult.pct)))
        }
        if (tanques.all { st.historico[it].orEmpty().none { p -> p.ts >= ini } }) {
            val r = medidor.measure("Sem dados nesta janela", TextStyle(fontFamily = Manrope, fontSize = 13.sp, color = Cores.TextoFraco))
            drawText(r, topLeft = Offset(margemEsq + (w - r.size.width) / 2, (h - r.size.height) / 2))
        }
    }
}
