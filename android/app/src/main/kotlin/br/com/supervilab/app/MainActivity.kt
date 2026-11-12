package br.com.supervilab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.supervilab.app.ui.*
import br.com.supervilab.app.ui.telas.*
import br.com.supervilab.core.Equipamento
import br.com.supervilab.core.PlantaStore
import kotlinx.coroutines.delay

enum class Aba(val rotulo: String, val icone: ImageVector) {
    PAINEL("Painel", Icons.Rounded.Home), EQUIPAMENTOS("Equipamentos", Icons.Rounded.List), ALARMES("Alarmes", Icons.Rounded.Notifications),
    HISTORICO("Histórico", Icons.Rounded.DateRange), AJUSTES("Ajustes", Icons.Rounded.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = SuperviLabApp.de(this)
        val store = app.store
        // Extras para testes e capturas automáticas: --ez demo true --es screen painel|equipamentos|alarmes|historico|ajustes|equip:valvula/1 --es cenario alarmes
        if (intent.getBooleanExtra("demo", false) && !store.estado.value.config.modoDemo) store.salvarConfig(store.estado.value.config.copy(modoDemo = true))
        store.iniciar()
        val telaInicial = intent.getStringExtra("screen")
        val cenario = intent.getStringExtra("cenario")
        val versao = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")
        setContent { TemaSuperviLab { Raiz(store, telaInicial, cenario, versao) } }
    }
}

@Composable
fun Raiz(store: PlantaStore, telaInicial: String?, cenario: String?, versao: String) {
    val st by store.estado.collectAsStateWithLifecycle()
    var aba by rememberSaveable { mutableStateOf(abaDe(telaInicial)) }
    var detalhe by rememberSaveable { mutableStateOf(telaInicial?.takeIf { it.startsWith("equip:") }?.removePrefix("equip:")) }
    val eqDetalhe = detalhe?.let { Equipamento.porId(it) }
    BackHandler(enabled = eqDetalhe != null) { detalhe = null }
    LaunchedEffect(cenario) {
        if (cenario == "alarmes") { // cenário para capturas: gera alarmes reais na demo
            delay(1500)
            store.simularFalha("valvula_sem_resposta", "valvula/3", true); delay(300)
            store.comandar(Equipamento.V3, "abrir")
            store.simularFalha("sensor_travado", "tanque/1", true)
        }
    }
    Box(Modifier.fillMaxSize().background(Cores.Fundo)) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when {
                eqDetalhe != null -> TelaDetalhe(eqDetalhe, st, store) { detalhe = null }
                aba == Aba.PAINEL -> TelaPainel(st, store, aoAbrir = { detalhe = it.id }, aoAbrirAlarmes = { aba = Aba.ALARMES })
                aba == Aba.EQUIPAMENTOS -> TelaEquipamentos(st, store) { detalhe = it.id }
                aba == Aba.ALARMES -> TelaAlarmes(st, store)
                aba == Aba.HISTORICO -> TelaHistorico(st)
                aba == Aba.AJUSTES -> TelaAjustes(st, store, versao)
            }
        }
        BarraInferior(aba, st.alarmesNaoReconhecidos, Modifier.align(Alignment.BottomCenter)) { aba = it; detalhe = null }
    }
}

private fun abaDe(s: String?) = when (s) {
    "equipamentos" -> Aba.EQUIPAMENTOS; "alarmes" -> Aba.ALARMES; "historico" -> Aba.HISTORICO; "ajustes" -> Aba.AJUSTES; else -> Aba.PAINEL
}

@Composable
fun BarraInferior(atual: Aba, alarmes: Int, modifier: Modifier, aoSelecionar: (Aba) -> Unit) {
    val vib = vibrar()
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
            .clip(Formas.Card).background(Cores.Superficie2.copy(alpha = 0.96f)).border(1.dp, Cores.Borda, Formas.Card).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (a in Aba.entries) {
            val sel = a == atual
            Column(
                Modifier.weight(1f).clip(Formas.Chip).background(if (sel) Cores.Superficie3 else Cores.Superficie2.copy(alpha = 0f))
                    .clickable { vib(); aoSelecionar(a) }.semantics { contentDescription = "Aba ${a.rotulo}" }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(a.icone, contentDescription = null, tint = if (sel) Cores.Tanque else Cores.TextoFraco, modifier = Modifier.size(22.dp))
                    if (a == Aba.ALARMES && alarmes > 0) Box(Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(Cores.Alerta), contentAlignment = Alignment.Center) {
                        Text(if (alarmes > 9) "9+" else "$alarmes", style = Tipo.Rotulo.copy(color = Cores.Fundo, fontSize = 8.sp, letterSpacing = 0.sp))
                    }
                }
                Text(a.rotulo, style = Tipo.Rotulo.copy(color = if (sel) Cores.Tanque else Cores.TextoFraco, fontSize = 9.sp, letterSpacing = 0.3.sp), maxLines = 1)
            }
        }
    }
}
