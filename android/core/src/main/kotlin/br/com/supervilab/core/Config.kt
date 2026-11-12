package br.com.supervilab.core

/** Configuração de conexão. Senha e CA ficam só nas preferências do usuário, nunca no código. */
data class ConfigConexao(
    val host: String = "",
    val porta: Int = 8883,
    val usuario: String = "",
    val senha: String = "",
    val tls: Boolean = true,
    /** Certificado(s) da CA em PEM, para brokers com certificado próprio. Vazio = CAs do sistema. */
    val caPem: String = "",
    val verificarNomeHost: Boolean = true,
    val clienteId: String = "supervilab-app",
    val keepAliveS: Int = 10,
    val modoDemo: Boolean = true,
) {
    fun valida(): String? = when {
        modoDemo -> null
        host.isBlank() -> "Informe o endereço do broker"
        porta !in 1..65535 -> "Porta inválida"
        usuario.isBlank() -> "Informe o usuário"
        senha.isBlank() -> "Informe a senha"
        else -> null
    }
}

interface Ajustes {
    fun carregar(): ConfigConexao
    fun salvar(c: ConfigConexao)
}

class AjustesMemoria(private var cfg: ConfigConexao = ConfigConexao()) : Ajustes {
    override fun carregar() = cfg
    override fun salvar(c: ConfigConexao) { cfg = c }
}
