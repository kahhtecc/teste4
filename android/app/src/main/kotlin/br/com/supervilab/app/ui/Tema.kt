package br.com.supervilab.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.supervilab.app.R
import br.com.supervilab.core.TipoEquipamento

/** Paleta própria: grafite azulado em três níveis, uma cor por tipo de equipamento. */
object Cores {
    val Fundo = Color(0xFF0B1016)
    val Superficie1 = Color(0xFF121922)
    val Superficie2 = Color(0xFF1A2430)
    val Superficie3 = Color(0xFF233040)
    val Borda = Color(0xFF2A3747)
    val Texto = Color(0xFFE8EEF5)
    val TextoFraco = Color(0xFF94A3B8)
    val TextoMuitoFraco = Color(0xFF5B6B80)

    val Tanque = Color(0xFF2DD4BF)      // teal
    val Valvula = Color(0xFFF59E0B)     // âmbar
    val Bomba = Color(0xFF38BDF8)       // ciano
    val Ok = Color(0xFF34D399)          // verde
    val Alerta = Color(0xFFFB7185)      // coral
    val Aviso = Color(0xFFFBBF24)       // amarelo
    val Desconhecido = Color(0xFF64748B)
    val Agua = Color(0xFF1FB2A6)
    val AguaClara = Color(0xFF5EEAD4)

    fun doTipo(t: TipoEquipamento) = when (t) {
        TipoEquipamento.TANQUE -> Tanque; TipoEquipamento.VALVULA -> Valvula; TipoEquipamento.BOMBA -> Bomba
    }
}

val Sora = FontFamily(
    Font(R.font.sora_regular, FontWeight.Normal),
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_bold, FontWeight.Bold),
)
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

object Tipo {
    val Titulo = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, color = Cores.Texto)
    val Subtitulo = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, color = Cores.Texto)
    val Numero = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, color = Cores.Texto)
    val NumeroPequeno = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 18.sp, color = Cores.Texto)
    val Corpo = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp, color = Cores.Texto)
    val CorpoFraco = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp, color = Cores.TextoFraco)
    val Legenda = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp, color = Cores.TextoFraco, letterSpacing = 0.4.sp)
    val Rotulo = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 13.sp, color = Cores.TextoFraco, letterSpacing = 1.2.sp)
    val Botao = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 18.sp, color = Cores.Texto)
}

object Formas {
    val Card = RoundedCornerShape(20.dp)
    val Chip = RoundedCornerShape(12.dp)
    val Botao = RoundedCornerShape(14.dp)
    val Pilula = RoundedCornerShape(50)
}

private val esquema = darkColorScheme(
    primary = Cores.Tanque, onPrimary = Cores.Fundo, secondary = Cores.Bomba, tertiary = Cores.Valvula,
    background = Cores.Fundo, onBackground = Cores.Texto, surface = Cores.Superficie1, onSurface = Cores.Texto,
    surfaceVariant = Cores.Superficie2, onSurfaceVariant = Cores.TextoFraco, outline = Cores.Borda, error = Cores.Alerta,
)

@Composable
fun TemaSuperviLab(conteudo: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val escuro = isSystemInDarkTheme() // o app é sempre escuro, por desenho
    MaterialTheme(
        colorScheme = esquema,
        typography = Typography(bodyLarge = Tipo.Corpo, bodyMedium = Tipo.Corpo, labelLarge = Tipo.Botao, titleMedium = Tipo.Subtitulo),
    ) {
        CompositionLocalProvider(LocalContentColor provides Cores.Texto, content = conteudo)
    }
}
