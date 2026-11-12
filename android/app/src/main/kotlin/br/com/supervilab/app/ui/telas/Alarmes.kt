package br.com.supervilab.app.ui.telas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.supervilab.app.ui.*
import br.com.supervilab.core.*

@Composable
fun TelaAlarmes(st: EstadoUI, store: PlantaStore) {
    val ativos = st.alarmesAtivos
    val historico = st.alarmes.filter { !it.ativo }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Cabecalho("Alarmes", st) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${ativos.size}", style = Tipo.Numero.copy(color = if (ativos.isEmpty()) Cores.Ok else Cores.Alerta))
                    Text(if (ativos.isEmpty()) "nenhum alarme ativo" else "${st.alarmesNaoReconhecidos} sem reconhecer", style = Tipo.CorpoFraco)
                }
                if (st.alarmesNaoReconhecidos > 0) Botao("Reconhecer todos", Cores.Alerta, secundario = true) { store.reconhecerTodos() }
            }
        }
        if (ativos.isNotEmpty()) {
            item { Rotulo("Ativos") }
            items(ativos, key = { it.id }) { a -> Cartao(cor = if (a.tipo.critico) Cores.Alerta else Cores.Aviso, ativo = !a.reconhecido) { LinhaAlarme(a, store) } }
        }
        if (historico.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Rotulo("Histórico", Modifier.weight(1f))
                    Text("Limpar", style = Tipo.Legenda.copy(color = Cores.TextoFraco), modifier = Modifier.clickable { store.limparHistoricoAlarmes() }.padding(4.dp))
                }
            }
            items(historico, key = { it.id }) { a -> Cartao { LinhaAlarme(a, store) } }
        }
        if (ativos.isEmpty() && historico.isEmpty()) item {
            Cartao { Text("Tudo normal", style = Tipo.Subtitulo.copy(color = Cores.Ok)); Espaco(4); Text("Alarmes locais: nível alto (≥ 90 %) e baixo (≤ 10 %), válvula ou bomba sem resposta, sensor sem leitura ou travado, modo seguro e conexão perdida.", style = Tipo.CorpoFraco) }
        }
    }
}

@Composable
fun LinhaAlarme(a: Alarme, store: PlantaStore) {
    val cor = when { !a.ativo -> Cores.TextoFraco; a.tipo.critico -> Cores.Alerta; else -> Cores.Aviso }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PontoEstado(cor, 12); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.tipo.titulo, style = Tipo.Subtitulo.copy(color = if (a.ativo) Cores.Texto else Cores.TextoFraco))
            Text(a.mensagem, style = Tipo.CorpoFraco)
            Text(buildString {
                append("Início ${PlantaStore.hora(a.inicio)}")
                a.fim?.let { append(" · fim ${PlantaStore.hora(it)}") }
                if (a.reconhecido) append(" · reconhecido")
            }, style = Tipo.Legenda)
        }
        if (a.ativo && !a.reconhecido) Botao("OK", cor, secundario = true, descricao = "Reconhecer ${a.tipo.titulo}") { store.reconhecerAlarme(a.id) }
        else if (!a.ativo) Pilula("Normalizado", Cores.Ok)
        else Pilula("Ativo", cor)
    }
}
