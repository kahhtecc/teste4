package br.com.supervilab.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.supervilab.core.EstadoBomba
import br.com.supervilab.core.EstadoValvula

@Composable
fun vibrar(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
}

@Composable
fun Cartao(
    modifier: Modifier = Modifier,
    cor: Color? = null,
    ativo: Boolean = false,
    aoClicar: (() -> Unit)? = null,
    descricao: String? = null,
    conteudo: @Composable ColumnScope.() -> Unit,
) {
    val borda = if (ativo && cor != null) cor.copy(alpha = 0.55f) else Cores.Borda
    val fundo = if (ativo && cor != null) cor.copy(alpha = 0.08f) else Cores.Superficie1
    Column(
        modifier
            .clip(Formas.Card)
            .background(fundo)
            .border(BorderStroke(1.dp, borda), Formas.Card)
            .then(if (aoClicar != null) Modifier.clickable(onClick = aoClicar) else Modifier)
            .then(if (descricao != null) Modifier.semantics { contentDescription = descricao } else Modifier)
            .padding(16.dp),
        content = conteudo,
    )
}

@Composable
fun Rotulo(texto: String, modifier: Modifier = Modifier) = Text(texto.uppercase(), style = Tipo.Rotulo, modifier = modifier)

@Composable
fun Pilula(texto: String, cor: Color, modifier: Modifier = Modifier, preenchida: Boolean = false) {
    Box(
        modifier
            .clip(Formas.Pilula)
            .background(if (preenchida) cor else cor.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(texto, style = Tipo.Legenda.copy(color = if (preenchida) Cores.Fundo else cor)) }
}

@Composable
fun PontoEstado(cor: Color, tamanho: Int = 10) = Box(Modifier.size(tamanho.dp).clip(CircleShape).background(cor))

@Composable
fun Botao(
    texto: String,
    cor: Color,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    secundario: Boolean = false,
    descricao: String = texto,
    aoClicar: () -> Unit,
) {
    val vib = vibrar()
    val fundo = when { !habilitado -> Cores.Superficie2; secundario -> cor.copy(alpha = 0.14f); else -> cor }
    val corTexto = when { !habilitado -> Cores.TextoMuitoFraco; secundario -> cor; else -> Cores.Fundo }
    Box(
        modifier
            .clip(Formas.Botao)
            .background(fundo)
            .then(if (secundario) Modifier.border(1.dp, cor.copy(alpha = 0.4f), Formas.Botao) else Modifier)
            .clickable(enabled = habilitado) { vib(); aoClicar() }
            .semantics { contentDescription = descricao }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(texto, style = Tipo.Botao.copy(color = corTexto), textAlign = TextAlign.Center) }
}

@Composable
fun Faixa(texto: String, cor: Color, modifier: Modifier = Modifier, detalhe: String? = null) {
    Row(
        modifier.fillMaxWidth().clip(Formas.Chip).background(cor.copy(alpha = 0.14f)).border(1.dp, cor.copy(alpha = 0.5f), Formas.Chip)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PontoEstado(cor)
        Spacer(Modifier.width(10.dp))
        Column { Text(texto, style = Tipo.Corpo.copy(color = cor)); if (detalhe != null) Text(detalhe, style = Tipo.CorpoFraco) }
    }
}

/** Confirmação obrigatória antes de qualquer acionamento. */
@Composable
fun DialogoConfirmacao(titulo: String, mensagem: String, cor: Color, textoConfirmar: String, aoCancelar: () -> Unit, aoConfirmar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoCancelar,
        containerColor = Cores.Superficie2,
        titleContentColor = Cores.Texto,
        textContentColor = Cores.TextoFraco,
        shape = Formas.Card,
        title = { Text(titulo, style = Tipo.Subtitulo) },
        text = { Text(mensagem, style = Tipo.Corpo.copy(color = Cores.TextoFraco)) },
        confirmButton = { Botao(textoConfirmar, cor, descricao = "Confirmar $textoConfirmar", aoClicar = aoConfirmar) },
        dismissButton = { TextButton(onClick = aoCancelar, modifier = Modifier.semantics { contentDescription = "Cancelar" }) { Text("Cancelar", style = Tipo.Botao.copy(color = Cores.TextoFraco)) } },
    )
}

fun corValvula(e: EstadoValvula?): Color = when (e) {
    null -> Cores.Desconhecido
    EstadoValvula.ABERTA -> Cores.Valvula
    EstadoValvula.FECHADA -> Cores.TextoFraco
    EstadoValvula.ABRINDO, EstadoValvula.FECHANDO -> Cores.Aviso
    EstadoValvula.FALHA -> Cores.Alerta
}
fun corBomba(e: EstadoBomba?): Color = when (e) {
    null -> Cores.Desconhecido
    EstadoBomba.LIGADA -> Cores.Bomba
    EstadoBomba.DESLIGADA -> Cores.TextoFraco
    EstadoBomba.FALHA -> Cores.Alerta
}
fun corNivel(pct: Double?): Color = when {
    pct == null -> Cores.Desconhecido
    pct >= 90 || pct <= 10 -> Cores.Alerta
    pct >= 85 || pct <= 15 -> Cores.Aviso
    else -> Cores.Tanque
}
const val DESCONHECIDO = "Desconhecido"

@Composable
fun Espaco(dp: Int = 12) = Spacer(Modifier.height(dp.dp))

/** Estado simples de "mostrar diálogo" reutilizável. */
class PedidoComando(val titulo: String, val mensagem: String, val cor: Color, val botao: String, val acao: () -> Unit)

@Composable
fun rememberPedido(): androidx.compose.runtime.MutableState<PedidoComando?> = remember { mutableStateOf(null) }

@Composable
fun HostConfirmacao(estado: androidx.compose.runtime.MutableState<PedidoComando?>) {
    val p = estado.value ?: return
    DialogoConfirmacao(p.titulo, p.mensagem, p.cor, p.botao, aoCancelar = { estado.value = null }) { estado.value = null; p.acao() }
}

@Suppress("unused") private val paddingPadrao = PaddingValues(16.dp)
