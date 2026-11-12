import br.com.supervilab.core.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

// Mini framework de testes (sem JUnit: Maven está bloqueado neste ambiente)
var passou = 0; var falhou = 0
val falhas = mutableListOf<String>()
fun teste(nome: String, corpo: () -> Unit) {
    try { corpo(); passou++; println("  ok  $nome") } catch (e: Throwable) { falhou++; falhas += nome; println("FALHOU $nome: ${e.message ?: e}"); if (e !is AssertionError) e.printStackTrace() }
}
fun verifica(cond: Boolean, msg: String = "condição falsa") { if (!cond) throw AssertionError(msg) }
fun <T> igual(esperado: T, obtido: T, msg: String = "") { if (esperado != obtido) throw AssertionError("$msg esperado=$esperado obtido=$obtido") }
fun esperarAte(timeoutMs: Long = 3000, passo: Long = 20, cond: () -> Boolean): Boolean {
    val fim = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < fim) { if (cond()) return true; Thread.sleep(passo) }
    return cond()
}

/** Conexão falsa controlada pelo teste. */
class ConexaoFalsa : Conexao {
    var ouvinte: OuvinteConexao? = null
    val publicados = CopyOnWriteArrayList<Pair<String, String>>()
    var falharPublicar = false
    override fun conectar(ouvinte: OuvinteConexao) { this.ouvinte = ouvinte }
    override fun publicar(topico: String, payload: String) { if (falharPublicar) throw java.net.SocketException("cabo cortado"); publicados += topico to payload }
    override fun desconectar() {}
    fun msg(t: String, p: String) = ouvinte!!.mensagem(t, p, false)
}

class Relogio(var t: Long = 1_700_000_000_000L) { fun avancar(ms: Long) { t += ms } }

fun novoStore(rel: Relogio, cfg: ConfigConexao = ConfigConexao(modoDemo = false, host = "h", usuario = "u", senha = "s")): Pair<PlantaStore, ConexaoFalsa> {
    val fake = ConexaoFalsa()
    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = PlantaStore(escopo, AjustesMemoria(cfg), { fake }, { rel.t })
    store.conectar()
    fake.ouvinte!!.conectado()
    return store to fake
}
fun hb(fake: ConexaoFalsa, rel: Relogio, seguro: Boolean = false) =
    fake.msg("planta/heartbeat", """{"ts":${rel.t},"uptime_s":1,"modo":"${if (seguro) "seguro" else "normal"}","seq":1}""")
fun nivel(fake: ConexaoFalsa, rel: Relogio, n: Int, pct: Double, q: String = "boa") =
    fake.msg("planta/tanque/$n/nivel", """{"nivel_pct":$pct,"volume_l":${pct * 10},"qualidade":"$q","ts":${rel.t}}""")
fun ativo(st: EstadoUI, tipo: TipoAlarme, eq: Equipamento? = null) = st.alarmes.any { it.ativo && it.tipo == tipo && (eq == null || it.equipamento == eq) }

fun testesJson() {
    teste("json: parse de objeto aninhado") {
        val m = Json.objeto("""{"a":1,"b":2.5,"c":"x\"yé","d":[true,null,{"e":-3e2}],"f":{}}""")
        igual(1L, m["a"]); igual(2.5, m["b"]); igual("x\"yé", m["c"])
        val d = m["d"] as List<*>; igual(true, d[0]); igual(null, d[1]); igual(-300.0, (d[2] as Map<*, *>)["e"])
        igual(0, (m["f"] as Map<*, *>).size)
    }
    teste("json: stringify e ida e volta") {
        val s = Json.stringify(mapOf("cmd" to "abrir", "id" to "a1", "ts" to 5L, "x" to 45.2, "n" to null, "l" to listOf(1, "2")))
        igual("""{"cmd":"abrir","id":"a1","ts":5,"x":45.2,"n":null,"l":[1,"2"]}""", s)
        igual(mapOf("a" to 1L), Json.objeto(Json.stringify(mapOf("a" to 1))))
        igual("""{"v":45.0}""", Json.stringify(mapOf("v" to 45.0)))
    }
    teste("json: erros não derrubam") {
        for (ruim in listOf("", "{", "[1,", "{\"a\":}", "isto nao e json", "{\"a\":1} x", "[1 2]"))
            verifica(runCatching { Json.parse(ruim) }.isFailure, "deveria falhar: '$ruim'")
        igual(null, Payloads.valvula("isto nao e json")); igual(null, Payloads.nivel("{}")); igual(null, Payloads.ack(""))
    }
}

fun testesProtocolo() {
    teste("protocolo: tópicos") {
        igual("planta/valvula/2/cmd", Protocolo.topicoCmd(Equipamento.V2)); igual("planta/bomba/1/ack", Protocolo.topicoAck(Equipamento.BOMBA))
        igual("planta/tanque/1/nivel", Protocolo.topicoEstado(Equipamento.T1))
        igual(Equipamento.V3 to "estado", Protocolo.interpretar("planta/valvula/3/estado"))
        igual(null, Protocolo.interpretar("planta/heartbeat")); igual(null, Protocolo.interpretar("outra/valvula/1/estado")); igual(null, Protocolo.interpretar("planta/valvula/x/estado"))
    }
    teste("protocolo: payloads") {
        igual(EstadoValvula.ABRINDO to 10L, Payloads.valvula("""{"estado":"abrindo","ts":10}"""))
        igual(null, Payloads.valvula("""{"estado":"meio"}"""))
        val n = Payloads.nivel("""{"nivel_pct":45.2,"volume_l":452.0,"qualidade":"boa","ts":1}""")!!
        igual(45.2, n.pct); igual(QualidadeSensor.BOA, n.qualidade)
        igual(null, Payloads.nivel("""{"nivel_pct":145.2,"qualidade":"boa"}"""), "nível fora de 0..100")
        igual(QualidadeSensor.INVALIDA, Payloads.nivel("""{"nivel_pct":null,"volume_l":null,"qualidade":"invalida","ts":1}""")!!.qualidade)
        igual(true, Payloads.heartbeat("""{"ts":1,"uptime_s":2,"modo":"seguro","seq":3}""")!!.modoSeguro)
        igual("a válvula 1 precisa estar aberta", Payloads.motivoLegivel("valvula_1_fechada"))
        val cmd = Json.objeto(Protocolo.payloadCmd("abrir", "id1", 7)); igual("abrir", cmd["cmd"]); igual("id1", cmd["id"]); igual(7L, cmd["ts"])
    }
}

fun testesStore() {
    teste("store: sem heartbeat tudo é DESCONHECIDO; com heartbeat fica conhecido") {
        val rel = Relogio(); val (store, fake) = novoStore(rel)
        fake.msg("planta/valvula/1/estado", """{"estado":"aberta","ts":1}""")
        store.tick()
        var st = store.estado.value
        verifica(!st.plantaViva); igual(null, store.estadoVisivel(st).valvulas[1], "antes do heartbeat deve ser desconhecido")
        verifica(ativo(st, TipoAlarme.CONEXAO_PERDIDA), "alarme de conexão enquanto a planta não dá sinal")
        hb(fake, rel); store.tick(); st = store.estado.value
        verifica(st.plantaViva); igual(EstadoValvula.ABERTA, store.estadoVisivel(st).valvulas[1])
        verifica(!ativo(st, TipoAlarme.CONEXAO_PERDIDA))
        rel.avancar(6_500); store.tick(); st = store.estado.value
        verifica(!st.plantaViva, "6,5 s sem heartbeat → não viva")
        igual(null, store.estadoVisivel(st).valvulas[1]); igual(null, store.estadoVisivel(st).bomba)
        verifica(ativo(st, TipoAlarme.CONEXAO_PERDIDA))
    }
    teste("store: queda do broker marca desconhecido e reconecta com espera") {
        val rel = Relogio(); val (store, fake) = novoStore(rel)
        hb(fake, rel); store.tick(); verifica(store.estado.value.plantaViva)
        fake.ouvinte!!.desconectado("conexão perdida")
        val st = store.estado.value
        igual(EstadoConexao.RECONECTANDO, st.conexao); verifica(!st.plantaViva); igual("conexão perdida", st.erroConexao)
        verifica(ativo(st, TipoAlarme.CONEXAO_PERDIDA))
        verifica(esperarAte(4000) { store.estado.value.conexao == EstadoConexao.RECONECTANDO && fake.ouvinte != null })
    }
    teste("store: alarmes de nível com histerese") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        nivel(fake, rel, 2, 89.9); verifica(!ativo(store.estado.value, TipoAlarme.NIVEL_ALTO))
        nivel(fake, rel, 2, 90.0); verifica(ativo(store.estado.value, TipoAlarme.NIVEL_ALTO, Equipamento.T2))
        nivel(fake, rel, 2, 88.5); verifica(ativo(store.estado.value, TipoAlarme.NIVEL_ALTO), "histerese: 88,5 ainda ativo")
        nivel(fake, rel, 2, 87.9); verifica(!ativo(store.estado.value, TipoAlarme.NIVEL_ALTO))
        nivel(fake, rel, 1, 10.0); verifica(ativo(store.estado.value, TipoAlarme.NIVEL_BAIXO, Equipamento.T1))
        nivel(fake, rel, 1, 12.1); verifica(!ativo(store.estado.value, TipoAlarme.NIVEL_BAIXO))
        igual(1, store.estado.value.alarmes.count { it.tipo == TipoAlarme.NIVEL_ALTO }, "um único registro no histórico")
        verifica(store.estado.value.alarmes.first { it.tipo == TipoAlarme.NIVEL_ALTO }.fim != null)
    }
    teste("store: nível alto some quando a planta fica desconhecida (nunca alarme com dado velho)") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        nivel(fake, rel, 1, 95.0); verifica(ativo(store.estado.value, TipoAlarme.NIVEL_ALTO))
        rel.avancar(7000); store.tick()
        verifica(!ativo(store.estado.value, TipoAlarme.NIVEL_ALTO)); verifica(ativo(store.estado.value, TipoAlarme.CONEXAO_PERDIDA))
    }
    teste("store: sensor inválido e modo seguro geram alarmes") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        fake.msg("planta/tanque/2/nivel", """{"nivel_pct":null,"volume_l":null,"qualidade":"invalida","ts":1}""")
        verifica(ativo(store.estado.value, TipoAlarme.SENSOR_INVALIDO, Equipamento.T2))
        hb(fake, rel, seguro = true); verifica(ativo(store.estado.value, TipoAlarme.MODO_SEGURO))
        hb(fake, rel, seguro = false); verifica(!ativo(store.estado.value, TipoAlarme.MODO_SEGURO))
        nivel(fake, rel, 2, 50.0); verifica(!ativo(store.estado.value, TipoAlarme.SENSOR_INVALIDO))
    }
    teste("store: sensor travado detectado por leitura parada com fluxo") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        fake.msg("planta/bomba/1/estado", """{"estado":"ligada","ts":1}""")
        for (i in 0..25) { nivel(fake, rel, 1, 40.0); rel.avancar(1000); if (i % 2 == 0) hb(fake, rel); store.tick() }
        verifica(ativo(store.estado.value, TipoAlarme.SENSOR_SUSPEITO, Equipamento.T1), "40 % parado por 25 s com bomba ligada")
        verifica(!ativo(store.estado.value, TipoAlarme.SENSOR_SUSPEITO, Equipamento.T2), "tanque 2 sem leitura não alarma")
        nivel(fake, rel, 1, 39.5); verifica(!ativo(store.estado.value, TipoAlarme.SENSOR_SUSPEITO, Equipamento.T1))
    }
    teste("store: comando aceito e concluído com latências") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        val id = store.comandar(Equipamento.V1, "abrir")
        verifica(esperarAte { fake.publicados.any { it.first == "planta/valvula/1/cmd" } }, "comando publicado")
        val p = Json.objeto(fake.publicados.last().second); igual("abrir", p["cmd"]); igual(id, p["id"])
        rel.avancar(120); fake.msg("planta/valvula/1/ack", """{"id":"$id","ok":true,"motivo":"","ts":1}""")
        var c = store.estado.value.comandos.first { it.id == id }; igual(StatusComando.ACEITO, c.status); igual(120L, c.ackMs)
        fake.msg("planta/valvula/1/estado", """{"estado":"abrindo","ts":1}"""); verifica(store.estado.value.comandoPendente(Equipamento.V1))
        rel.avancar(3000); fake.msg("planta/valvula/1/estado", """{"estado":"aberta","ts":1}""")
        c = store.estado.value.comandos.first { it.id == id }; igual(StatusComando.CONCLUIDO, c.status); igual(3120L, c.concluidoMs)
    }
    teste("store: comando recusado mostra motivo legível") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        val id = store.comandar(Equipamento.BOMBA, "ligar")
        verifica(esperarAte { fake.publicados.any { it.first == "planta/bomba/1/cmd" } })
        fake.msg("planta/bomba/1/ack", """{"id":"$id","ok":false,"motivo":"valvula_1_fechada","ts":1}""")
        val c = store.estado.value.comandos.first { it.id == id }
        igual(StatusComando.RECUSADO, c.status); igual("a válvula 1 precisa estar aberta", c.detalhe)
        verifica(!ativo(store.estado.value, TipoAlarme.BOMBA_SEM_RESPOSTA))
    }
    teste("store: válvula sem resposta → alarme; sem alarme falso se comando mais novo funcionou") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        val id1 = store.comandar(Equipamento.V2, "abrir")
        verifica(esperarAte { fake.publicados.size == 1 })
        rel.avancar(1600); hb(fake, rel); store.tick()
        igual(StatusComando.SEM_RESPOSTA, store.estado.value.comandos.first { it.id == id1 }.status)
        verifica(ativo(store.estado.value, TipoAlarme.VALVULA_SEM_RESPOSTA, Equipamento.V2))
        // estado chega depois → normaliza
        fake.msg("planta/valvula/2/estado", """{"estado":"fechada","ts":1}""")
        verifica(!ativo(store.estado.value, TipoAlarme.VALVULA_SEM_RESPOSTA, Equipamento.V2))
        // dois comandos: o antigo expira mas o novo foi aceito → sem alarme
        val a = store.comandar(Equipamento.V3, "abrir"); rel.avancar(500); val b = store.comandar(Equipamento.V3, "fechar")
        verifica(esperarAte { fake.publicados.size == 3 })
        fake.msg("planta/valvula/3/ack", """{"id":"$b","ok":true,"motivo":"","ts":1}""")
        rel.avancar(1200); hb(fake, rel); store.tick()
        igual(StatusComando.SEM_RESPOSTA, store.estado.value.comandos.first { it.id == a }.status)
        verifica(!ativo(store.estado.value, TipoAlarme.VALVULA_SEM_RESPOSTA, Equipamento.V3), "sem alarme falso")
    }
    teste("store: aceito mas sem estado final em 8 s → sem resposta") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        val id = store.comandar(Equipamento.V1, "abrir"); verifica(esperarAte { fake.publicados.size == 1 })
        fake.msg("planta/valvula/1/ack", """{"id":"$id","ok":true,"motivo":"","ts":1}""")
        for (i in 1..5) { rel.avancar(2000); hb(fake, rel); store.tick() }
        igual(StatusComando.SEM_RESPOSTA, store.estado.value.comandos.first { it.id == id }.status)
        verifica(ativo(store.estado.value, TipoAlarme.VALVULA_SEM_RESPOSTA, Equipamento.V1))
    }
    teste("store: sem conexão o comando é recusado na hora; erro de envio vira ERRO") {
        val rel = Relogio(); val (store, fake) = novoStore(rel)
        fake.ouvinte!!.desconectado("x")
        val id = store.comandar(Equipamento.V1, "abrir")
        igual(StatusComando.ERRO, store.estado.value.comandos.first { it.id == id }.status); igual(0, fake.publicados.size)
        fake.ouvinte!!.conectado(); fake.falharPublicar = true
        val id2 = store.comandar(Equipamento.V1, "abrir")
        verifica(esperarAte { store.estado.value.comandos.first { it.id == id2 }.status == StatusComando.ERRO })
        igual("conexão perdida", store.estado.value.comandos.first { it.id == id2 }.detalhe)
    }
    teste("store: 20 comandos em paralelo não bloqueiam e saem em ordem") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        val t0 = System.nanoTime()
        val ids = (1..20).map { store.comandar(Equipamento.VALVULAS[it % 3], if (it % 2 == 0) "abrir" else "fechar") }
        val gasto = (System.nanoTime() - t0) / 1e6
        verifica(gasto < 100, "comandar() bloqueou ${gasto} ms")
        verifica(esperarAte { fake.publicados.size == 20 })
        igual(ids.toSet(), fake.publicados.map { Json.objeto(it.second)["id"] }.toSet())
    }
    teste("store: histórico guarda pontos com ts da mensagem e corta no máximo") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        for (i in 0 until 1000) { nivel(fake, rel, 1, 50.0 + (i % 10)); rel.avancar(1000) }
        val h = store.estado.value.historico[1]!!
        igual(900, h.size); verifica(h.last().ts > h.first().ts)
        fake.msg("planta/tanque/2/nivel", """{"nivel_pct":10.5,"volume_l":105,"qualidade":"boa","ts":1}""")
        igual(rel.t, store.estado.value.historico[2]!!.last().ts, "ts absurdo (1 ms) é substituído pelo relógio local")
    }
    teste("store: reconhecer alarmes e relatório de diagnóstico") {
        val rel = Relogio(); val (store, fake) = novoStore(rel); hb(fake, rel); store.tick()
        nivel(fake, rel, 1, 99.0); val a = store.estado.value.alarmesAtivos.first()
        igual(1, store.estado.value.alarmesNaoReconhecidos); store.reconhecerAlarme(a.id); igual(0, store.estado.value.alarmesNaoReconhecidos)
        val r = store.relatorioDiagnostico(); verifica(r.contains("Nível alto") && r.contains("conectado"), r)
    }
    teste("store: configuração inválida não tenta conectar") {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var fabricou = false
        val store = PlantaStore(escopo, AjustesMemoria(ConfigConexao(modoDemo = false)), { fabricou = true; ConexaoFalsa() })
        store.conectar()
        verifica(!fabricou); igual("Informe o endereço do broker", store.estado.value.erroConexao)
    }
}

fun testesDemo() {
    teste("demo: fluxo completo (conecta, histórico, comandos, física, falhas) ") {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = PlantaStore(escopo, AjustesMemoria(ConfigConexao(modoDemo = true)), { ConexaoDemo() })
        store.iniciar()
        verifica(esperarAte(3000) { store.estado.value.plantaViva }, "demo deve ficar viva")
        var st = store.estado.value
        verifica(st.historico[1]!!.size >= 600, "histórico inicial"); igual(EstadoBomba.LIGADA, st.bomba); igual(EstadoValvula.ABERTA, st.valvulas[1])
        val n0 = st.tanques[2]!!.pct!!
        verifica(esperarAte(2500) { (store.estado.value.tanques[2]?.pct ?: 0.0) > n0 }, "tanque 2 sobe com a bomba")
        // desligar bomba: ack rápido
        val t0 = System.currentTimeMillis()
        val id = store.comandar(Equipamento.BOMBA, "desligar")
        verifica(esperarAte(1000) { store.estado.value.comandos.first { it.id == id }.status == StatusComando.CONCLUIDO })
        val lat = System.currentTimeMillis() - t0; verifica(lat < 400, "latência demo $lat ms"); println("     latência demo bomba: $lat ms")
        // abrir válvula 2: abrindo → aberta em ~3 s
        val id2 = store.comandar(Equipamento.V2, "abrir")
        verifica(esperarAte(1000) { store.estado.value.valvulas[2] == EstadoValvula.ABRINDO })
        verifica(esperarAte(4000) { store.estado.value.valvulas[2] == EstadoValvula.ABERTA })
        igual(StatusComando.CONCLUIDO, store.estado.value.comandos.first { it.id == id2 }.status)
        // ligar bomba com válvula 1 aberta → ok; fechar v1 → bomba desliga por intertravamento
        store.comandar(Equipamento.BOMBA, "ligar"); verifica(esperarAte(1000) { store.estado.value.bomba == EstadoBomba.LIGADA })
        store.comandar(Equipamento.V1, "fechar"); verifica(esperarAte(4500) { store.estado.value.bomba == EstadoBomba.DESLIGADA && store.estado.value.valvulas[1] == EstadoValvula.FECHADA })
        store.comandar(Equipamento.BOMBA, "ligar")
        verifica(esperarAte(1000) { store.estado.value.comandos.first().status == StatusComando.RECUSADO }); igual("a válvula 1 precisa estar aberta", store.estado.value.comandos.first().detalhe)
        // falha: válvula sem resposta
        store.simularFalha("valvula_sem_resposta", "valvula/3", true)
        verifica(esperarAte(1500) { store.estado.value.falhasSimuladas.contains("valvula_sem_resposta:valvula/3") })
        store.comandar(Equipamento.V3, "abrir")
        verifica(esperarAte(2500) { ativo(store.estado.value, TipoAlarme.VALVULA_SEM_RESPOSTA, Equipamento.V3) }, "alarme de válvula sem resposta")
        store.simularFalha("valvula_sem_resposta", "valvula/3", false)
        // falha: perda de conexão 2 s → desconhecido → volta
        store.simularFalha("perda_conexao", null, true, 2)
        verifica(esperarAte(1500) { !store.estado.value.plantaViva && store.estado.value.conexao == EstadoConexao.RECONECTANDO }, "fica desconhecido na queda")
        verifica(ativo(store.estado.value, TipoAlarme.CONEXAO_PERDIDA))
        verifica(esperarAte(5000) { store.estado.value.plantaViva }, "volta depois da queda")
        verifica(!ativo(store.estado.value, TipoAlarme.CONEXAO_PERDIDA))
        // falha: sensor inválido → modo seguro
        store.simularFalha("sensor_invalido", "tanque/1", true)
        verifica(esperarAte(3000) { ativo(store.estado.value, TipoAlarme.SENSOR_INVALIDO, Equipamento.T1) && ativo(store.estado.value, TipoAlarme.MODO_SEGURO) })
        store.simularFalha("sensor_invalido", "tanque/1", false)
        verifica(esperarAte(3000) { !ativo(store.estado.value, TipoAlarme.MODO_SEGURO) })
        store.desconectar(); st = store.estado.value
        igual(EstadoConexao.DESCONECTADO, st.conexao); igual(null, store.estadoVisivel(st).bomba)
        escopo.cancel()
    }
}

fun main(args: Array<String>) {
    println("== JSON e protocolo =="); testesJson(); testesProtocolo()
    println("== PlantaStore =="); testesStore()
    println("== Demo =="); testesDemo()
    if (args.isNotEmpty()) { println("== MQTT real (broker Python) =="); testesMqtt(args[0].toInt(), args[1].toInt(), args[2].toInt(), args[3]) }
    println("\nRESULTADO: $passou passaram, $falhou falharam")
    if (falhou > 0) { println("Falhas: $falhas"); System.exit(1) }
}
