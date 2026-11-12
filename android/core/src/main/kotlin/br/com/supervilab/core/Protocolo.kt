package br.com.supervilab.core

/** Tópicos e payloads do protocolo (ver PROTOCOLO.md). */
object Protocolo {
    const val PREFIXO = "planta"
    const val STATUS = "$PREFIXO/status"
    const val HEARTBEAT = "$PREFIXO/heartbeat"
    const val SIM_FALHA = "$PREFIXO/sim/falha"
    const val SIM_FALHAS = "$PREFIXO/sim/falhas"
    const val ASSINATURA_TUDO = "$PREFIXO/#"

    const val PERIODO_HEARTBEAT_MS = 2_000L
    const val TIMEOUT_HEARTBEAT_MS = 6_000L
    const val TIMEOUT_ACK_MS = 1_500L
    const val TIMEOUT_ESTADO_FINAL_MS = 8_000L
    const val NIVEL_ALTO_PCT = 90.0
    const val NIVEL_BAIXO_PCT = 10.0
    const val HISTERESE_PCT = 2.0

    fun topicoEstado(e: Equipamento) = when (e.tipo) {
        TipoEquipamento.TANQUE -> "$PREFIXO/tanque/${e.numero}/nivel"
        TipoEquipamento.VALVULA -> "$PREFIXO/valvula/${e.numero}/estado"
        TipoEquipamento.BOMBA -> "$PREFIXO/bomba/${e.numero}/estado"
    }
    fun topicoCmd(e: Equipamento) = "$PREFIXO/${e.tipo.chave}/${e.numero}/cmd"
    fun topicoAck(e: Equipamento) = "$PREFIXO/${e.tipo.chave}/${e.numero}/ack"

    /** Interpreta um tópico: retorna (equipamento, sufixo) ou null. */
    fun interpretar(topico: String): Pair<Equipamento, String>? {
        val p = topico.split('/')
        if (p.size != 4 || p[0] != PREFIXO) return null
        val tipo = TipoEquipamento.entries.firstOrNull { it.chave == p[1] } ?: return null
        val n = p[2].toIntOrNull() ?: return null
        return Equipamento(tipo, n) to p[3]
    }

    fun payloadCmd(cmd: String, id: String, ts: Long) = Json.stringify(mapOf("cmd" to cmd, "id" to id, "ts" to ts))
    fun payloadFalha(tipo: String, alvo: String?, ativa: Boolean, duracaoS: Int = 10) =
        Json.stringify(mapOf("tipo" to tipo, "alvo" to alvo, "ativa" to ativa, "duracao_s" to duracaoS))
}

enum class TipoEquipamento(val chave: String, val rotulo: String) {
    VALVULA("valvula", "Válvula"), BOMBA("bomba", "Bomba"), TANQUE("tanque", "Tanque")
}

data class Equipamento(val tipo: TipoEquipamento, val numero: Int) {
    val id: String get() = "${tipo.chave}/$numero"
    val nome: String get() = when (tipo) {
        TipoEquipamento.VALVULA -> "Válvula $numero"
        TipoEquipamento.BOMBA -> "Bomba"
        TipoEquipamento.TANQUE -> "Tanque $numero"
    }
    val descricao: String get() = when (id) {
        "valvula/1" -> "Saída da bomba para o Tanque 2"
        "valvula/2" -> "Dreno do Tanque 2"
        "valvula/3" -> "Alimentação do Tanque 1 (rede)"
        "bomba/1" -> "Transfere do Tanque 1 para o Tanque 2"
        "tanque/1" -> "IBC 1000 L, fonte"
        "tanque/2" -> "IBC 1000 L, destino"
        else -> ""
    }
    companion object {
        val V1 = Equipamento(TipoEquipamento.VALVULA, 1)
        val V2 = Equipamento(TipoEquipamento.VALVULA, 2)
        val V3 = Equipamento(TipoEquipamento.VALVULA, 3)
        val BOMBA = Equipamento(TipoEquipamento.BOMBA, 1)
        val T1 = Equipamento(TipoEquipamento.TANQUE, 1)
        val T2 = Equipamento(TipoEquipamento.TANQUE, 2)
        val TODOS = listOf(T1, V3, BOMBA, V1, T2, V2)
        val VALVULAS = listOf(V1, V2, V3)
        fun porId(id: String): Equipamento? = TODOS.firstOrNull { it.id == id }
    }
}

enum class EstadoValvula(val chave: String, val rotulo: String) {
    ABERTA("aberta", "Aberta"), FECHADA("fechada", "Fechada"), ABRINDO("abrindo", "Abrindo…"),
    FECHANDO("fechando", "Fechando…"), FALHA("falha", "Falha");
    val emMovimento get() = this == ABRINDO || this == FECHANDO
    companion object { fun de(s: String?) = entries.firstOrNull { it.chave == s } }
}

enum class EstadoBomba(val chave: String, val rotulo: String) {
    LIGADA("ligada", "Ligada"), DESLIGADA("desligada", "Desligada"), FALHA("falha", "Falha");
    companion object { fun de(s: String?) = entries.firstOrNull { it.chave == s } }
}

enum class QualidadeSensor(val chave: String, val rotulo: String) {
    BOA("boa", "Leitura normal"), SUSPEITA("suspeita", "Leitura suspeita"), INVALIDA("invalida", "Sensor sem leitura");
    companion object { fun de(s: String?) = entries.firstOrNull { it.chave == s } ?: BOA }
}

data class NivelTanque(val pct: Double?, val volumeL: Double?, val qualidade: QualidadeSensor, val ts: Long)
data class Heartbeat(val ts: Long, val uptimeS: Long, val modoSeguro: Boolean, val seq: Long)
data class Ack(val id: String?, val ok: Boolean, val motivo: String?, val ts: Long)

/** Decodificação dos payloads. Qualquer erro retorna null (nunca derruba o app). */
object Payloads {
    fun valvula(p: String): Pair<EstadoValvula, Long>? = runCatching {
        val m = Json.objeto(p); EstadoValvula.de(m.str("estado"))?.let { it to (m.lng("ts") ?: 0L) }
    }.getOrNull()

    fun bomba(p: String): Pair<EstadoBomba, Long>? = runCatching {
        val m = Json.objeto(p); EstadoBomba.de(m.str("estado"))?.let { it to (m.lng("ts") ?: 0L) }
    }.getOrNull()

    fun nivel(p: String): NivelTanque? = runCatching {
        val m = Json.objeto(p)
        val q = QualidadeSensor.de(m.str("qualidade"))
        val pct = m.num("nivel_pct")
        if (q != QualidadeSensor.INVALIDA && (pct == null || pct < 0 || pct > 100)) return null
        NivelTanque(pct, m.num("volume_l"), q, m.lng("ts") ?: 0L)
    }.getOrNull()

    fun heartbeat(p: String): Heartbeat? = runCatching {
        val m = Json.objeto(p)
        Heartbeat(m.lng("ts") ?: 0L, m.lng("uptime_s") ?: 0L, m.str("modo") == "seguro", m.lng("seq") ?: 0L)
    }.getOrNull()

    fun ack(p: String): Ack? = runCatching {
        val m = Json.objeto(p); Ack(m.str("id"), m.bool("ok") ?: false, m.str("motivo"), m.lng("ts") ?: 0L)
    }.getOrNull()

    fun statusOnline(p: String): Boolean? = runCatching { Json.objeto(p).bool("online") }.getOrNull()

    fun motivoLegivel(m: String?): String = when (m) {
        null, "" -> ""
        "valvula_1_fechada" -> "a válvula 1 precisa estar aberta"
        "tanque_1_vazio" -> "o Tanque 1 está vazio"
        "modo_seguro" -> "o equipamento está em modo seguro"
        "cmd_invalido" -> "comando inválido"
        "json_invalido" -> "payload inválido"
        "equipamento_desconhecido" -> "equipamento desconhecido"
        "ja_estava" -> "já estava nesse estado"
        else -> m
    }
}
