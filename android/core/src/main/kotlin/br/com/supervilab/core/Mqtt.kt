package br.com.supervilab.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Cliente MQTT 3.1.1 mínimo e sem dependências (CONNECT, PUBLISH QoS 0/1, SUBSCRIBE, PING, DISCONNECT),
 * com TLS (CAs do sistema ou CA própria em PEM) e usuário/senha.
 * Chame [conectar] fora da thread principal. Callbacks chegam na thread de leitura.
 */
class MqttCliente(private val cfg: ConfigConexao, private val ouvinte: Ouvinte) {
    interface Ouvinte {
        fun aoConectar()
        fun aoDesconectar(motivo: String?)
        fun aoReceber(topico: String, payload: ByteArray, retido: Boolean)
    }

    class ErroMqtt(msg: String) : IOException(msg)

    @Volatile private var socket: Socket? = null
    private var entrada: DataInputStream? = null
    private var saida: OutputStream? = null
    private val travaSaida = Any()
    private val proximoId = AtomicInteger(1)
    @Volatile private var ativo = false
    @Volatile private var ultimoRecebido = 0L
    @Volatile private var encerrouPorNos = false

    val conectado: Boolean get() = ativo

    @Throws(IOException::class)
    fun conectar(timeoutMs: Int = 8_000) {
        val s = if (cfg.tls) criarSocketTls(timeoutMs) else Socket().also {
            it.connect(InetSocketAddress(cfg.host, cfg.porta), timeoutMs)
        }
        s.tcpNoDelay = true
        s.soTimeout = timeoutMs
        socket = s
        entrada = DataInputStream(s.getInputStream().buffered())
        saida = s.getOutputStream().buffered()
        enviar(pacoteConnect())
        val (tipo, corpo) = lerPacote() ?: throw ErroMqtt("broker fechou a conexão")
        if (tipo != 2 || corpo.size < 2) throw ErroMqtt("resposta inesperada do broker")
        when (val rc = corpo[1].toInt() and 0xFF) {
            0 -> Unit
            1 -> throw ErroMqtt("broker não aceita MQTT 3.1.1")
            2 -> throw ErroMqtt("identificador de cliente recusado")
            3 -> throw ErroMqtt("serviço MQTT indisponível")
            4 -> throw ErroMqtt("usuário ou senha incorretos")
            5 -> throw ErroMqtt("não autorizado (verifique usuário e senha)")
            else -> throw ErroMqtt("CONNACK código $rc")
        }
        s.soTimeout = 0
        ativo = true
        ultimoRecebido = System.currentTimeMillis()
        Thread(::lacoLeitura, "mqtt-leitura").apply { isDaemon = true }.start()
        Thread(::lacoKeepAlive, "mqtt-keepalive").apply { isDaemon = true }.start()
        ouvinte.aoConectar()
    }

    fun assinar(vararg filtros: String) {
        val id = novoId()
        val vh = ByteArrayOutputStream()
        vh.write(id shr 8); vh.write(id and 0xFF)
        for (f in filtros) { escreverString(vh, f); vh.write(1) }
        enviar(pacote(8, 0x02, vh.toByteArray()))
    }

    fun publicar(topico: String, payload: ByteArray, qos: Int = 1, retain: Boolean = false) {
        if (!ativo) throw ErroMqtt("não conectado")
        val vh = ByteArrayOutputStream()
        escreverString(vh, topico)
        if (qos > 0) { val id = novoId(); vh.write(id shr 8); vh.write(id and 0xFF) }
        vh.write(payload)
        enviar(pacote(3, (qos shl 1) or (if (retain) 1 else 0), vh.toByteArray()))
    }

    fun desconectar() {
        encerrouPorNos = true
        runCatching { if (ativo) enviar(byteArrayOf(0xE0.toByte(), 0)) }
        fechar(null)
    }

    // ---------- internos ----------
    private fun novoId(): Int { val v = proximoId.getAndIncrement() and 0xFFFF; return if (v == 0) novoId() else v }

    private fun criarSocketTls(timeoutMs: Int): Socket {
        val ctx = SSLContext.getInstance("TLS")
        if (cfg.caPem.isBlank()) ctx.init(null, null, null) else {
            val cf = CertificateFactory.getInstance("X.509")
            val certs = cf.generateCertificates(cfg.caPem.byteInputStream())
            if (certs.isEmpty()) throw ErroMqtt("certificado CA inválido")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            certs.forEachIndexed { i, c -> ks.setCertificateEntry("ca$i", c) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            ctx.init(null, tmf.trustManagers, null)
        }
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(cfg.host, cfg.porta), timeoutMs)
        if (cfg.verificarNomeHost) {
            val p = s.sslParameters; p.endpointIdentificationAlgorithm = "HTTPS"; s.sslParameters = p
        }
        s.soTimeout = timeoutMs
        s.startHandshake()
        return s
    }

    private fun pacoteConnect(): ByteArray {
        val vh = ByteArrayOutputStream()
        escreverString(vh, "MQTT"); vh.write(4)
        var flags = 0x02 // clean session
        if (cfg.usuario.isNotEmpty()) flags = flags or 0x80
        if (cfg.senha.isNotEmpty()) flags = flags or 0x40
        vh.write(flags)
        vh.write(cfg.keepAliveS shr 8); vh.write(cfg.keepAliveS and 0xFF)
        escreverString(vh, cfg.clienteId.ifBlank { "supervilab-${System.currentTimeMillis() % 100000}" })
        if (cfg.usuario.isNotEmpty()) escreverString(vh, cfg.usuario)
        if (cfg.senha.isNotEmpty()) escreverString(vh, cfg.senha)
        return pacote(1, 0, vh.toByteArray())
    }

    private fun pacote(tipo: Int, flags: Int, corpo: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(corpo.size + 5)
        out.write((tipo shl 4) or flags)
        var n = corpo.size
        do { var b = n % 128; n /= 128; if (n > 0) b = b or 0x80; out.write(b) } while (n > 0)
        out.write(corpo)
        return out.toByteArray()
    }

    private fun escreverString(o: OutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.write(b.size shr 8); o.write(b.size and 0xFF); o.write(b)
    }

    private fun enviar(b: ByteArray) {
        val o = saida ?: throw ErroMqtt("não conectado")
        synchronized(travaSaida) { o.write(b); o.flush() }
    }

    /** Lê um pacote completo: (tipo, flags) no primeiro byte e o corpo. Retorna null em EOF. */
    private fun lerPacote(): Pair<Int, ByteArray>? {
        val e = entrada ?: return null
        val b0 = try { e.readUnsignedByte() } catch (_: EOFException) { return null }
        var mult = 1; var tam = 0; var i = 0
        do {
            val b = e.readUnsignedByte(); tam += (b and 0x7F) * mult; mult *= 128
            if (++i > 4) throw ErroMqtt("tamanho de pacote inválido")
        } while (b and 0x80 != 0)
        val corpo = ByteArray(tam); e.readFully(corpo)
        ultimoRecebido = System.currentTimeMillis()
        return (b0 shr 4) to corpo.also { flagsUltimo = b0 and 0x0F }
    }
    private var flagsUltimo = 0

    private fun lacoLeitura() {
        try {
            while (ativo) {
                val (tipo, corpo) = lerPacote() ?: throw ErroMqtt("broker fechou a conexão")
                when (tipo) {
                    3 -> tratarPublish(flagsUltimo, corpo)
                    4, 9, 13 -> Unit // PUBACK, SUBACK, PINGRESP
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (!encerrouPorNos) fechar(mensagemErro(e))
        }
    }

    private fun tratarPublish(flags: Int, corpo: ByteArray) {
        val qos = (flags shr 1) and 0x03
        val retido = flags and 0x01 != 0
        val tamTopico = ((corpo[0].toInt() and 0xFF) shl 8) or (corpo[1].toInt() and 0xFF)
        val topico = String(corpo, 2, tamTopico, Charsets.UTF_8)
        var pos = 2 + tamTopico
        if (qos > 0) {
            val id = ((corpo[pos].toInt() and 0xFF) shl 8) or (corpo[pos + 1].toInt() and 0xFF)
            pos += 2
            if (qos == 1) runCatching { enviar(byteArrayOf(0x40, 2, (id shr 8).toByte(), (id and 0xFF).toByte())) }
        }
        ouvinte.aoReceber(topico, corpo.copyOfRange(pos, corpo.size), retido)
    }

    private fun lacoKeepAlive() {
        val ka = cfg.keepAliveS.coerceAtLeast(2) * 1000L
        while (ativo) {
            try { Thread.sleep(ka / 2) } catch (_: InterruptedException) { return }
            if (!ativo) return
            if (System.currentTimeMillis() - ultimoRecebido > ka * 3 / 2) {
                fechar("sem resposta do broker (keep-alive)"); return
            }
            runCatching { enviar(byteArrayOf(0xC0.toByte(), 0)) }.onFailure { fechar(mensagemErro(it)); return }
        }
    }

    private fun fechar(motivo: String?) {
        val estava = ativo
        ativo = false
        runCatching { socket?.close() }
        socket = null
        if (estava) ouvinte.aoDesconectar(motivo)
    }

    companion object {
        fun mensagemErro(e: Throwable): String = when {
            e is ErroMqtt -> e.message ?: "erro MQTT"
            e is java.net.UnknownHostException -> "endereço não encontrado"
            e is java.net.ConnectException -> "conexão recusada (broker fora do ar ou porta errada)"
            e is java.net.SocketTimeoutException -> "tempo esgotado ao conectar"
            e is javax.net.ssl.SSLHandshakeException -> "certificado TLS não confiável (importe a CA do broker)"
            e is javax.net.ssl.SSLException -> "falha TLS: ${e.message}"
            e is java.net.SocketException -> "conexão perdida"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

/** Utilitário usado nos testes: leitura bruta de um InputStream até EOF. */
internal fun InputStream.lerTudo(): ByteArray = readBytes()
