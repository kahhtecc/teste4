package br.com.supervilab.app.ui.telas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.supervilab.app.ui.*
import br.com.supervilab.core.*

@Composable
fun TelaPainel(st: EstadoUI, store: PlantaStore, aoAbrir: (Equipamento) -> Unit, aoAbrirAlarmes: () -> Unit) {
    val vis = store.estadoVisivel(st)
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Cabecalho("Painel", st) }
        item { FaixaEstado(st) }
        item {
            Cartao {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Rotulo("Planta"); Spacer(Modifier.weight(1f))
                    Text(if (st.config.modoDemo) "Simulação demo" else "Toque num equipamento", style = Tipo.Legenda)
                }
                Espaco(8)
                DiagramaPlanta(vis, aoTocar = aoAbrir)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Indicador("Tanque 1", vis.tanques[1]?.pct, Equipamento.T1, Modifier.weight(1f)) { aoAbrir(Equipamento.T1) }
                Indicador("Tanque 2", vis.tanques[2]?.pct, Equipamento.T2, Modifier.weight(1f)) { aoAbrir(Equipamento.T2) }
                val n = st.alarmesAtivos.size
                Cartao(Modifier.weight(1f), cor = Cores.Alerta, ativo = n > 0, aoClicar = aoAbrirAlarmes, descricao = "Alarmes ativos") {
                    Rotulo("Alarmes"); Espaco(6)
                    Text("$n", style = Tipo.Numero.copy(color = if (n > 0) Cores.Alerta else Cores.Ok, fontSize = 24.sp, lineHeight = 30.sp)); Text(if (n == 1) "ativo" else "ativos", style = Tipo.Legenda)
                }
            }
        }
        item {
            Cartao {
                Rotulo("Equipamentos"); Espaco(10)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinhaEquip(Equipamento.V3, vis.valvulas[3]?.rotulo, corValvula(vis.valvulas[3]), st, aoAbrir)
                    LinhaEquip(Equipamento.BOMBA, vis.bomba?.rotulo, corBomba(vis.bomba), st, aoAbrir)
                    LinhaEquip(Equipamento.V1, vis.valvulas[1]?.rotulo, corValvula(vis.valvulas[1]), st, aoAbrir)
                    LinhaEquip(Equipamento.V2, vis.valvulas[2]?.rotulo, corValvula(vis.valvulas[2]), st, aoAbrir)
                }
            }
        }
        if (st.comandos.isNotEmpty()) item {
            Cartao {
                Rotulo("Últimos comandos"); Espaco(8)
                st.comandos.take(4).forEach { LinhaComando(it) }
            }
        }
    }
}

@Composable
fun Cabecalho(titulo: String, st: EstadoUI) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("SuperviLab", style = Tipo.Rotulo.copy(color = Cores.Tanque))
            Text(titulo, style = Tipo.Titulo)
        }
        val (txt, cor) = when {
            st.plantaViva && st.modoSeguro -> "Modo seguro" to Cores.Aviso
            st.plantaViva -> (if (st.config.modoDemo) "Demo ao vivo" else "Ao vivo") to Cores.Ok
            st.conexao == EstadoConexao.CONECTADO -> "Sem sinal" to Cores.Alerta
            else -> st.conexao.rotulo to (if (st.conexao == EstadoConexao.DESCONECTADO) Cores.Desconhecido else Cores.Aviso)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { PontoEstado(cor, 8); Spacer(Modifier.width(6.dp)); Text(txt, style = Tipo.Legenda.copy(color = cor)) }
    }
}

@Composable
fun FaixaEstado(st: EstadoUI) {
    when {
        st.conexao == EstadoConexao.DESCONECTADO && st.erroConexao != null -> Faixa("Desconectado", Cores.Alerta, detalhe = st.erroConexao)
        st.conexao == EstadoConexao.DESCONECTADO -> Faixa("Desconectado: estados desconhecidos", Cores.Desconhecido, detalhe = "Conecte em Ajustes para ver dados reais")
        st.conexao != EstadoConexao.CONECTADO -> Faixa(st.conexao.rotulo, Cores.Aviso, detalhe = st.erroConexao)
        !st.plantaViva -> Faixa("Equipamento sem sinal de vida", Cores.Alerta, detalhe = "Conectado ao broker, mas sem heartbeat há mais de 6 s. Estados marcados como desconhecidos.")
        st.modoSeguro -> Faixa("Equipamento em modo seguro", Cores.Aviso, detalhe = "Válvulas fechadas e bomba desligada pelo próprio equipamento.")
        else -> Unit
    }
}

@Composable
private fun Indicador(nome: String, pct: Double?, eq: Equipamento, modifier: Modifier, aoClicar: () -> Unit) {
    Cartao(modifier, cor = Cores.Tanque, ativo = pct != null, aoClicar = aoClicar, descricao = nome) {
        Rotulo(nome); Espaco(6)
        Text(if (pct == null) "?" else "${PlantaStore.fmt(pct)}%", style = Tipo.Numero.copy(color = corNivel(pct), fontSize = 24.sp, lineHeight = 30.sp), maxLines = 1, softWrap = false)
        Text(if (pct == null) DESCONHECIDO else "${(pct * 10).toInt()} L", style = Tipo.Legenda, maxLines = 1)
    }
}

@Composable
fun LinhaEquip(eq: Equipamento, estado: String?, cor: Color, st: EstadoUI, aoAbrir: (Equipamento) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { aoAbrir(eq) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PontoEstado(cor); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(eq.nome, style = Tipo.Corpo); Text(eq.descricao, style = Tipo.Legenda) }
        if (st.comandoPendente(eq)) Pilula("Enviando…", Cores.Aviso) else Pilula(estado ?: DESCONHECIDO, cor)
    }
}

@Composable
fun LinhaComando(c: RegistroComando) {
    val cor = when (c.status) {
        StatusComando.CONCLUIDO, StatusComando.ACEITO -> Cores.Ok
        StatusComando.ENVIADO -> Cores.Aviso
        else -> Cores.Alerta
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${c.equipamento.nome}: ${c.cmd}", style = Tipo.Corpo)
            val det = buildString {
                append(PlantaStore.hora(c.enviadoEm))
                c.ackMs?.let { append(" · ack ${it} ms") }
                c.concluidoMs?.let { append(" · concluído em ${it / 1000.0} s") }
                if (c.detalhe.isNotBlank()) append(" · ${c.detalhe}")
            }
            Text(det, style = Tipo.Legenda)
        }
        Pilula(c.status.rotulo, cor)
    }
}
