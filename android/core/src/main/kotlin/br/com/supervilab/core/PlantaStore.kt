package br.com.supervilab.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class EstadoConexao(val rotulo: String) {
    DESCONECTADO("Desconectado"), CONECTANDO("Conectando…"), CONECTADO("Conectado"), RECONECTANDO("Reconectando…")
}

enum class TipoAlarme(val titulo: String, val critico: Boolean) {
    CONEXAO_PERDIDA("Conexão perdida", true),
    MODO_SEGURO("Equipamento em modo seguro", true),
    NIVEL_ALTO("Nível alto", true),
    NIVEL_BAIXO("Nível baixo", false),
    SENSOR_INVALIDO("Sensor de nível sem leitura", true),
    SENSOR_SUSPEITO("Sensor de nível possivelmente travado", false),
    VALVULA_SEM_RESPOSTA("Válvula sem resposta", true),
    BOMBA_SEM_RESPOSTA("Bomba sem resposta", true),
}

data class Alarme(
    val id: Long, val tipo: TipoAlarme, val equipamento: Equipamento?, val mensagem: String,
    val inicio: Long, val fim: Long? = null, val reconhecido: Boolean = false,
) { val ativo get() = fim == null }

enum class StatusComando(val rotulo: String) {
    ENVIADO("Enviado"), ACEITO("Aceito"), CONCLUIDO("Concluído"), RECUSADO("Recusado"), SEM_RESPOSTA("Sem resposta"), ERRO("Erro ao enviar")
}

data class RegistroComando(
    val id: String, val equipamento: Equipamento, val cmd: String, val enviadoEm: Long,
    val status: StatusComando = StatusComando.ENVIADO, val detalhe: String = "",
    val ackMs: Long? = null, val concluidoMs: Long? = null,
) { val pendente get() = status == StatusComando.ENVIADO || status == StatusComando.ACEITO }

data class Ponto(val ts: Long, val pct: Double)
data class RegistroDiag(val ts: Long, val texto: String)

data class EstadoUI(
    val config: ConfigConexao = ConfigConexao(),
    val conexao: EstadoConexao = EstadoConexao.DESCONECTADO,
    val erroConexao: String? = null,
    /** true quando conectado e com heartbeat recente: só então os valores são confiáveis. */
    val plantaViva: Boolean = false,
    val modoSeguro: Boolean = false,
    val ultimoHeartbeat: Long = 0L,
    val ultimoDadoEm: Long = 0L,
    /** null = DESCONHECIDO (sem conexão ou dado velho). */
    val valvulas: Map<Int, EstadoValvula?> = mapOf(1 to null, 2 to null, 3 to null),
    val bomba: EstadoBomba? = null,
    val tanques: Map<Int, NivelTanque?> = mapOf(1 to null, 2 to null),
    val alarmes: List<Alarme> = emptyList(),
    val historico: Map<Int, List<Ponto>> = mapOf(1 to emptyList(), 2 to emptyList()),
    val comandos: List<RegistroComando> = emptyList(),
    val diagnostico: List<RegistroDiag> = emptyList(),
    val falhasSimuladas: List<String> = emptyList(),
) {
    val alarmesAtivos get() = alarmes.filter { it.ativo }
    val alarmesNaoReconhecidos get() = alarmes.count { it.ativo && !it.reconhecido }
    fun valvula(n: Int) = valvulas[n]
    fun tanque(n: Int) = tanques[n]
    fun comandoPendente(e: Equipamento) = comandos.any { it.equipamento == e && it.pendente }
}

/**
 * Núcleo do app, sem Android: recebe mensagens da [Conexao], mantém o [EstadoUI], gera alarmes,
 * envia comandos em paralelo com tempo limite e marca tudo como DESCONHECIDO quando a planta some.
 */
class PlantaStore(
    private val escopo: CoroutineScope,
    private val ajustes: Ajustes,
    private val fabricaConexao: (ConfigConexao) -> Conexao,
    private val agora: () -> Long = System::currentTimeMillis,
    private val maxHistorico: Int = 900,
) {
    private val _estado = MutableStateFlow(EstadoUI(config = ajustes.carregar()))
    val estado: StateFlow<EstadoUI> = _estado
    private val trava = Any()
    private var conexao: Conexao? = null
    private var desejaConectar = false
    private var tentativa = 0
    private var reconexao: Job? = null
    private var ticker: Job? = null
    private var proxAlarmeId = 1L
    private var geracaoConexao = 0
    private val ultimaMudancaNivel = HashMap<Int, Pair<Double?, Long>>()
    /** Um único thread de envio: nunca bloqueia a UI e mantém a ordem dos comandos no socket. */
    private val envio = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "envio-mqtt").apply { isDaemon = true } }.asCoroutineDispatcher()

    private inline fun mudar(f: (EstadoUI) -> EstadoUI) = synchronized(trava) { _estado.value = f(_estado.value) }

    // ---------- ciclo de vida ----------
    fun iniciar() {
        if (ticker == null) ticker = escopo.launch { while (isActive) { delay(250); tick() } }
        conectar()
    }

    fun salvarConfig(c: ConfigConexao) {
        ajustes.salvar(c)
        mudar { it.copy(config = c) }
        diag("configuração salva (${if (c.modoDemo) "modo demo" else "${c.host}:${c.porta}${if (c.tls) " TLS" else ""}"})")
        reconectar()
    }

    fun conectar() {
        val cfg = _estado.value.config
        cfg.valida()?.let { erro -> mudar { it.copy(conexao = EstadoConexao.DESCONECTADO, erroConexao = erro) }; return }
        desejaConectar = true
        reconexao?.cancel()
        val geracao = synchronized(trava) { ++geracaoConexao }
        conexao?.desconectar()
        val c = fabricaConexao(cfg)
        conexao = c
        mudar { it.copy(conexao = if (tentativa == 0) EstadoConexao.CONECTANDO else EstadoConexao.RECONECTANDO, erroConexao = null) }
        diag(if (cfg.modoDemo) "iniciando simulação demo" else "conectando a ${cfg.host}:${cfg.porta}")
        c.conectar(object : OuvinteConexao {
            override fun conectado() { if (geracao == geracaoConexao) aoConectar() }
            override fun desconectado(motivo: String?) { if (geracao == geracaoConexao) aoDesconectar(motivo) }
            override fun mensagem(topico: String, payload: String, retido: Boolean) { if (geracao == geracaoConexao) aoMensagem(topico, payload) }
        })
    }

    fun desconectar() {
        desejaConectar = false
        reconexao?.cancel()
        synchronized(trava) { geracaoConexao++ }
        conexao?.desconectar(); conexao = null
        mudar { it.copy(conexao = EstadoConexao.DESCONECTADO, erroConexao = null) }
        diag("desconectado pelo usuário")
        tick()
    }

    fun reconectar() { tentativa = 0; conectar() }

    private fun aoConectar() {
        tentativa = 0
        mudar { it.copy(conexao = EstadoConexao.CONECTADO, erroConexao = null) }
        diag("conectado")
    }

    private fun aoDesconectar(motivo: String?) {
        diag("conexão caiu: ${motivo ?: "sem motivo"}")
        mudar { it.copy(conexao = if (desejaConectar) EstadoConexao.RECONECTANDO else EstadoConexao.DESCONECTADO, erroConexao = motivo) }
        tick()
        if (desejaConectar) {
            val espera = (2000L shl tentativa.coerceAtMost(4)).coerceAtMost(30_000L)
            tentativa++
            reconexao?.cancel()
            reconexao = escopo.launch { delay(espera); if (desejaConectar) conectar() }
        }
    }

    // ---------- mensagens ----------
    internal fun aoMensagem(topico: String, payload: String) {
        val t = agora()
        when (topico) {
            Protocolo.HEARTBEAT -> Payloads.heartbeat(payload)?.let { hb ->
                mudar { it.copy(ultimoHeartbeat = t, modoSeguro = hb.modoSeguro, plantaViva = it.conexao == EstadoConexao.CONECTADO) }
                avaliarAlarmes(); return
            }
            Protocolo.STATUS -> { Payloads.statusOnline(payload)?.let { on -> if (!on) { diag("equipamento publicou offline (LWT)"); mudar { it.copy(ultimoHeartbeat = 0L, plantaViva = false) }; avaliarAlarmes() } }; return }
            Protocolo.SIM_FALHAS -> {
                val lista = runCatching { (Json.objeto(payload)["ativas"] as? List<*>)?.mapNotNull { f -> (f as? Map<*, *>)?.let { "${it["tipo"]}:${it["alvo"] ?: ""}" } } }.getOrNull() ?: emptyList()
                mudar { it.copy(falhasSimuladas = lista) }; return
            }
        }
        val (eq, sufixo) = Protocolo.interpretar(topico) ?: return
        when (sufixo) {
            "estado" -> when (eq.tipo) {
                TipoEquipamento.VALVULA -> Payloads.valvula(payload)?.let { (v, _) ->
                    mudar { it.copy(valvulas = it.valvulas + (eq.numero to v), ultimoDadoEm = t) }
                    concluirComandos(eq, v.chave); if (!v.emMovimento) limparSemResposta(eq)
                } ?: diag("payload inválido em $topico")
                TipoEquipamento.BOMBA -> Payloads.bomba(payload)?.let { (b, _) ->
                    mudar { it.copy(bomba = b, ultimoDadoEm = t) }
                    concluirComandos(eq, b.chave); limparSemResposta(eq)
                } ?: diag("payload inválido em $topico")
                else -> Unit
            }
            "nivel" -> Payloads.nivel(payload)?.let { n ->
                val tsMsg = if (n.ts in (t - 3_600_000)..(t + 5_000)) n.ts else t
                mudar { st ->
                    val hist = st.historico[eq.numero].orEmpty()
                    val novo = if (n.pct != null) (hist + Ponto(tsMsg, n.pct)).takeLast(maxHistorico) else hist
                    st.copy(tanques = st.tanques + (eq.numero to n), historico = st.historico + (eq.numero to novo), ultimoDadoEm = t)
                }
                val (ultimoPct, _) = ultimaMudancaNivel[eq.numero] ?: (null to 0L)
                if (ultimoPct != n.pct) ultimaMudancaNivel[eq.numero] = n.pct to t
            } ?: diag("payload inválido em $topico")
            "ack" -> Payloads.ack(payload)?.let { aoAck(eq, it) }
        }
        avaliarAlarmes()
    }

    // ---------- comandos ----------
    /** Envia o comando sem bloquear a UI; os envios saem em sequência (ordem garantida) e as respostas chegam em paralelo. */
    fun comandar(eq: Equipamento, cmd: String): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val t = agora()
        val reg = RegistroComando(id, eq, cmd, t)
        val st = _estado.value
        if (st.conexao != EstadoConexao.CONECTADO) {
            mudar { it.copy(comandos = (listOf(reg.copy(status = StatusComando.ERRO, detalhe = "sem conexão com o broker")) + it.comandos).take(50)) }
            diag("comando $cmd ${eq.id} não enviado: sem conexão"); return id
        }
        mudar { it.copy(comandos = (listOf(reg) + it.comandos).take(50)) }
        val c = conexao
        escopo.launch(envio) {
            try {
                c?.publicar(Protocolo.topicoCmd(eq), Protocolo.payloadCmd(cmd, id, t)) ?: error("sem conexão")
                diag("→ ${eq.id} $cmd ($id)")
            } catch (e: Exception) {
                atualizarComando(id) { it.copy(status = StatusComando.ERRO, detalhe = MqttCliente.mensagemErro(e)) }
                diag("erro ao enviar $cmd ${eq.id}: ${MqttCliente.mensagemErro(e)}")
            }
        }
        return id
    }

    fun simularFalha(tipo: String, alvo: String?, ativa: Boolean, duracaoS: Int = 10) {
        val c = conexao ?: return
        escopo.launch(envio) { runCatching { c.publicar(Protocolo.SIM_FALHA, Protocolo.payloadFalha(tipo, alvo, ativa, duracaoS)) } }
        diag("falha simulada $tipo ${alvo ?: ""} ${if (ativa) "ativada" else "desativada"}")
    }

    private fun atualizarComando(id: String, f: (RegistroComando) -> RegistroComando) =
        mudar { st -> st.copy(comandos = st.comandos.map { if (it.id == id) f(it) else it }) }

    private fun aoAck(eq: Equipamento, ack: Ack) {
        val id = ack.id ?: return
        val reg = _estado.value.comandos.firstOrNull { it.id == id } ?: return
        val ms = agora() - reg.enviadoEm
        if (ack.ok) {
            val jaFinal = ack.motivo == "ja_estava"
            atualizarComando(id) { it.copy(status = if (jaFinal) StatusComando.CONCLUIDO else StatusComando.ACEITO, ackMs = ms, concluidoMs = if (jaFinal) ms else null, detalhe = Payloads.motivoLegivel(ack.motivo)) }
            diag("← ack ok ${eq.id} ($id) em $ms ms")
        } else {
            atualizarComando(id) { it.copy(status = StatusComando.RECUSADO, ackMs = ms, detalhe = Payloads.motivoLegivel(ack.motivo)) }
            diag("← recusado ${eq.id} ($id): ${ack.motivo}")
        }
    }

    private fun concluirComandos(eq: Equipamento, estadoChave: String) {
        val alvo = mapOf("abrir" to "aberta", "fechar" to "fechada", "ligar" to "ligada", "desligar" to "desligada")
        val t = agora()
        mudar { st ->
            st.copy(comandos = st.comandos.map {
                if (it.equipamento == eq && it.pendente && alvo[it.cmd] == estadoChave)
                    it.copy(status = StatusComando.CONCLUIDO, concluidoMs = t - it.enviadoEm) else it
            })
        }
    }

    private fun verificarTimeouts() {
        val t = agora()
        val expirados = _estado.value.comandos.filter {
            (it.status == StatusComando.ENVIADO && t - it.enviadoEm > Protocolo.TIMEOUT_ACK_MS) ||
                (it.status == StatusComando.ACEITO && t - it.enviadoEm > Protocolo.TIMEOUT_ESTADO_FINAL_MS)
        }
        for (c in expirados) {
            val det = if (c.status == StatusComando.ENVIADO) "sem confirmação em ${Protocolo.TIMEOUT_ACK_MS / 1000.0} s" else "não chegou ao estado final em ${Protocolo.TIMEOUT_ESTADO_FINAL_MS / 1000} s"
            atualizarComando(c.id) { it.copy(status = StatusComando.SEM_RESPOSTA, detalhe = det) }
            diag("⚠ ${c.equipamento.id} ${c.cmd} (${c.id}): $det")
            // sem alarme falso: só alarma se nenhum comando mais novo do mesmo equipamento funcionou
            val maisNovoOk = _estado.value.comandos.any { it.equipamento == c.equipamento && it.enviadoEm > c.enviadoEm && (it.status == StatusComando.ACEITO || it.status == StatusComando.CONCLUIDO) }
            if (!maisNovoOk && _estado.value.plantaViva) {
                val tipo = if (c.equipamento.tipo == TipoEquipamento.BOMBA) TipoAlarme.BOMBA_SEM_RESPOSTA else TipoAlarme.VALVULA_SEM_RESPOSTA
                ativarAlarme(tipo, c.equipamento, "${c.equipamento.nome} não respondeu ao comando \"${c.cmd}\"")
            }
        }
    }

    private fun limparSemResposta(eq: Equipamento) {
        val tipo = if (eq.tipo == TipoEquipamento.BOMBA) TipoAlarme.BOMBA_SEM_RESPOSTA else TipoAlarme.VALVULA_SEM_RESPOSTA
        desativarAlarme(tipo, eq)
    }

    // ---------- alarmes ----------
    fun reconhecerAlarme(id: Long) = mudar { st -> st.copy(alarmes = st.alarmes.map { if (it.id == id) it.copy(reconhecido = true) else it }) }
    fun reconhecerTodos() = mudar { st -> st.copy(alarmes = st.alarmes.map { it.copy(reconhecido = true) }) }
    fun limparHistoricoAlarmes() = mudar { st -> st.copy(alarmes = st.alarmes.filter { it.ativo }) }

    private fun ativarAlarme(tipo: TipoAlarme, eq: Equipamento?, msg: String) {
        synchronized(trava) {
            val st = _estado.value
            if (st.alarmes.any { it.ativo && it.tipo == tipo && it.equipamento == eq }) return
            val a = Alarme(proxAlarmeId++, tipo, eq, msg, agora())
            _estado.value = st.copy(alarmes = (listOf(a) + st.alarmes).take(200))
        }
        diag("ALARME ${tipo.titulo}${eq?.let { " (${it.nome})" } ?: ""}")
    }

    private fun desativarAlarme(tipo: TipoAlarme, eq: Equipamento?) {
        var houve = false
        mudar { st ->
            st.copy(alarmes = st.alarmes.map {
                if (it.ativo && it.tipo == tipo && it.equipamento == eq) { houve = true; it.copy(fim = agora()) } else it
            })
        }
        if (houve) diag("normalizado: ${tipo.titulo}${eq?.let { " (${it.nome})" } ?: ""}")
    }

    private fun avaliarAlarmes() {
        val st = _estado.value
        val t = agora()
        // conexão
        if (desejaConectar && !st.plantaViva) ativarAlarme(TipoAlarme.CONEXAO_PERDIDA, null,
            if (st.conexao == EstadoConexao.CONECTADO) "Conectado ao broker, mas o equipamento não envia sinal de vida" else "Sem conexão com o broker${st.erroConexao?.let { ": $it" } ?: ""}")
        else desativarAlarme(TipoAlarme.CONEXAO_PERDIDA, null)
        if (st.plantaViva && st.modoSeguro) ativarAlarme(TipoAlarme.MODO_SEGURO, null, "Válvulas fechadas e bomba desligada pelo próprio equipamento")
        else if (!st.modoSeguro || !st.plantaViva) desativarAlarme(TipoAlarme.MODO_SEGURO, null)
        for (n in 1..2) {
            val eq = Equipamento(TipoEquipamento.TANQUE, n)
            val nivel = if (st.plantaViva) st.tanques[n] else null
            val pct = nivel?.pct
            val valido = nivel != null && nivel.qualidade != QualidadeSensor.INVALIDA && pct != null
            if (nivel != null && nivel.qualidade == QualidadeSensor.INVALIDA) ativarAlarme(TipoAlarme.SENSOR_INVALIDO, eq, "${eq.nome}: sensor sem leitura")
            else desativarAlarme(TipoAlarme.SENSOR_INVALIDO, eq)
            if (valido) {
                val altoAtivo = st.alarmes.any { it.ativo && it.tipo == TipoAlarme.NIVEL_ALTO && it.equipamento == eq }
                if (pct!! >= Protocolo.NIVEL_ALTO_PCT) ativarAlarme(TipoAlarme.NIVEL_ALTO, eq, "${eq.nome} em ${fmt(pct)} % (limite ${fmt(Protocolo.NIVEL_ALTO_PCT)} %)")
                else if (altoAtivo && pct < Protocolo.NIVEL_ALTO_PCT - Protocolo.HISTERESE_PCT) desativarAlarme(TipoAlarme.NIVEL_ALTO, eq)
                val baixoAtivo = st.alarmes.any { it.ativo && it.tipo == TipoAlarme.NIVEL_BAIXO && it.equipamento == eq }
                if (pct <= Protocolo.NIVEL_BAIXO_PCT) ativarAlarme(TipoAlarme.NIVEL_BAIXO, eq, "${eq.nome} em ${fmt(pct)} % (limite ${fmt(Protocolo.NIVEL_BAIXO_PCT)} %)")
                else if (baixoAtivo && pct > Protocolo.NIVEL_BAIXO_PCT + Protocolo.HISTERESE_PCT) desativarAlarme(TipoAlarme.NIVEL_BAIXO, eq)
                // sensor travado: valor idêntico por 20 s enquanto há fluxo esperado
                val fluxo = when (n) {
                    1 -> st.bomba == EstadoBomba.LIGADA || st.valvulas[3] == EstadoValvula.ABERTA
                    else -> st.bomba == EstadoBomba.LIGADA || st.valvulas[2] == EstadoValvula.ABERTA
                }
                val (_, desde) = ultimaMudancaNivel[n] ?: (null to t)
                val suspeito = nivel!!.qualidade == QualidadeSensor.SUSPEITA || (fluxo && t - desde > 20_000 && pct in 0.5..99.5)
                if (suspeito) ativarAlarme(TipoAlarme.SENSOR_SUSPEITO, eq, "${eq.nome}: leitura parada em ${fmt(pct)} % apesar do fluxo")
                else desativarAlarme(TipoAlarme.SENSOR_SUSPEITO, eq)
            } else {
                // sem dado confiável: nada de alarme de nível (nem mantém o antigo como se fosse atual)
                desativarAlarme(TipoAlarme.NIVEL_ALTO, eq); desativarAlarme(TipoAlarme.NIVEL_BAIXO, eq); desativarAlarme(TipoAlarme.SENSOR_SUSPEITO, eq)
            }
        }
    }

    // ---------- relógio ----------
    internal fun tick() {
        val t = agora()
        val st = _estado.value
        val viva = st.conexao == EstadoConexao.CONECTADO && st.ultimoHeartbeat > 0 && t - st.ultimoHeartbeat <= Protocolo.TIMEOUT_HEARTBEAT_MS
        if (viva != st.plantaViva) {
            if (!viva) diag("planta sem sinal de vida: estados marcados como DESCONHECIDOS")
            mudar { it.copy(plantaViva = viva) }
        }
        verificarTimeouts()
        avaliarAlarmes()
    }

    /** Estado "visível": quando a planta não está viva, tudo é desconhecido. */
    fun estadoVisivel(st: EstadoUI = _estado.value): EstadoUI =
        if (st.plantaViva) st else st.copy(valvulas = st.valvulas.mapValues { null }, bomba = null, tanques = st.tanques.mapValues { null })

    // ---------- diagnóstico ----------
    private fun diag(texto: String) = mudar { it.copy(diagnostico = (listOf(RegistroDiag(agora(), texto)) + it.diagnostico).take(150)) }

    fun relatorioDiagnostico(): String {
        val st = _estado.value
        val sb = StringBuilder("SuperviLab, diagnóstico\n")
        sb.append("Conexão: ${st.conexao.rotulo}${st.erroConexao?.let { " ($it)" } ?: ""}; planta viva: ${st.plantaViva}; modo seguro: ${st.modoSeguro}\n")
        sb.append("Broker: ${if (st.config.modoDemo) "DEMO" else "${st.config.host}:${st.config.porta} tls=${st.config.tls}"}\n")
        sb.append("Alarmes ativos: ${st.alarmesAtivos.joinToString { it.tipo.titulo }}\n\nÚltimos eventos:\n")
        st.diagnostico.take(60).forEach { sb.append(hora(it.ts)).append(' ').append(it.texto).append('\n') }
        return sb.toString()
    }

    companion object {
        fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f", v)
        fun hora(ts: Long): String {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            return String.format("%02d:%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
        }
    }
}
