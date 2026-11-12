import asyncio, os, sys, threading, time, socket, json
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from amqtt.broker import Broker
from broker import config_broker
from mqtt_sim import Simulador
import paho.mqtt.client as mqtt


def porta_livre():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


class BrokerThread:
    def __init__(self, user="", senha=""):
        self.port = porta_livre()
        self.cfg = config_broker(self.port, user, senha, bind="127.0.0.1")
        self.loop = asyncio.new_event_loop()
        self.pronto = threading.Event()
        self.t = threading.Thread(target=self._run, daemon=True); self.t.start()
        assert self.pronto.wait(10), "broker não subiu"

    def _run(self):
        asyncio.set_event_loop(self.loop)
        self.broker = Broker(self.cfg, loop=self.loop)
        self.loop.run_until_complete(self.broker.start())
        self.pronto.set()
        self.loop.run_forever()

    def stop(self):
        fut = asyncio.run_coroutine_threadsafe(self.broker.shutdown(), self.loop)
        try: fut.result(5)
        except Exception: pass
        self.loop.call_soon_threadsafe(self.loop.stop)


@pytest.fixture
def broker():
    b = BrokerThread(); yield b; b.stop()


@pytest.fixture
def broker_auth():
    b = BrokerThread("supervilab", "segredo123"); yield b; b.stop()


class Espiao:
    """Cliente de teste: guarda todas as mensagens por tópico e mede tempos."""
    def __init__(self, port, user=None, senha=None):
        self.msgs = []
        self.lock = threading.Lock()
        self.c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="espiao", protocol=mqtt.MQTTv311)
        if user: self.c.username_pw_set(user, senha)
        self.conectado = threading.Event()
        self.falhou = threading.Event()
        def on_connect(c, u, f, rc, p=None):
            if rc == 0: self.conectado.set()
            else: self.falhou.set()
        self.c.on_connect = on_connect
        self.c.on_connect_fail = lambda *a: self.falhou.set()
        self.c.on_message = lambda c, u, m: self._rec(m)
        self.c.connect_async("127.0.0.1", port, keepalive=5)
        self.c.loop_start()

    def _rec(self, m):
        with self.lock:
            self.msgs.append((time.time(), m.topic, json.loads(m.payload.decode()), m.retain))

    def sub(self, *topicos):
        for t in topicos: self.c.subscribe(t, 1)

    def pub(self, t, payload):
        self.c.publish(t, json.dumps(payload), qos=1)
        return time.time()

    def esperar(self, pred, timeout=5.0):
        fim = time.time() + timeout
        while time.time() < fim:
            with self.lock:
                for m in self.msgs:
                    if pred(m): return m
            time.sleep(0.02)
        return None

    def ultimo(self, topico):
        with self.lock:
            xs = [m for m in self.msgs if m[1] == topico]
        return xs[-1] if xs else None

    def limpar(self):
        with self.lock: self.msgs.clear()

    def stop(self):
        self.c.loop_stop(); self.c.disconnect()


@pytest.fixture
def sim(broker):
    s = Simulador("127.0.0.1", broker.port)
    s.iniciar()
    fim = time.time() + 5
    while not s.conectado and time.time() < fim: time.sleep(0.05)
    assert s.conectado
    yield s
    s.parar()


@pytest.fixture
def espiao(broker):
    e = Espiao(broker.port)
    assert e.conectado.wait(5)
    yield e
    e.stop()
