import br.com.supervilab.core.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Testes do cliente MQTT próprio contra o broker Python (amqtt): porta sem TLS, porta TLS e simulador rodando. */
fun testesMqtt(portaPlain: Int, portaTls: Int, portaSemAuth: Int, caPath: String) {
    val ca = File(caPath).readText()
    val cfgBase = ConfigConexao(host = "127.0.0.1", porta = portaPlain, usuario = "supervilab", senha = "segredo123", tls = false, modoDemo = false, clienteId = "teste-jvm")

    class Colector : MqttCliente.Ouvinte {
        val msgs = CopyOnWriteArrayList<Triple<String, String, Boolean>>(); @Volatile var desconectou: String? = "nunca"; @Volatile var conectou = false
        override fun aoConectar() { conectou = true }
        override fun aoDesconectar(motivo: String?) { desconectou = motivo }
        override fun aoReceber(topico: String, payload: ByteArray, retido: Boolean) { msgs += Triple(topico, String(payload), retido) }
    }

    teste("mqtt: conecta com usuário/senha, assina e recebe retidos do simulador") {
        val col = Colector(); val c = MqttCliente(cfgBase, col)
        c.conectar(); verifica(col.conectou)
        c.assinar("planta/#")
        verifica(esperarAte(3000) { col.msgs.any { it.first == "planta/valvula/1/estado" && it.third } }, "retido da válvula 1")
        verifica(esperarAte(3000) { col.msgs.any { it.first == "planta/heartbeat" } }, "heartbeat ao vivo")
        c.desconectar(); Thread.sleep(200)
    }
    teste("mqtt: senha errada é recusada com mensagem clara") {
        val e = runCatching { MqttCliente(cfgBase.copy(senha = "errada"), Colector()).conectar() }.exceptionOrNull()
        verifica(e != null, "deveria falhar"); val m = MqttCliente.mensagemErro(e!!); verifica(m == "usuário ou senha incorretos" || m.startsWith("não autorizado"), m); println("     erro: $m")
    }
    teste("mqtt: porta fechada dá erro legível e não trava") {
        val t0 = System.currentTimeMillis()
        val e = runCatching { MqttCliente(cfgBase.copy(porta = 1), Colector()).conectar(2000) }.exceptionOrNull()
        verifica(e != null); verifica(System.currentTimeMillis() - t0 < 3000); println("     erro: ${MqttCliente.mensagemErro(e!!)}")
    }
    teste("mqtt: TLS com CA própria funciona; sem CA é recusado; nome de host errado é recusado") {
        val col = Colector(); val c = MqttCliente(cfgBase.copy(porta = portaTls, tls = true, caPem = ca), col)
        c.conectar(); verifica(col.conectou); c.assinar("planta/heartbeat")
        verifica(esperarAte(3000) { col.msgs.isNotEmpty() }); c.desconectar()
        val semCa = runCatching { MqttCliente(cfgBase.copy(porta = portaTls, tls = true, caPem = ""), Colector()).conectar() }.exceptionOrNull()
        verifica(semCa != null, "sem CA deveria falhar"); println("     sem CA: ${MqttCliente.mensagemErro(semCa!!)}")
        val lixo = runCatching { MqttCliente(cfgBase.copy(porta = portaTls, tls = true, caPem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"), Colector()).conectar() }.exceptionOrNull()
        verifica(lixo != null, "CA inválida deveria falhar")
        val plainEmTls = runCatching { MqttCliente(cfgBase.copy(porta = portaTls, tls = false), Colector()).conectar(2000) }.exceptionOrNull()
        verifica(plainEmTls != null, "sem TLS na porta TLS deveria falhar")
    }
    teste("mqtt: store + cliente real: 20 comandos ao simulador com ack < 400 ms e ordem preservada") {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = PlantaStore(escopo, AjustesMemoria(cfgBase), { ConexaoMqtt(it) })
        store.iniciar()
        verifica(esperarAte(5000) { store.estado.value.plantaViva }, "planta viva via MQTT real")
        val t0 = System.currentTimeMillis()
        val ids = (1..20).map { store.comandar(Equipamento.VALVULAS[it % 3], if (it % 2 == 0) "abrir" else "fechar") }
        verifica(esperarAte(3000) { ids.all { id -> store.estado.value.comandos.first { it.id == id }.ackMs != null } }, "todos com ack")
        val total = System.currentTimeMillis() - t0
        val acks = ids.map { id -> store.estado.value.comandos.first { it.id == id }.ackMs!! }
        println("     20 comandos: total $total ms, ack máx ${acks.max()} ms, médio ${acks.average().toInt()} ms")
        verifica(total < 400, "20 comandos levaram $total ms"); verifica(acks.max() < 400)
        verifica(esperarAte(6000) { store.estado.value.valvulas[1] == EstadoValvula.ABERTA && store.estado.value.valvulas[2] == EstadoValvula.FECHADA && store.estado.value.valvulas[3] == EstadoValvula.ABERTA }, "estado final na ordem dos comandos: ${store.estado.value.valvulas}")
        // falha simulada pelo app: válvula 1 sem resposta
        store.simularFalha("valvula_sem_resposta", "valvula/1", true)
        verifica(esperarAte(2000) { store.estado.value.falhasSimuladas.isNotEmpty() })
        store.comandar(Equipamento.V1, "abrir")
        verifica(esperarAte(3000) { ativo(store.estado.value, TipoAlarme.VALVULA_SEM_RESPOSTA, Equipamento.V1) })
        store.simularFalha("valvula_sem_resposta", "valvula/1", false)
        // queda de rede do equipamento (LWT) → desconhecido → volta
        store.simularFalha("perda_conexao", null, true, 3)
        verifica(esperarAte(3000) { !store.estado.value.plantaViva }, "LWT/heartbeat ausente marca desconhecido")
        igual(EstadoConexao.CONECTADO, store.estado.value.conexao, "app continua no broker")
        verifica(esperarAte(8000) { store.estado.value.plantaViva }, "equipamento voltou")
        store.desconectar(); escopo.cancel()
    }
    teste("mqtt: keep-alive detecta broker morto (porta sem auth, broker derrubado)") {
        val col = Colector(); val c = MqttCliente(cfgBase.copy(porta = portaSemAuth, usuario = "", senha = "", keepAliveS = 2), col)
        c.conectar(); verifica(col.conectou)
        File(System.getProperty("java.io.tmpdir"), "matar_broker_aux").writeText("1") // sinal para o run.sh derrubar o broker auxiliar
        verifica(esperarAte(8000) { col.desconectou != "nunca" }, "deveria detectar queda"); println("     motivo: ${col.desconectou}")
    }
}
