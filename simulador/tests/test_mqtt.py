import time
import uuid

from conftest import Espiao

from mqtt_sim import Simulador


def cmd(espiao, topico, c):
    cid = uuid.uuid4().hex
    t0 = espiao.pub(topico + "/cmd", {"cmd": c, "id": cid, "ts": 0})
    return cid, t0


def test_estados_retidos_chegam_para_cliente_novo(sim, broker):
    time.sleep(0.5)
    e = Espiao(broker.port); assert e.conectado.wait(5)
    e.sub("planta/#")
    assert e.esperar(lambda m: m[1] == "planta/valvula/3/estado" and m[3] is True)
    assert e.esperar(lambda m: m[1] == "planta/bomba/1/estado" and m[2]["estado"] == "desligada")
    assert e.esperar(lambda m: m[1] == "planta/tanque/2/nivel" and m[2]["nivel_pct"] == 20.0)
    assert e.esperar(lambda m: m[1] == "planta/status" and m[2]["online"] is True)
    e.stop()


def test_heartbeat_a_cada_2s(sim, espiao):
    espiao.sub("planta/heartbeat")
    assert espiao.esperar(lambda m: m[1] == "planta/heartbeat", 3)
    time.sleep(4.2)
    hbs = [m for m in espiao.msgs if m[1] == "planta/heartbeat"]
    assert 2 <= len(hbs) <= 4
    assert all(m[2]["modo"] == "normal" for m in hbs)
    seqs = [m[2]["seq"] for m in hbs]
    assert seqs == sorted(seqs)


def test_comando_valvula_ack_rapido_e_estado_final(sim, espiao):
    espiao.sub("planta/valvula/1/#")
    cid, t0 = cmd(espiao, "planta/valvula/1", "abrir")
    ack = espiao.esperar(lambda m: m[1] == "planta/valvula/1/ack" and m[2]["id"] == cid, 2)
    assert ack and ack[2]["ok"] is True
    latencia_ms = (ack[0] - t0) * 1000
    print(f"latência ack válvula: {latencia_ms:.0f} ms")
    assert latencia_ms < 400
    assert espiao.esperar(lambda m: m[1] == "planta/valvula/1/estado" and m[2]["estado"] == "abrindo", 2)
    fin = espiao.esperar(lambda m: m[1] == "planta/valvula/1/estado" and m[2]["estado"] == "aberta", 5)
    assert fin and 2.5 < (fin[0] - t0) < 4.0


def test_bomba_intertravada_e_transferencia(sim, espiao):
    espiao.sub("planta/#")
    cid, _ = cmd(espiao, "planta/bomba/1", "ligar")
    ack = espiao.esperar(lambda m: m[1] == "planta/bomba/1/ack" and m[2]["id"] == cid, 2)
    assert ack[2]["ok"] is False and ack[2]["motivo"] == "valvula_1_fechada"
    cmd(espiao, "planta/valvula/1", "abrir")
    assert espiao.esperar(lambda m: m[1] == "planta/valvula/1/estado" and m[2]["estado"] == "aberta", 5)
    cid, _ = cmd(espiao, "planta/bomba/1", "ligar")
    ack = espiao.esperar(lambda m: m[1] == "planta/bomba/1/ack" and m[2]["id"] == cid, 2)
    assert ack[2]["ok"] is True
    assert espiao.esperar(lambda m: m[1] == "planta/bomba/1/estado" and m[2]["estado"] == "ligada", 2)
    n0 = espiao.ultimo("planta/tanque/2/nivel")[2]["volume_l"]
    time.sleep(3.2)
    n1 = espiao.ultimo("planta/tanque/2/nivel")[2]["volume_l"]
    assert n1 > n0 + 2.0


def test_20_comandos_paralelos_todos_respondidos_em_ordem(sim, espiao):
    espiao.sub("planta/+/+/ack")
    ids = []
    t0 = time.time()
    for i in range(20):
        v = 1 + i % 3
        cid, _ = cmd(espiao, f"planta/valvula/{v}", "abrir" if i % 2 == 0 else "fechar")
        ids.append(cid)
    for cid in ids:
        assert espiao.esperar(lambda m, c=cid: m[1].endswith("/ack") and m[2]["id"] == c, 3)
    dt = (time.time() - t0) * 1000
    print(f"20 comandos respondidos em {dt:.0f} ms")
    assert dt < 400
    recebidos = [m[2]["id"] for m in espiao.msgs if m[1].endswith("/ack")]
    assert recebidos == ids


def test_payload_invalido_responde_ok_false(sim, espiao):
    espiao.sub("planta/valvula/2/ack")
    espiao.c.publish("planta/valvula/2/cmd", b"isto nao e json", qos=1)
    a = espiao.esperar(lambda m: m[1] == "planta/valvula/2/ack", 2)
    assert a and a[2]["ok"] is False and a[2]["motivo"] == "json_invalido"
    espiao.limpar()
    espiao.pub("planta/valvula/2/cmd", {"cmd": "explodir", "id": "x"})
    a = espiao.esperar(lambda m: m[1] == "planta/valvula/2/ack", 2)
    assert a[2]["ok"] is False and a[2]["motivo"] == "cmd_invalido"


def test_falha_valvula_sem_resposta(sim, espiao):
    espiao.sub("planta/#")
    espiao.pub("planta/sim/falha", {"tipo": "valvula_sem_resposta", "alvo": "valvula/2", "ativa": True})
    assert espiao.esperar(lambda m: m[1] == "planta/sim/falhas" and any(f["alvo"] == "valvula/2" for f in m[2]["ativas"]), 2)
    espiao.limpar()
    cid, _ = cmd(espiao, "planta/valvula/2", "abrir")
    assert espiao.esperar(lambda m: m[1] == "planta/valvula/2/ack", 2) is None
    assert sim.planta.valvulas[2].estado == "fechada"
    espiao.pub("planta/sim/falha", {"tipo": "valvula_sem_resposta", "alvo": "valvula/2", "ativa": False})
    assert espiao.esperar(lambda m: m[1] == "planta/sim/falhas" and m[2]["ativas"] == [], 2)
    cid, _ = cmd(espiao, "planta/valvula/2", "abrir")
    assert espiao.esperar(lambda m: m[1] == "planta/valvula/2/ack" and m[2]["id"] == cid, 2)


def test_falha_sensor_travado(sim, espiao):
    espiao.sub("planta/#")
    cmd(espiao, "planta/valvula/3", "abrir")
    espiao.pub("planta/sim/falha", {"tipo": "sensor_travado", "alvo": "tanque/1", "ativa": True})
    time.sleep(1.5); espiao.limpar(); time.sleep(3.2)
    niveis = {m[2]["nivel_pct"] for m in espiao.msgs if m[1] == "planta/tanque/1/nivel"}
    assert len(niveis) == 1
    assert sim.planta.tanques[1].volume_l > 700.5


def test_falha_sensor_invalido_modo_seguro(sim, espiao):
    espiao.sub("planta/#")
    cmd(espiao, "planta/valvula/1", "abrir")
    assert espiao.esperar(lambda m: m[1] == "planta/valvula/1/estado" and m[2]["estado"] == "aberta", 5)
    espiao.pub("planta/sim/falha", {"tipo": "sensor_invalido", "alvo": "tanque/2", "ativa": True})
    assert espiao.esperar(lambda m: m[1] == "planta/tanque/2/nivel" and m[2]["qualidade"] == "invalida", 3)
    assert espiao.esperar(lambda m: m[1] == "planta/heartbeat" and m[2]["modo"] == "seguro", 4)
    assert espiao.esperar(lambda m: m[1] == "planta/valvula/1/estado" and m[2]["estado"] == "fechada", 6)
    cid, _ = cmd(espiao, "planta/bomba/1", "ligar")
    a = espiao.esperar(lambda m: m[1] == "planta/bomba/1/ack" and m[2]["id"] == cid, 2)
    assert a[2]["ok"] is False and a[2]["motivo"] == "modo_seguro"


def test_perda_de_conexao_lwt_e_reconexao(sim, espiao):
    espiao.sub("planta/#")
    assert espiao.esperar(lambda m: m[1] == "planta/status" and m[2]["online"] is True, 3)
    espiao.limpar()
    espiao.pub("planta/sim/falha", {"tipo": "perda_conexao", "ativa": True, "duracao_s": 3})
    assert espiao.esperar(lambda m: m[1] == "planta/status" and m[2]["online"] is False, 5), "LWT não publicado"
    espiao.limpar(); time.sleep(1.5)
    assert not [m for m in espiao.msgs if m[1] == "planta/heartbeat"], "heartbeat não deveria chegar durante a queda"
    assert espiao.esperar(lambda m: m[1] == "planta/status" and m[2]["online"] is True, 8), "não reconectou"
    assert espiao.esperar(lambda m: m[1] == "planta/heartbeat", 4)


def test_broker_exige_usuario_e_senha(broker_auth):
    ruim = Espiao(broker_auth.port, "supervilab", "errada")
    assert ruim.falhou.wait(5) or not ruim.conectado.wait(1)
    ruim.stop()
    anon = Espiao(broker_auth.port)
    assert anon.falhou.wait(5) or not anon.conectado.wait(1)
    anon.stop()
    bom = Espiao(broker_auth.port, "supervilab", "segredo123")
    assert bom.conectado.wait(5)
    bom.stop()
    s = Simulador("127.0.0.1", broker_auth.port, "supervilab", "segredo123")
    s.iniciar()
    fim = time.time() + 5
    while not s.conectado and time.time() < fim: time.sleep(0.05)
    assert s.conectado
    s.parar()
