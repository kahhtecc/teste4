package br.com.supervilab.core

/** Canal com a planta: MQTT real ou simulação local (demo). Callbacks podem vir de qualquer thread. */
interface Conexao {
    fun conectar(ouvinte: OuvinteConexao)
    /** Publica sem bloquear por muito tempo; erros vão para [OuvinteConexao.desconectado]. */
    fun publicar(topico: String, payload: String)
    fun desconectar()
}

interface OuvinteConexao {
    fun conectado()
    fun desconectado(motivo: String?)
    fun mensagem(topico: String, payload: String, retido: Boolean)
}

/** Conexão MQTT real, usando o cliente próprio [MqttCliente]. */
class ConexaoMqtt(private val cfg: ConfigConexao) : Conexao {
    @Volatile private var cliente: MqttCliente? = null

    override fun conectar(ouvinte: OuvinteConexao) {
        val c = MqttCliente(cfg, object : MqttCliente.Ouvinte {
            override fun aoConectar() {
                cliente?.assinar(Protocolo.ASSINATURA_TUDO)
                ouvinte.conectado()
            }
            override fun aoDesconectar(motivo: String?) = ouvinte.desconectado(motivo)
            override fun aoReceber(topico: String, payload: ByteArray, retido: Boolean) =
                ouvinte.mensagem(topico, String(payload, Charsets.UTF_8), retido)
        })
        cliente = c
        Thread({
            try { c.conectar() } catch (e: Exception) { ouvinte.desconectado(MqttCliente.mensagemErro(e)) }
        }, "mqtt-conectar").apply { isDaemon = true }.start()
    }

    override fun publicar(topico: String, payload: String) {
        cliente?.publicar(topico, payload.toByteArray(Charsets.UTF_8), qos = 1)
    }

    override fun desconectar() { cliente?.desconectar(); cliente = null }
}
