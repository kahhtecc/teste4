import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from planta import Planta, Valvula, ABERTA, FECHADA, ABRINDO, FECHANDO, LIGADA, DESLIGADA, CURSO_VALVULA_S


def avancar(p, segundos, dt=0.1):
    n = int(round(segundos / dt))
    for _ in range(n):
        p.avancar(dt)


def test_estado_inicial():
    p = Planta()
    assert all(v.estado == FECHADA for v in p.valvulas.values())
    assert p.bomba == DESLIGADA
    assert p.tanques[1].nivel_pct == 70.0 and p.tanques[2].nivel_pct == 20.0


def test_valvula_abre_com_curso():
    p = Planta()
    ok, motivo = p.comandar_valvula(1, "abrir")
    assert ok and motivo == ""
    assert p.valvulas[1].estado == ABRINDO
    avancar(p, CURSO_VALVULA_S - 0.2)
    assert p.valvulas[1].estado == ABRINDO
    avancar(p, 0.3)
    assert p.valvulas[1].estado == ABERTA


def test_valvula_fecha_durante_abertura():
    p = Planta()
    p.comandar_valvula(2, "abrir"); avancar(p, 1.0)
    ok, _ = p.comandar_valvula(2, "fechar")
    assert ok and p.valvulas[2].estado == FECHANDO
    avancar(p, 3.1)
    assert p.valvulas[2].estado == FECHADA


def test_comando_invalido_e_equipamento_inexistente():
    p = Planta()
    assert p.comandar_valvula(1, "explodir") == (False, "cmd_invalido")
    assert p.comandar_valvula(9, "abrir") == (False, "equipamento_desconhecido")
    assert p.comandar_bomba("x") == (False, "cmd_invalido")


def test_bomba_nao_liga_com_valvula1_fechada():
    p = Planta()
    assert p.comandar_bomba("ligar") == (False, "valvula_1_fechada")
    assert p.bomba == DESLIGADA


def test_bomba_transfere_do_tanque1_para_2():
    p = Planta()
    p.comandar_valvula(1, "abrir"); avancar(p, 3.1)
    assert p.comandar_bomba("ligar") == (True, "")
    v1, v2 = p.tanques[1].volume_l, p.tanques[2].volume_l
    avancar(p, 60.0)  # 1 minuto a 60 L/min
    assert abs((v1 - p.tanques[1].volume_l) - 60.0) < 0.5
    assert abs((p.tanques[2].volume_l - v2) - 60.0) < 0.5


def test_bomba_desliga_ao_fechar_valvula1():
    p = Planta()
    p.comandar_valvula(1, "abrir"); avancar(p, 3.1)
    p.comandar_bomba("ligar")
    p.comandar_valvula(1, "fechar"); avancar(p, 3.1)
    assert p.bomba == DESLIGADA
    assert "bomba_desligada_intertravamento" in p.eventos


def test_bomba_desliga_com_tanque1_vazio():
    p = Planta()
    p.tanques[1].volume_l = 15.0  # 1,5 %
    p.comandar_valvula(1, "abrir"); avancar(p, 3.1)
    assert p.comandar_bomba("ligar") == (False, "tanque_1_vazio")
    p.tanques[1].volume_l = 25.0
    assert p.comandar_bomba("ligar") == (True, "")
    avancar(p, 10)  # consome ~10 L → cai abaixo de 2 %
    assert p.bomba == DESLIGADA


def test_rede_enche_tanque1_e_dreno_esvazia_tanque2():
    p = Planta()
    p.comandar_valvula(3, "abrir"); p.comandar_valvula(2, "abrir"); avancar(p, 3.1)
    v1, v2 = p.tanques[1].volume_l, p.tanques[2].volume_l
    avancar(p, 60.0)
    assert abs((p.tanques[1].volume_l - v1) - 30.0) < 0.5
    assert abs((v2 - p.tanques[2].volume_l) - 40.0) < 0.5


def test_tanque_nao_passa_da_capacidade_nem_fica_negativo():
    p = Planta()
    p.tanques[1].volume_l = 995.0; p.tanques[2].volume_l = 3.0
    p.comandar_valvula(3, "abrir"); p.comandar_valvula(2, "abrir"); avancar(p, 3.1)
    avancar(p, 60.0)
    assert p.tanques[1].volume_l == 1000.0
    assert p.tanques[2].volume_l == 0.0


def test_sensor_travado_mantem_leitura_mas_fisica_continua():
    p = Planta()
    p.comandar_valvula(3, "abrir"); avancar(p, 3.1)
    p.tanques[1].sensor_travado = True
    leitura0 = p.tanques[1].leitura()
    avancar(p, 30)
    leitura1 = p.tanques[1].leitura()
    assert leitura0["nivel_pct"] == leitura1["nivel_pct"] and leitura1["qualidade"] == "boa"
    assert p.tanques[1].volume_l > 700.0 + 10
    p.tanques[1].sensor_travado = False
    assert p.tanques[1].leitura()["nivel_pct"] > leitura1["nivel_pct"]


def test_sensor_invalido_leva_a_modo_seguro():
    p = Planta()
    p.comandar_valvula(1, "abrir"); avancar(p, 3.1); p.comandar_bomba("ligar")
    p.tanques[2].sensor_invalido = True
    avancar(p, 0.1)
    assert p.modo_seguro and p.bomba == DESLIGADA
    assert p.tanques[2].leitura()["qualidade"] == "invalida"
    assert p.valvulas[1].estado == FECHANDO
    avancar(p, 3.1)
    assert all(v.estado == FECHADA for v in p.valvulas.values())
    assert p.comandar_valvula(1, "abrir") == (False, "modo_seguro")
    assert p.comandar_bomba("ligar") == (False, "modo_seguro")


def test_modo_seguro_fecha_ate_valvula_sem_resposta():
    p = Planta()
    p.comandar_valvula(2, "abrir"); avancar(p, 3.1)
    p.valvulas[2].sem_resposta = True
    assert p.comandar_valvula(2, "fechar") == (False, "sem_resposta")
    p.entrar_modo_seguro("teste")
    avancar(p, 3.1)
    assert p.valvulas[2].estado == FECHADA
    assert p.valvulas[2].sem_resposta is True  # falha continua ativa depois


def test_sair_modo_seguro_nao_refaz_comandos():
    p = Planta()
    p.comandar_valvula(1, "abrir"); avancar(p, 3.1); p.comandar_bomba("ligar")
    p.entrar_modo_seguro("sem_conexao"); avancar(p, 3.1)
    p.sair_modo_seguro()
    assert not p.modo_seguro and p.bomba == DESLIGADA and p.valvulas[1].estado == FECHADA
    assert p.comandar_valvula(1, "abrir") == (True, "")


def test_comando_repetido_e_idempotente():
    v = Valvula()
    assert v.comandar("fechar") == (True, "ja_estava")
    v.comandar("abrir"); v.avancar(5)
    assert v.comandar("abrir") == (True, "ja_estava")
