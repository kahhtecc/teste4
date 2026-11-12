package br.com.supervilab.app.ui.telas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import br.com.supervilab.app.ui.*
import br.com.supervilab.core.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelaAjustes(st: EstadoUI, store: PlantaStore, versao: String) {
    val ctx = LocalContext.current
    var cfg by remember(st.config) { mutableStateOf(st.config) }
    var mostrarSenha by remember { mutableStateOf(false) }
    val pedido = rememberPedido()
    HostConfirmacao(pedido)

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Cabecalho("Ajustes", st) }
        item {
            Cartao(cor = Cores.Tanque, ativo = cfg.modoDemo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Modo demo", style = Tipo.Subtitulo); Text("Planta simulada dentro do app, sem broker nem hardware.", style = Tipo.CorpoFraco) }
                    Chave(cfg.modoDemo, "Modo demo") { cfg = cfg.copy(modoDemo = it) }
                }
            }
        }
        item {
            Cartao {
                Rotulo("Broker MQTT"); Espaco(10)
                Campo(cfg.host, "Endereço (IP ou host)", "Endereço do broker", habilitado = !cfg.modoDemo) { cfg = cfg.copy(host = it.trim()) }
                Espaco(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Campo(cfg.porta.toString(), "Porta", "Porta", Modifier.weight(1f), teclado = KeyboardType.Number, habilitado = !cfg.modoDemo) { cfg = cfg.copy(porta = it.filter { c -> c.isDigit() }.take(5).toIntOrNull() ?: 0) }
                    Campo(cfg.clienteId, "ID do cliente", "ID do cliente", Modifier.weight(2f), habilitado = !cfg.modoDemo) { cfg = cfg.copy(clienteId = it.trim()) }
                }
                Espaco(8)
                Campo(cfg.usuario, "Usuário", "Usuário", habilitado = !cfg.modoDemo) { cfg = cfg.copy(usuario = it) }
                Espaco(8)
                Campo(cfg.senha, "Senha", "Senha", habilitado = !cfg.modoDemo, senha = !mostrarSenha) { cfg = cfg.copy(senha = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mostrar senha", style = Tipo.CorpoFraco, modifier = Modifier.weight(1f)); Chave(mostrarSenha, "Mostrar senha") { mostrarSenha = it }
                }
                Espaco(4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("TLS (porta 8883)", style = Tipo.Corpo); Text("Recomendado. Sem TLS, usuário e senha trafegam abertos.", style = Tipo.Legenda) }
                    Chave(cfg.tls, "TLS") { cfg = cfg.copy(tls = it, porta = if (it && cfg.porta == 1883) 8883 else if (!it && cfg.porta == 8883) 1883 else cfg.porta) }
                }
                if (cfg.tls) {
                    Espaco(8)
                    Campo(cfg.caPem, "Certificado da CA (PEM), opcional", "Certificado CA", linhas = 4, habilitado = !cfg.modoDemo) { cfg = cfg.copy(caPem = it) }
                    Text("Cole o conteúdo de ca.crt se o broker usa certificado próprio. Vazio = CAs do sistema.", style = Tipo.Legenda)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Verificar nome do host no certificado", style = Tipo.CorpoFraco, modifier = Modifier.weight(1f)); Chave(cfg.verificarNomeHost, "Verificar nome do host") { cfg = cfg.copy(verificarNomeHost = it) }
                    }
                }
                Espaco(12)
                val erro = cfg.valida()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Botao("Salvar e conectar", Cores.Tanque, Modifier.weight(1f), habilitado = erro == null) { store.salvarConfig(cfg) }
                    Botao("Desconectar", Cores.TextoFraco, secundario = true, habilitado = st.conexao != EstadoConexao.DESCONECTADO) { store.desconectar() }
                }
                if (erro != null) { Espaco(6); Text(erro, style = Tipo.Legenda.copy(color = Cores.Aviso)) }
                Espaco(8)
                val corCon = when (st.conexao) { EstadoConexao.CONECTADO -> Cores.Ok; EstadoConexao.DESCONECTADO -> Cores.Desconhecido; else -> Cores.Aviso }
                Row(verticalAlignment = Alignment.CenterVertically) { PontoEstado(corCon); Spacer(Modifier.width(8.dp)); Text(st.conexao.rotulo + (st.erroConexao?.let { ": $it" } ?: ""), style = Tipo.CorpoFraco) }
                Text("A senha fica só neste aparelho, nas preferências privadas do app.", style = Tipo.Legenda)
            }
        }
        item {
            Cartao {
                Rotulo("Simular falhas"); Espaco(4)
                Text(if (st.config.modoDemo) "Teste os alarmes na planta demo." else "Funciona com o simulador Python (tópico planta/sim/falha). O ESP32 ignora.", style = Tipo.CorpoFraco); Espaco(10)
                val ativas = st.falhasSimuladas
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotaoFalha("Sensor travado T1", "sensor_travado:tanque/1" in ativas) { store.simularFalha("sensor_travado", "tanque/1", it) }
                    BotaoFalha("Sensor inválido T2", "sensor_invalido:tanque/2" in ativas) { store.simularFalha("sensor_invalido", "tanque/2", it) }
                    BotaoFalha("Válvula 2 sem resposta", "valvula_sem_resposta:valvula/2" in ativas) { store.simularFalha("valvula_sem_resposta", "valvula/2", it) }
                    BotaoFalha("Queda de rede 10 s", false) { store.simularFalha("perda_conexao", null, true, 10) }
                }
            }
        }
        item {
            Cartao {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Rotulo("Diagnóstico", Modifier.weight(1f))
                    Botao("Copiar relatório", Cores.Bomba, secundario = true) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("SuperviLab diagnóstico", store.relatorioDiagnostico()))
                        Toast.makeText(ctx, "Relatório copiado", Toast.LENGTH_SHORT).show()
                    }
                }
                Espaco(8)
                if (st.diagnostico.isEmpty()) Text("Sem eventos ainda.", style = Tipo.CorpoFraco)
                st.diagnostico.take(12).forEach { Text("${PlantaStore.hora(it.ts)}  ${it.texto}", style = Tipo.Legenda.copy(color = if (it.texto.startsWith("ALARME") || it.texto.startsWith("⚠")) Cores.Alerta else Cores.TextoFraco)) }
            }
        }
        item {
            Cartao {
                Rotulo("Sobre"); Espaco(6)
                Text("SuperviLab $versao", style = Tipo.Subtitulo); Text("Mini-supervisório para válvulas, bomba e tanques via MQTT.", style = Tipo.CorpoFraco); Espaco(8)
                Text("⚠ Segurança: este app não substitui proteção física. Use apenas em equipamento com botão de emergência, válvula de alívio e intertravamentos independentes do software.", style = Tipo.Legenda.copy(color = Cores.Aviso))
                Espaco(6); Text("Fontes Sora e Manrope (SIL Open Font License).", style = Tipo.Legenda)
            }
        }
    }
}

@Composable
private fun Chave(valor: Boolean, descricao: String, aoMudar: (Boolean) -> Unit) {
    val vib = vibrar()
    Switch(valor, { vib(); aoMudar(it) }, modifier = Modifier.semantics { contentDescription = descricao },
        colors = SwitchDefaults.colors(checkedThumbColor = Cores.Fundo, checkedTrackColor = Cores.Tanque, uncheckedThumbColor = Cores.TextoFraco, uncheckedTrackColor = Cores.Superficie3, uncheckedBorderColor = Cores.Borda))
}

@Composable
private fun Campo(valor: String, rotulo: String, descricao: String, modifier: Modifier = Modifier, teclado: KeyboardType = KeyboardType.Text,
                  habilitado: Boolean = true, senha: Boolean = false, linhas: Int = 1, aoMudar: (String) -> Unit) {
    OutlinedTextField(
        valor, aoMudar, modifier.fillMaxWidth().semantics { contentDescription = descricao },
        label = { Text(rotulo, style = Tipo.Legenda) }, enabled = habilitado, singleLine = linhas == 1, minLines = linhas,
        textStyle = Tipo.Corpo, shape = Formas.Chip,
        keyboardOptions = KeyboardOptions(keyboardType = if (senha) KeyboardType.Password else teclado),
        visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cores.Tanque, unfocusedBorderColor = Cores.Borda, disabledBorderColor = Cores.Borda.copy(alpha = 0.5f),
            focusedTextColor = Cores.Texto, unfocusedTextColor = Cores.Texto, disabledTextColor = Cores.TextoMuitoFraco,
            cursorColor = Cores.Tanque, focusedLabelColor = Cores.Tanque, unfocusedLabelColor = Cores.TextoFraco, disabledLabelColor = Cores.TextoMuitoFraco,
            focusedContainerColor = Cores.Superficie2, unfocusedContainerColor = Cores.Superficie2, disabledContainerColor = Cores.Superficie1,
        ),
    )
}

@Composable
private fun BotaoFalha(texto: String, ativa: Boolean, aoMudar: (Boolean) -> Unit) {
    Botao(if (ativa) "✓ $texto" else texto, Cores.Alerta, secundario = !ativa, descricao = "Falha: $texto") { aoMudar(!ativa) }
}

@Suppress("unused") private val alturaMinima = Modifier.height(1.dp)
