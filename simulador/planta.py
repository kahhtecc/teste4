"""Modelo físico puro da planta (sem MQTT). Testável isoladamente.

Topologia:
  rede --[V3]--> Tanque 1 --[Bomba]--[V1]--> Tanque 2 --[V2]--> dreno
"""
from __future__ import annotations

from dataclasses import dataclass, field

ABERTA, FECHADA, ABRINDO, FECHANDO, FALHA = "aberta", "fechada", "abrindo", "fechando", "falha"
LIGADA, DESLIGADA = "ligada", "desligada"

CAPACIDADE_L = 1000.0
VAZAO_BOMBA_LMIN = 60.0
VAZAO_REDE_LMIN = 30.0
VAZAO_DRENO_LMIN = 40.0
CURSO_VALVULA_S = 3.0
NIVEL_MIN_BOMBA_PCT = 2.0


@dataclass
class Valvula:
    estado: str = FECHADA
    alvo: str = FECHADA
    restante_s: float = 0.0
    sem_resposta: bool = False  # falha simulável: ignora comandos

    def comandar(self, cmd: str) -> tuple[bool, str]:
        if self.sem_resposta:
            return False, "sem_resposta"
        if cmd not in ("abrir", "fechar"):
            return False, "cmd_invalido"
        novo = ABERTA if cmd == "abrir" else FECHADA
        if self.estado == novo and self.alvo == novo:
            return True, "ja_estava"
        self.alvo = novo
        self.estado = ABRINDO if novo == ABERTA else FECHANDO
        self.restante_s = CURSO_VALVULA_S
        return True, ""

    def avancar(self, dt: float) -> bool:
        """Retorna True se o estado final mudou neste passo."""
        if self.estado in (ABRINDO, FECHANDO):
            self.restante_s -= dt
            if self.restante_s <= 0:
                self.estado = self.alvo
                self.restante_s = 0.0
                return True
        return False

    @property
    def aberta(self) -> bool:
        return self.estado == ABERTA


@dataclass
class Tanque:
    volume_l: float = 500.0
    capacidade_l: float = CAPACIDADE_L
    sensor_travado: bool = False
    sensor_invalido: bool = False
    _leitura_travada: float | None = field(default=None, repr=False)

    @property
    def nivel_pct(self) -> float:
        return round(100.0 * self.volume_l / self.capacidade_l, 1)

    def leitura(self) -> dict:
        """Leitura do sensor (pode estar travado ou inválido)."""
        if self.sensor_invalido:
            return {"nivel_pct": None, "volume_l": None, "qualidade": "invalida"}
        if self.sensor_travado:
            if self._leitura_travada is None:
                self._leitura_travada = self.volume_l
            v = self._leitura_travada
        else:
            self._leitura_travada = None
            v = self.volume_l
        return {"nivel_pct": round(100.0 * v / self.capacidade_l, 1), "volume_l": round(v, 1), "qualidade": "boa"}


@dataclass
class Planta:
    valvulas: dict[int, Valvula] = field(default_factory=lambda: {1: Valvula(), 2: Valvula(), 3: Valvula()})
    bomba: str = DESLIGADA
    tanques: dict[int, Tanque] = field(default_factory=lambda: {1: Tanque(700.0), 2: Tanque(200.0)})
    modo_seguro: bool = False
    tempo_s: float = 0.0
    eventos: list[str] = field(default_factory=list)

    # ---------- comandos ----------
    def comandar_valvula(self, n: int, cmd: str) -> tuple[bool, str]:
        if n not in self.valvulas:
            return False, "equipamento_desconhecido"
        if self.modo_seguro:
            return False, "modo_seguro"
        return self.valvulas[n].comandar(cmd)

    def comandar_bomba(self, cmd: str) -> tuple[bool, str]:
        if self.modo_seguro:
            return False, "modo_seguro"
        if cmd == "ligar":
            if not self.valvulas[1].aberta:
                return False, "valvula_1_fechada"
            if self.tanques[1].nivel_pct < NIVEL_MIN_BOMBA_PCT:
                return False, "tanque_1_vazio"
            self.bomba = LIGADA
            return True, ""
        if cmd == "desligar":
            self.bomba = DESLIGADA
            return True, ""
        return False, "cmd_invalido"

    # ---------- segurança ----------
    def entrar_modo_seguro(self, motivo: str) -> None:
        if self.modo_seguro:
            return
        self.modo_seguro = True
        self.bomba = DESLIGADA
        for v in self.valvulas.values():
            v.sem_resposta_backup = v.sem_resposta
            v.sem_resposta = False
            v.comandar("fechar")
            v.sem_resposta = v.sem_resposta_backup
        self.eventos.append(f"modo_seguro:{motivo}")

    def sair_modo_seguro(self) -> None:
        if self.modo_seguro:
            self.modo_seguro = False
            self.eventos.append("modo_normal")

    # ---------- física ----------
    def avancar(self, dt: float) -> dict:
        """Avança dt segundos. Retorna quais estados finais mudaram."""
        mudou = {"valvulas": [], "bomba": False}
        self.tempo_s += dt
        for n, v in self.valvulas.items():
            if v.avancar(dt):
                mudou["valvulas"].append(n)

        t1, t2 = self.tanques[1], self.tanques[2]
        # alimentação da rede
        if self.valvulas[3].aberta:
            t1.volume_l += VAZAO_REDE_LMIN / 60.0 * dt
        # bomba: intertravamentos
        if self.bomba == LIGADA:
            if not self.valvulas[1].aberta or t1.nivel_pct < NIVEL_MIN_BOMBA_PCT:
                self.bomba = DESLIGADA
                mudou["bomba"] = True
                self.eventos.append("bomba_desligada_intertravamento")
            else:
                q = min(VAZAO_BOMBA_LMIN / 60.0 * dt, t1.volume_l)
                t1.volume_l -= q
                t2.volume_l += q
        # dreno
        if self.valvulas[2].aberta:
            t2.volume_l -= min(VAZAO_DRENO_LMIN / 60.0 * dt, t2.volume_l)
        # limites físicos (transbordo vai para o chão...)
        for t in (t1, t2):
            t.volume_l = max(0.0, min(t.capacidade_l, t.volume_l))
        # sensor inválido → modo seguro
        if any(t.sensor_invalido for t in self.tanques.values()):
            self.entrar_modo_seguro("sensor_invalido")
        return mudou
