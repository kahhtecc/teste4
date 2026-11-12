package br.com.supervilab.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Simulação da planta em Kotlin (mesma física do simulador Python), usada pelo modo demo.
 * Fala o mesmo protocolo pelo canal [Conexao], então o resto do app não sabe que é simulação.
 */
class ConexaoDemo(
    private val agora: () -> Long = System::currentTimeMillis,
    private val passoMs: Long = 100,
    private val comHistorico: Boolean = true,
) : Conexao {
    private class Valvula(var estado: EstadoValvula = EstadoValvula.FECHADA) {
        var alvo = estado; var restanteS = 0.0; var semResposta = false
        val aberta get() = estado == EstadoValvula.ABERTA
        fun comandar(cmd: String): Pair<Boolean, String> {
            if (semResposta) return false to "sem_resposta"
            val novo = when (cmd) { "abrir" -> EstadoValvula.ABERTA; "fechar" -> EstadoValvula.FECHADA; else -> return false to "cmd_invalido" }
            if (estado == novo && alvo == novo) return true to "ja_estava"
            alvo = novo; estado = if (novo == EstadoValvula.ABERTA) EstadoValvula.ABRINDO else EstadoValvula.FECHANDO
            restanteS = CURSO_S
            return true to ""
        }
        fun avancar(dt: Double): Boolean {
            if (estado.emMovimento) { restanteS -= dt; if (restanteS <= 0) { estado = alvo; return true } }
            return false
        }
    }
    private class Tanque(var volumeL: Double) {
        var travado = false; var invalido = false; var leituraTravada: Double? = null
        val pct get() = 100.0 * volumeL / CAPACIDADE_L
        fun leitura(): Map<String, Any?> {
            if (invalido) return mapOf("nivel_pct" to null, "volume_l" to null, "qualidade" to "invalida")
            val v = if (travado) (leituraTravada ?: volumeL.also { leituraTravada = it }) else volumeL.also { leituraTravada = null }
            return mapOf("nivel_pct" to arred(100.0 * v / CAPACIDADE_L), "volume_l" to arred(v), "qualidade" to "boa")
        }
    }

    private val valvulas = mapOf(1 to Valvula(EstadoValvula.ABERTA), 2 to Valvula(), 3 to Valvula())
    private val tanques = mapOf(1 to Tanque(640.0), 2 to Tanque(260.0))
    private var bomba = EstadoBomba.LIGADA
    private var modoSeguro = false
    private val falhas = LinkedHashMap<String, Map<String, Any?>>()
    @Volatile private var ouvinte: OuvinteConexao? = null
    @Volatile private var rodando = false
    @Volatile private var caidoAte = 0L
    private var seq = 0L
    private val inicio = agora()
    private val trava = Any()
    private var thread: Thread? = null

    override fun conectar(ouvinte: OuvinteConexao) {
        this.ouvinte = ouvinte
        rodando = true
        thread = Thread(::laco, "demo-planta").apply { isDaemon = true; start() }
        Thread({
            Thread.sleep(300) // "handshake" para a UI mostrar Conectando…
            ouvinte.conectado()
            synchronized(trava) {
                if (comHistorico) publicarHistorico()
                publicarTudo()
            }
        }, "demo-conectar").apply { isDaemon = true }.start()
    }

    override fun desconectar() { rodando = false; ouvinte = null }

    override fun publicar(topico: String, payload: String) {
        if (caidoAte > agora()) return // em queda simulada, nada passa
        val m = runCatching { Json.objeto(payload) }.getOrNull()
        if (topico == Protocolo.SIM_FALHA) { if (m != null) aplicarFalha(m); return }
        val (eq, sufixo) = Protocolo.interpretar(topico) ?: return
        if (sufixo != "cmd") return
        if (m == null) { ack(eq, null, false, "json_invalido"); return }
        val cmd = m.str("cmd") ?: ""; val id = m.str("id")
        synchronized(trava) {
            when (eq.tipo) {
                TipoEquipamento.VALVULA -> {
                    val v = valvulas[eq.numero] ?: return ack(eq, id, false, "equipamento_desconhecido")
                    if (modoSeguro) return ack(eq, id, false, "modo_seguro")
                    val (ok, motivo) = v.comandar(cmd)
                    if (motivo == "sem_resposta") return
                    ack(eq, id, ok, motivo); if (ok) pubValvula(eq.numero)
                }
                TipoEquipamento.BOMBA -> {
                    if (modoSeguro) return ack(eq, id, false, "modo_seguro")
                    val (ok, motivo) = when (cmd) {
                        "ligar" -> when {
                            !valvulas[1]!!.aberta -> false to "valvula_1_fechada"
                            tanques[1]!!.pct < NIVEL_MIN_BOMBA -> false to "tanque_1_vazio"
                            else -> { bomba = EstadoBomba.LIGADA; true to "" }
                        }
                        "desligar" -> { bomba = EstadoBomba.DESLIGADA; true to "" }
                        else -> false to "cmd_invalido"
                    }
                    ack(eq, id, ok, motivo); if (ok) pubBomba()
                }
                TipoEquipamento.TANQUE -> ack(eq, id, false, "equipamento_desconhecido")
            }
        }
    }

    private fun aplicarFalha(m: Map<String, Any?>) {
        val tipo = m.str("tipo"); val alvo = m.str("alvo"); val ativa = m.bool("ativa") ?: true
        val n = alvo?.substringAfter('/')?.toIntOrNull()
        synchronized(trava) {
            when (tipo) {
                "sensor_travado" -> tanques[n]?.travado = ativa
                "sensor_invalido" -> { tanques[n]?.invalido = ativa; if (!ativa) sairModoSeguro() }
                "valvula_sem_resposta" -> valvulas[n]?.semResposta = ativa
                "perda_conexao" -> if (ativa) {
                    val dur = (m.num("duracao_s") ?: 10.0) * 1000
                    caidoAte = agora() + dur.toLong()
                    falhas["$tipo:$alvo"] = mapOf("tipo" to tipo, "alvo" to alvo); pubFalhas()
                    ouvinte?.desconectado("queda de rede simulada")
                    return
                }
                else -> return
            }
            if (ativa) falhas["$tipo:$alvo"] = mapOf("tipo" to tipo, "alvo" to alvo) else falhas.remove("$tipo:$alvo")
            pubFalhas()
        }
    }

    private fun laco() {
        var proxNivel = agora(); var proxHb = agora()
        val dt = passoMs / 1000.0
        while (rodando) {
            val t0 = agora()
            synchronized(trava) {
                if (caidoAte != 0L && t0 >= caidoAte) {
                    caidoAte = 0L; falhas.remove("perda_conexao:null"); falhas.remove("perda_conexao:")
                    sairModoSeguro(); ouvinte?.conectado(); publicarTudo()
                }
                for ((n, v) in valvulas) if (v.avancar(dt)) pubValvula(n)
                val t1 = tanques[1]!!; val t2 = tanques[2]!!
                if (valvulas[3]!!.aberta) t1.volumeL += VAZAO_REDE / 60 * dt
                if (bomba == EstadoBomba.LIGADA) {
                    if (!valvulas[1]!!.aberta || t1.pct < NIVEL_MIN_BOMBA) { bomba = EstadoBomba.DESLIGADA; pubBomba() }
                    else { val q = min(VAZAO_BOMBA / 60 * dt, t1.volumeL); t1.volumeL -= q; t2.volumeL += q }
                }
                if (valvulas[2]!!.aberta) t2.volumeL -= min(VAZAO_DRENO / 60 * dt, t2.volumeL)
                for (t in tanques.values) t.volumeL = max(0.0, min(CAPACIDADE_L, t.volumeL))
                if (tanques.values.any { it.invalido } && !modoSeguro) entrarModoSeguro()
                if (t0 >= proxNivel) { pubNiveis(); proxNivel = t0 + 1000 }
                if (t0 >= proxHb) { pubHeartbeat(); proxHb = t0 + Protocolo.PERIODO_HEARTBEAT_MS }
            }
            val gasto = agora() - t0
            try { Thread.sleep(max(1, passoMs - gasto)) } catch (_: InterruptedException) { return }
        }
    }

    private fun entrarModoSeguro() {
        modoSeguro = true; bomba = EstadoBomba.DESLIGADA; pubBomba()
        for ((n, v) in valvulas) { val sr = v.semResposta; v.semResposta = false; v.comandar("fechar"); v.semResposta = sr; pubValvula(n) }
    }
    private fun sairModoSeguro() { modoSeguro = false }

    // ---------- publicação ----------
    private fun emitir(topico: String, payload: Map<String, Any?>, retido: Boolean = false) {
        if (caidoAte > agora()) return
        ouvinte?.mensagem(topico, Json.stringify(payload), retido)
    }
    private fun ack(eq: Equipamento, id: String?, ok: Boolean, motivo: String) =
        emitir(Protocolo.topicoAck(eq), mapOf("id" to id, "ok" to ok, "motivo" to motivo, "ts" to agora()))
    private fun pubValvula(n: Int) = emitir("planta/valvula/$n/estado", mapOf("estado" to valvulas[n]!!.estado.chave, "ts" to agora()), true)
    private fun pubBomba() = emitir("planta/bomba/1/estado", mapOf("estado" to bomba.chave, "ts" to agora()), true)
    private fun pubNiveis() = tanques.forEach { (n, t) -> emitir("planta/tanque/$n/nivel", t.leitura() + ("ts" to agora()), true) }
    private fun pubHeartbeat() = emitir(Protocolo.HEARTBEAT, mapOf("ts" to agora(), "uptime_s" to (agora() - inicio) / 1000,
        "modo" to if (modoSeguro) "seguro" else "normal", "seq" to ++seq))
    private fun pubFalhas() = emitir(Protocolo.SIM_FALHAS, mapOf("ativas" to falhas.values.toList(), "ts" to agora()), true)
    private fun publicarTudo() {
        emitir(Protocolo.STATUS, mapOf("online" to true, "ts" to agora(), "fw" to "demo-1.0"), true)
        valvulas.keys.forEach(::pubValvula); pubBomba(); pubNiveis(); pubFalhas(); pubHeartbeat()
    }

    /** Dez minutos de histórico sintético para o gráfico não nascer vazio. */
    private fun publicarHistorico() {
        val t = agora()
        for (i in 600 downTo 1) {
            val ts = t - i * 1000L
            val n1 = 64.0 + 6 * sin(i / 90.0) + (i / 600.0) * 8
            val n2 = 26.0 - 5 * sin(i / 70.0) - (i / 600.0) * 9
            emitir("planta/tanque/1/nivel", mapOf("nivel_pct" to arred(n1), "volume_l" to arred(n1 * 10), "qualidade" to "boa", "ts" to ts))
            emitir("planta/tanque/2/nivel", mapOf("nivel_pct" to arred(n2), "volume_l" to arred(n2 * 10), "qualidade" to "boa", "ts" to ts))
        }
    }

    companion object {
        const val CAPACIDADE_L = 1000.0
        const val VAZAO_BOMBA = 60.0
        const val VAZAO_REDE = 30.0
        const val VAZAO_DRENO = 40.0
        const val CURSO_S = 3.0
        const val NIVEL_MIN_BOMBA = 2.0
        private fun arred(v: Double) = Math.round(v * 10) / 10.0
        @Suppress("unused") private fun quase(a: Double, b: Double) = abs(a - b) < 1e-6
    }
}
