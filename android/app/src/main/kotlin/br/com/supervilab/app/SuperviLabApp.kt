package br.com.supervilab.app

import android.app.Application
import android.content.Context
import br.com.supervilab.core.Ajustes
import br.com.supervilab.core.ConexaoDemo
import br.com.supervilab.core.ConexaoMqtt
import br.com.supervilab.core.ConfigConexao
import br.com.supervilab.core.PlantaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SuperviLabApp : Application() {
    lateinit var store: PlantaStore
        private set

    override fun onCreate() {
        super.onCreate()
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = PlantaStore(escopo, AjustesAndroid(this), { cfg -> if (cfg.modoDemo) ConexaoDemo() else ConexaoMqtt(cfg) })
    }

    companion object {
        fun de(ctx: Context) = ctx.applicationContext as SuperviLabApp
    }
}

/** Preferências privadas do app (fora do código e fora do backup). */
class AjustesAndroid(ctx: Context) : Ajustes {
    private val prefs = ctx.getSharedPreferences("conexao", Context.MODE_PRIVATE)
    override fun carregar() = ConfigConexao(
        host = prefs.getString("host", "") ?: "",
        porta = prefs.getInt("porta", 8883),
        usuario = prefs.getString("usuario", "") ?: "",
        senha = prefs.getString("senha", "") ?: "",
        tls = prefs.getBoolean("tls", true),
        caPem = prefs.getString("caPem", "") ?: "",
        verificarNomeHost = prefs.getBoolean("verificarNomeHost", true),
        clienteId = prefs.getString("clienteId", "supervilab-app") ?: "supervilab-app",
        modoDemo = prefs.getBoolean("modoDemo", true),
    )
    override fun salvar(c: ConfigConexao) {
        prefs.edit().putString("host", c.host).putInt("porta", c.porta).putString("usuario", c.usuario).putString("senha", c.senha)
            .putBoolean("tls", c.tls).putString("caPem", c.caPem).putBoolean("verificarNomeHost", c.verificarNomeHost)
            .putString("clienteId", c.clienteId).putBoolean("modoDemo", c.modoDemo).apply()
    }
}
