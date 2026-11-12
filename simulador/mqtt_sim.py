"""Simulador MQTT da planta: publica estados, responde comandos, simula falhas.

Uso: python3 mqtt_sim.py --host localhost --port 1883 [--user u --senha s --tls]
"""
from __future__ import annotations

import argparse
import json
import logging
import socket
import ssl
import threading
import time
import uuid

import paho.mqtt.client as mqtt

from planta import Planta

log = logging.getLogger("sim")
PASSO_S = 0.1
PERIODO_NIVEL_S = 1.0
PERIODO_HEARTBEAT_S = 2.0
TIMEOUT_BROKER_S = 15.0


def agora_ms() -> int:
    return int(time.time() * 1000)


class Simulador:
    def __init__(self, host: str, port: int, user: str | None = None, senha: str | None = None,
                 tls: bool = False, ca: str | None = None, cliente_id: str | None = None):
        self.planta = Planta()
        self.host, self.port = host, port
        self.lock = threading.Lock()
        self.inicio = time.time()
        self.seq = 0
        self.falhas: dict[str, dict] = {}  # chave "tipo:alvo" -> info
        self._parar = threading.Event()
        self.ultimo_contato = time.time()
        self.conectado = False
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id=cliente_id or f"supervilab-sim-{uuid.uuid4().hex[:6]}",
                                  protocol=mqtt.MQTTv311)
        if user:
            self.client.username_pw_set(user, senha)
        if tls:
            ctx = ssl.create_default_context(cafile=ca)
            if ca is None:
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
            self.client.tls_set_context(ctx)
        self.client.will_set("planta/status", json.dumps({"online": False, "ts": agora_ms()}), qos=1, retain=True)
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message

    # ---------- MQTT ----------
    def _on_connect(self, client, userdata, flags, reason, props=None):
        log.info("conectado ao broker (%s)", reason)
        self.conectado = True
        self.ultimo_contato = time.time()
        client.reconnect_delay_set(min_delay=1, max_delay=10)
        self.falhas.pop("perda_conexao:None", None)
        client.subscribe([("planta/+/+/cmd", 1), ("planta/sim/falha", 1)])
        self.pub("planta/status", {"online": True, "ts": agora_ms(), "fw": "sim-1.0"}, retain=True)
        with self.lock:
            self.planta.sair_modo_seguro()
            self.publicar_tudo()

    def _on_disconnect(self, client, userdata, flags, reason, props=None):
        log.warning("desconectado do broker (%s)", reason)
        self.conectado = False

    def pub(self, topico: str, payload: dict, retain: bool = False):
        if not self.conectado:
            return
        self.client.publish(topico, json.dumps(payload), qos=1, retain=retain)

    def _on_message(self, client, userdata, msg):
        self.ultimo_contato = time.time()
        try:
            dados = json.loads(msg.payload.decode("utf-8"))
            if not isinstance(dados, dict):
                raise ValueError("payload não é objeto")
        except Exception as e:  # noqa: BLE001
            log.warning("payload inválido em %s: %s", msg.topic, e)
            self._ack(msg.topic, {"id": None, "ok": False, "motivo": "json_invalido"})
            return
        partes = msg.topic.split("/")
        if msg.topic == "planta/sim/falha":
            self.aplicar_falha(dados)
            return
        if len(partes) == 4 and partes[3] == "cmd":
            tipo, n = partes[1], partes[2]
            with self.lock:
                self.tratar_comando(tipo, n, dados, msg.topic)

    def _ack(self, topico_cmd: str, resposta: dict):
        resposta["ts"] = agora_ms()
        self.pub(topico_cmd[: -len("cmd")] + "ack", resposta)

    def tratar_comando(self, tipo: str, n: str, dados: dict, topico: str):
        cmd = dados.get("cmd")
        cid = dados.get("id")
        if tipo == "valvula" and n.isdigit():
            ok, motivo = self.planta.comandar_valvula(int(n), str(cmd))
            if motivo == "sem_resposta":
                log.info("válvula %s ignorou comando (falha simulada)", n)
                return  # falha: não responde nada
            self._ack(topico, {"id": cid, "ok": ok, "motivo": motivo})
            if ok:
                self.pub_valvula(int(n))
        elif tipo == "bomba" and n == "1":
            ok, motivo = self.planta.comandar_bomba(str(cmd))
            self._ack(topico, {"id": cid, "ok": ok, "motivo": motivo})
            if ok:
                self.pub_bomba()
        else:
            self._ack(topico, {"id": cid, "ok": False, "motivo": "equipamento_desconhecido"})

    # ---------- publicação ----------
    def pub_valvula(self, n: int):
        self.pub(f"planta/valvula/{n}/estado", {"estado": self.planta.valvulas[n].estado, "ts": agora_ms()}, retain=True)

    def pub_bomba(self):
        self.pub("planta/bomba/1/estado", {"estado": self.planta.bomba, "ts": agora_ms()}, retain=True)

    def pub_niveis(self):
        for n, t in self.planta.tanques.items():
            d = t.leitura()
            d["ts"] = agora_ms()
            self.pub(f"planta/tanque/{n}/nivel", d, retain=True)

    def pub_heartbeat(self):
        self.seq += 1
        self.pub("planta/heartbeat", {"ts": agora_ms(), "uptime_s": int(time.time() - self.inicio),
                                      "modo": "seguro" if self.planta.modo_seguro else "normal", "seq": self.seq})

    def pub_falhas(self):
        self.pub("planta/sim/falhas", {"ativas": list(self.falhas.values()), "ts": agora_ms()}, retain=True)

    def publicar_tudo(self):
        for n in self.planta.valvulas:
            self.pub_valvula(n)
        self.pub_bomba()
        self.pub_niveis()
        self.pub_falhas()

    # ---------- falhas ----------
    def aplicar_falha(self, d: dict):
        tipo, alvo, ativa = d.get("tipo"), d.get("alvo"), bool(d.get("ativa", True))
        dur = float(d.get("duracao_s") or 0)
        chave = f"{tipo}:{alvo}"
        with self.lock:
            if tipo == "sensor_travado" and alvo and alvo.startswith("tanque/"):
                self.planta.tanques[int(alvo.split("/")[1])].sensor_travado = ativa
            elif tipo == "sensor_invalido" and alvo and alvo.startswith("tanque/"):
                self.planta.tanques[int(alvo.split("/")[1])].sensor_invalido = ativa
                if not ativa:
                    self.planta.sair_modo_seguro()
            elif tipo == "valvula_sem_resposta" and alvo and alvo.startswith("valvula/"):
                self.planta.valvulas[int(alvo.split("/")[1])].sem_resposta = ativa
            elif tipo == "perda_conexao":
                if ativa:
                    dur = dur or 10.0
                    log.warning("simulando perda de conexão por %.0fs", dur)
                    self.pub_falhas_com(chave, d, True)
                    # derruba o socket sem DISCONNECT: o broker publica o LWT e o paho
                    # reconecta sozinho depois de `dur` segundos
                    self.client.reconnect_delay_set(min_delay=int(dur), max_delay=int(dur))
                    sock = self.client.socket()
                    if sock is not None:
                        try:
                            sock.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass
                    return
            else:
                log.warning("falha desconhecida: %s", d)
                return
            self.pub_falhas_com(chave, d, ativa)
            log.info("falha %s %s -> %s", tipo, alvo, "ATIVA" if ativa else "inativa")

    def pub_falhas_com(self, chave, d, ativa):
        if ativa:
            self.falhas[chave] = {"tipo": d.get("tipo"), "alvo": d.get("alvo")}
        else:
            self.falhas.pop(chave, None)
        self.pub_falhas()

    # ---------- laço principal ----------
    def laco(self):
        prox_nivel = prox_hb = time.time()
        while not self._parar.is_set():
            t0 = time.time()
            with self.lock:
                # fail-safe: sem broker por TIMEOUT → modo seguro
                sem_contato = (not self.conectado) and (t0 - self.ultimo_contato > TIMEOUT_BROKER_S)
                if sem_contato:
                    self.planta.entrar_modo_seguro("sem_conexao")
                mudou = self.planta.avancar(PASSO_S)
                for n in mudou["valvulas"]:
                    self.pub_valvula(n)
                if mudou["bomba"]:
                    self.pub_bomba()
                if t0 >= prox_nivel:
                    self.pub_niveis()
                    prox_nivel = t0 + PERIODO_NIVEL_S
                if t0 >= prox_hb:
                    self.pub_heartbeat()
                    prox_hb = t0 + PERIODO_HEARTBEAT_S
            time.sleep(max(0.0, PASSO_S - (time.time() - t0)))

    def iniciar(self):
        self.client.connect_async(self.host, self.port, keepalive=10)
        self.client.loop_start()
        self.thread = threading.Thread(target=self.laco, daemon=True)
        self.thread.start()

    def parar(self):
        self._parar.set()
        self.thread.join(timeout=2)
        self.pub("planta/status", {"online": False, "ts": agora_ms()}, retain=True)
        self.client.loop_stop()
        self.client.disconnect()


def main():
    ap = argparse.ArgumentParser(description="Simulador da planta SuperviLab")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--user")
    ap.add_argument("--senha")
    ap.add_argument("--tls", action="store_true")
    ap.add_argument("--ca", help="certificado da CA (TLS)")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    sim = Simulador(a.host, a.port, a.user, a.senha, a.tls, a.ca)
    sim.iniciar()
    log.info("simulador rodando contra %s:%s (Ctrl+C para parar)", a.host, a.port)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        sim.parar()


if __name__ == "__main__":
    main()
