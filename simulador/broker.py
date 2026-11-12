"""Broker MQTT local (amqtt) para testes e demo, com usuário/senha e TLS opcional.

Uso: python3 broker.py [--port 1883] [--user supervilab --senha segredo] [--tls --cert cert.pem --chave key.pem --porta-tls 8883]
Sem --user, aceita conexões anônimas (apenas para testes locais).
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
from dataclasses import dataclass, field

from amqtt.broker import Broker
from amqtt.plugins.base import BaseAuthPlugin
from amqtt.session import Session


class UsuarioSenhaPlugin(BaseAuthPlugin):
    """Autentica contra um único par usuário/senha (ou variáveis MQTT_USER/MQTT_SENHA)."""

    @dataclass
    class Config:
        usuario: str = field(default_factory=lambda: os.environ.get("MQTT_USER", ""))
        senha: str = field(default_factory=lambda: os.environ.get("MQTT_SENHA", ""))

    async def authenticate(self, *, session: Session) -> bool:
        u = self._get_config_option("usuario", "")
        s = self._get_config_option("senha", "")
        if not u:
            return True  # modo anônimo
        return bool(session.username == u and session.password == s)


def config_broker(port: int = 1883, user: str = "", senha: str = "", tls: bool = False,
                  cert: str | None = None, chave: str | None = None, porta_tls: int = 8883, bind: str = "0.0.0.0") -> dict:
    listeners = {"default": {"type": "tcp", "bind": f"{bind}:{port}"}}
    if tls:
        listeners["tls"] = {"type": "tcp", "bind": f"{bind}:{porta_tls}", "ssl": True, "certfile": cert, "keyfile": chave}
    return {
        "listeners": listeners,
        "timeout_disconnect_delay": 2,
        "plugins": {"broker.UsuarioSenhaPlugin": {"usuario": user, "senha": senha}},
    }


async def rodar(cfg: dict):
    broker = Broker(cfg)
    await broker.start()
    try:
        while True:
            await asyncio.sleep(3600)
    finally:
        await broker.shutdown()


def main():
    ap = argparse.ArgumentParser(description="Broker MQTT local SuperviLab")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--user", default=os.environ.get("MQTT_USER", ""))
    ap.add_argument("--senha", default=os.environ.get("MQTT_SENHA", ""))
    ap.add_argument("--tls", action="store_true")
    ap.add_argument("--cert")
    ap.add_argument("--chave")
    ap.add_argument("--porta-tls", type=int, default=8883)
    a = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        asyncio.run(rodar(config_broker(a.port, a.user, a.senha, a.tls, a.cert, a.chave, a.porta_tls, a.bind)))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
