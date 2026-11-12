package br.com.supervilab.app.ui.telas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import br.com.supervilab.app.ui.*
import br.com.supervilab.core.*

@Composable
fun TelaEquipamentos(st: EstadoUI, store: PlantaStore, aoAbrir: (Equipamento) -> Unit) {
    val vis = store.estadoVisivel(st)
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Cabecalho("Equipamentos", st) }
        item { FaixaEstado(st) }
        items(Equipamento.TODOS) { eq ->
            val (estado, cor) = when (eq.tipo) {
                TipoEquipamento.TANQUE -> vis.tanques[eq.numero]?.pct.let { p -> (if (p == null) DESCONHECIDO else "${PlantaStore.fmt(p)} %") to corNivel(p) }
                TipoEquipamento.VALVULA -> vis.valvulas[eq.numero].let { (it?.rotulo ?: DESCONHECIDO) to corValvula(it) }
                TipoEquipamento.BOMBA -> vis.bomba.let { (it?.rotulo ?: DESCONHECIDO) to corBomba(it) }
            }
            val ativo = when (eq.tipo) {
                TipoEquipamento.TANQUE -> vis.tanques[eq.numero] != null
                TipoEquipamento.VALVULA -> vis.valvulas[eq.numero] == EstadoValvula.ABERTA
                TipoEquipamento.BOMBA -> vis.bomba == EstadoBomba.LIGADA
            }
            Cartao(cor = Cores.doTipo(eq.tipo), ativo = ativo, aoClicar = { aoAbrir(eq) }, descricao = "Abrir ${eq.nome}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PontoEstado(cor, 12); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(eq.tipo.rotulo.uppercase(), style = Tipo.Rotulo.copy(color = Cores.doTipo(eq.tipo)))
                        Text(eq.nome, style = Tipo.Subtitulo); Text(eq.descricao, style = Tipo.CorpoFraco)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(estado, style = Tipo.NumeroPequeno.copy(color = cor))
                        val alarmes = st.alarmesAtivos.count { it.equipamento == eq }
                        if (alarmes > 0) { Spacer(Modifier.height(4.dp)); Pilula("$alarmes alarme${if (alarmes > 1) "s" else ""}", Cores.Alerta) }
                    }
                }
            }
        }
    }
}

@Composable
fun TelaDetalhe(eq: Equipamento, st: EstadoUI, store: PlantaStore, aoVoltar: () -> Unit) {
    val vis = store.estadoVisivel(st)
    val pedido = rememberPedido()
    val cor = Cores.doTipo(eq.tipo)
    val vib = vibrar()
    HostConfirmacao(pedido)
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar", tint = Cores.Texto,
                    modifier = Modifier.clickable { vib(); aoVoltar() }.padding(8.dp).semantics { contentDescription = "Voltar" })
                Spacer(Modifier.width(8.dp))
                Column { Text(eq.tipo.rotulo.uppercase(), style = Tipo.Rotulo.copy(color = cor)); Text(eq.nome, style = Tipo.Titulo) }
            }
        }
        item { FaixaEstado(st) }
        item {
            Cartao(cor = cor, ativo = st.plantaViva) {
                Rotulo("Estado atual"); Espaco(8)
                when (eq.tipo) {
                    TipoEquipamento.TANQUE -> {
                        val n = vis.tanques[eq.numero]
                        Text(if (n?.pct == null) "?" else "${PlantaStore.fmt(n.pct)}%", style = Tipo.Numero.copy(color = corNivel(n?.pct)))
                        Text(if (n?.pct == null) DESCONHECIDO else "${(n.pct * 10).toInt()} L de 1000 L · ${n.qualidade.rotulo}", style = Tipo.CorpoFraco)
                        Espaco(10); BarraNivel(n?.pct)
                    }
                    TipoEquipamento.VALVULA -> {
                        val v = vis.valvulas[eq.numero]
                        Text(v?.rotulo ?: DESCONHECIDO, style = Tipo.Numero.copy(color = corValvula(v)))
                        Text(eq.descricao, style = Tipo.CorpoFraco)
                    }
                    TipoEquipamento.BOMBA -> {
                        val b = vis.bomba
                        Text(b?.rotulo ?: DESCONHECIDO, style = Tipo.Numero.copy(color = corBomba(b)))
                        Text(eq.descricao, style = Tipo.CorpoFraco)
                    }
                }
                if (!st.plantaViva) { Espaco(8); Text("Sem conexão com o equipamento: o último valor conhecido não é mostrado como atual.", style = Tipo.Legenda.copy(color = Cores.Alerta)) }
            }
        }
        if (eq.tipo != TipoEquipamento.TANQUE) item {
            Cartao {
                Rotulo("Comandos"); Espaco(4)
                Text("Cada comando pede confirmação e tem tempo limite de ${Protocolo.TIMEOUT_ACK_MS / 1000.0} s para a resposta.", style = Tipo.CorpoFraco); Espaco(12)
                val conectado = st.conexao == EstadoConexao.CONECTADO
                val (a, b) = if (eq.tipo == TipoEquipamento.VALVULA) ("abrir" to "fechar") else ("ligar" to "desligar")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Botao(a.replaceFirstChar { it.uppercase() }, cor, Modifier.weight(1f), habilitado = conectado, descricao = "${a.replaceFirstChar { it.uppercase() }} ${eq.nome}") {
                        pedido.value = PedidoComando("${a.replaceFirstChar { it.uppercase() }} ${eq.nome}?", "${eq.descricao}. O comando será enviado agora ao equipamento.", cor, a.replaceFirstChar { it.uppercase() }) { store.comandar(eq, a) }
                    }
                    Botao(b.replaceFirstChar { it.uppercase() }, Cores.TextoFraco, Modifier.weight(1f), habilitado = conectado, secundario = true, descricao = "${b.replaceFirstChar { it.uppercase() }} ${eq.nome}") {
                        pedido.value = PedidoComando("${b.replaceFirstChar { it.uppercase() }} ${eq.nome}?", "${eq.descricao}. O comando será enviado agora ao equipamento.", Cores.TextoFraco, b.replaceFirstChar { it.uppercase() }) { store.comandar(eq, b) }
                    }
                }
                if (!conectado) { Espaco(8); Text("Comandos bloqueados: sem conexão com o broker.", style = Tipo.Legenda.copy(color = Cores.Alerta)) }
                if (eq == Equipamento.BOMBA) { Espaco(8); Text("Intertravamento: a bomba só liga com a Válvula 1 aberta e desliga sozinha se ela fechar ou o Tanque 1 esvaziar.", style = Tipo.Legenda) }
            }
        }
        val alarmesEq = st.alarmes.filter { it.equipamento == eq }
        if (alarmesEq.isNotEmpty()) item {
            Cartao { Rotulo("Alarmes deste equipamento"); Espaco(8); alarmesEq.take(5).forEach { LinhaAlarme(it, store) } }
        }
        val cmds = st.comandos.filter { it.equipamento == eq }
        if (cmds.isNotEmpty()) item { Cartao { Rotulo("Histórico de comandos"); Espaco(8); cmds.take(8).forEach { LinhaComando(it) } } }
        if (eq.tipo == TipoEquipamento.TANQUE) item {
            Cartao { Rotulo("Últimos 10 minutos"); Espaco(8); GraficoNiveis(st, listOf(eq.numero), 10, Modifier.fillMaxWidth().height(160.dp)) }
        }
    }
}

@Composable
fun BarraNivel(pct: Double?) {
    val cor = corNivel(pct)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(Cores.Superficie3, cornerRadius = r)
        if (pct != null) drawRoundRect(cor, size = androidx.compose.ui.geometry.Size(size.width * (pct / 100.0).toFloat(), size.height), cornerRadius = r)
        for (m in listOf(0.10f, 0.90f)) drawLine(Cores.Alerta.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(size.width * m, 0f), androidx.compose.ui.geometry.Offset(size.width * m, size.height), 2f)
    }
}
