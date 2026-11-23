package com.vortex.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vortex.BuildConfig
import com.vortex.data.*
import com.vortex.model.Spec
import com.vortex.model.Specs
import com.vortex.net.Link
import com.vortex.net.ProjectSync
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * State of one open project. Three different numbers are kept apart on purpose:
 * logged (written down by the operator from the real mixer), ask (requested by the controller)
 * and applied (an ask the operator accepted, which then becomes the new logged value).
 *
 * The operator phone is the source of truth. The controller sends requests, the operator applies them
 * and sends the result back, so both phones end up with the same project.
 */
class ProjectViewModel(app: Application, private val pid: Long) : AndroidViewModel(app), ProjectSync {
    private val dao = AppDatabase.get(app).projects()
    val controller = BuildConfig.ROLE == "CONTROLLER"

    data class V(val logged: Double? = null, val ask: Double? = null)

    val vals = mutableStateMapOf<String, V>()
    val messages = mutableStateListOf<MessageEntity>()
    val vocals = mutableStateListOf<VocalLineEntity>()
    private var lastLive = 0L

    init {
        viewModelScope.launch {
            dao.values(pid).forEach { vals[it.controlKey] = V(it.logged, it.ask) }
            messages.addAll(dao.messages(pid))
            var v = dao.vocalLines(pid)
            if (v.isEmpty()) {
                dao.insertVocal(VocalLineEntity(projectId = pid, name = "Vocal", position = 0))
                v = dao.vocalLines(pid)
            }
            vocals.addAll(v)
            Link.attach(this@ProjectViewModel)
        }
    }

    override fun onCleared() {
        Link.detach(this)
    }

    // ------------------------------------------------------------ reading values

    /** Value drawn on the control. The controller sees their own request; the operator sees the logged value. */
    fun shown(spec: Spec): Double {
        val v = vals[spec.key]
        return if (controller && v?.ask != null) v.ask else v?.logged ?: spec.def
    }

    /** Marker for a pending request: yellow on the operator phone, white (real value) on the controller phone. */
    fun ghost(spec: Spec): Pair<Double, Boolean>? {
        val v = vals[spec.key] ?: return null
        val a = v.ask ?: return null
        if (a == v.logged) return null
        return if (controller) (v.logged ?: spec.def) to true else a to false
    }

    fun valueText(spec: Spec): Pair<String, Boolean> {
        val v = vals[spec.key]
        val a = v?.ask
        return if (controller && a != null && a != v.logged) "ask ${spec.fmt(a)}" to true else spec.fmt(v?.logged) to false
    }

    // ------------------------------------------------------------ changing values

    /** One press of a + or - button: moves by the control's own step (5 ms for delay time). */
    fun step(spec: Spec, dir: Int) {
        change(spec, shown(spec) + dir * spec.step)
        finish(spec)
    }

    fun change(spec: Spec, raw: Double) {
        val nv = spec.snap(raw)
        val cur = vals[spec.key] ?: V()
        if (controller) {
            vals[spec.key] = cur.copy(ask = nv)
            // Live movement, so the yellow marker follows the finger on the operator phone.
            val now = SystemClock.uptimeMillis()
            if (now - lastLive > 40) {
                lastLive = now
                Link.sendToServer(JSONObject().put("t", "live").put("k", spec.key).put("v", nv))
            }
        } else {
            vals[spec.key] = cur.copy(logged = nv)
        }
    }

    /** Called when the finger lifts: save the value; controller sends one request, operator informs the controller. */
    fun finish(spec: Spec) {
        val cur = vals[spec.key] ?: return
        viewModelScope.launch {
            dao.upsertValue(ValueEntity(pid, spec.key, cur.logged, cur.ask))
            if (controller) {
                val a = cur.ask
                if (a != null && a != cur.logged) {
                    val text = "${spec.title}: ${spec.fmt(cur.logged)} → ${spec.fmt(a)}"
                    sendToOperator(JSONObject().put("t", "ask").put("k", spec.key).put("v", a)
                        .put("section", spec.section).put("text", text))
                }
            } else {
                Link.broadcast(valJson(spec.key))
            }
        }
    }

    /** Operator confirms the change was really made on the mixer. */
    fun accept(m: MessageEntity) {
        if (controller || m.status != "PENDING") return
        val key = m.controlKey ?: return
        viewModelScope.launch {
            val cur = vals[key] ?: V()
            val nv = V(logged = cur.ask ?: cur.logged, ask = null)
            vals[key] = nv
            dao.upsertValue(ValueEntity(pid, key, nv.logged, null))
            val u = m.copy(status = "APPLIED")
            dao.updateMessage(u)
            val i = messages.indexOfFirst { it.id == m.id }
            if (i >= 0) messages[i] = u
            Link.broadcast(valJson(key))
            Link.broadcast(msgJson(u))
        }
    }

    fun sendText(t: String) {
        val text = t.trim()
        if (text.isEmpty()) return
        if (controller) sendToOperator(JSONObject().put("t", "text").put("text", text))
        else viewModelScope.launch { addText(text, "operator") }
    }

    fun finishSetup() {
        viewModelScope.launch { dao.markSetup(pid) }
    }

    // ------------------------------------------------------------ vocal lines

    fun addVocal() {
        if (controller) sendToOperator(JSONObject().put("t", "vocal").put("op", "add"))
        else viewModelScope.launch { addVocalLocal() }
    }

    fun renameVocal(l: VocalLineEntity, name: String) {
        if (name.isBlank()) return
        if (controller) sendToOperator(JSONObject().put("t", "vocal").put("op", "rename").put("id", l.id).put("name", name.trim()))
        else viewModelScope.launch { renameLocal(l, name) }
    }

    fun removeVocal(l: VocalLineEntity) {
        if (vocals.size <= 1) return
        if (controller) sendToOperator(JSONObject().put("t", "vocal").put("op", "remove").put("id", l.id))
        else viewModelScope.launch { removeLocal(l) }
    }

    private suspend fun addVocalLocal() {
        val e = VocalLineEntity(projectId = pid, name = "Vocal ${vocals.size + 1}", position = vocals.size)
        vocals.add(e.copy(id = dao.insertVocal(e)))
        Link.broadcast(vocalsJson())
    }

    private suspend fun renameLocal(l: VocalLineEntity, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val u = l.copy(name = n)
        dao.updateVocal(u)
        val i = vocals.indexOfFirst { it.id == l.id }
        if (i >= 0) vocals[i] = u
        Link.broadcast(vocalsJson())
    }

    private suspend fun removeLocal(l: VocalLineEntity) {
        if (vocals.size <= 1) return
        dao.deleteVocal(l.id)
        val keys = listOf("voc.${l.id}") + Specs.eqSuffixes.map { "eq.${l.id}.$it" }
        keys.forEach { dao.deleteValue(pid, it); vals.remove(it) }
        vocals.removeAll { it.id == l.id }
        Link.broadcast(vocalsJson())
    }

    // ------------------------------------------------------------ network (ProjectSync)

    /** Controller to operator. If the link is down the request is saved and sent later. */
    private fun sendToOperator(j: JSONObject) {
        if (!Link.sendToServer(j)) viewModelScope.launch { dao.insertOutbox(OutboxEntity(projectId = pid, payload = j.toString())) }
    }

    override fun onConnected() {
        if (!controller) return
        viewModelScope.launch {
            for (o in dao.outbox(pid)) {
                if (!Link.sendToServer(JSONObject(o.payload))) return@launch
                dao.deleteOutbox(o.id)
            }
            Link.sendToServer(JSONObject().put("t", "sync"))
        }
    }

    override fun onRemote(m: JSONObject) {
        viewModelScope.launch {
            try {
                if (controller) handleAsController(m) else handleAsOperator(m)
            } catch (e: Exception) {
                // a malformed message must never crash the app
            }
        }
    }

    override fun snapshot(): JSONObject {
        val vo = JSONObject()
        vals.toMap().forEach { (k, v) ->
            vo.put(k, JSONObject().put("l", v.logged ?: JSONObject.NULL).put("a", v.ask ?: JSONObject.NULL))
        }
        val ms = JSONArray()
        messages.toList().forEach { ms.put(msgJson(it).getJSONObject("m")) }
        return JSONObject().put("t", "snap").put("vals", vo).put("vocals", vocalsJson().getJSONArray("v")).put("msgs", ms)
    }

    private suspend fun handleAsOperator(m: JSONObject) {
        when (m.optString("t")) {
            "live" -> {
                val k = m.getString("k")
                vals[k] = (vals[k] ?: V()).copy(ask = m.getDouble("v"))
            }
            "ask" -> {
                val k = m.getString("k")
                val cur = (vals[k] ?: V()).copy(ask = m.getDouble("v"))
                vals[k] = cur
                dao.upsertValue(ValueEntity(pid, k, cur.logged, cur.ask))
                upsertPending(k, m.optString("section"), m.optString("text"))
                Link.broadcast(valJson(k))
            }
            "text" -> addText(m.optString("text"), "controller")
            "vocal" -> when (m.optString("op")) {
                "add" -> addVocalLocal()
                "rename" -> vocals.firstOrNull { it.id == m.getLong("id") }?.let { renameLocal(it, m.getString("name")) }
                "remove" -> vocals.firstOrNull { it.id == m.getLong("id") }?.let { removeLocal(it) }
            }
        }
    }

    private suspend fun handleAsController(m: JSONObject) {
        when (m.optString("t")) {
            "snap" -> applySnapshot(m)
            "val" -> {
                val k = m.getString("k")
                val v = V(jd(m, "l"), jd(m, "a"))
                vals[k] = v
                dao.upsertValue(ValueEntity(pid, k, v.logged, v.ask))
            }
            "msg" -> {
                val msg = parseMsg(m.getJSONObject("m"))
                dao.insertMessage(msg)
                val i = messages.indexOfFirst { it.id == msg.id }
                if (i >= 0) messages[i] = msg else messages.add(msg)
            }
            "vocals" -> applyVocals(m.getJSONArray("v"))
        }
    }

    private suspend fun applySnapshot(m: JSONObject) {
        dao.deleteValues(pid); dao.deleteMessages(pid)
        vals.clear(); messages.clear()
        val vo = m.getJSONObject("vals")
        vo.keys().forEach { k ->
            val o = vo.getJSONObject(k)
            val v = V(jd(o, "l"), jd(o, "a"))
            vals[k] = v
            dao.upsertValue(ValueEntity(pid, k, v.logged, v.ask))
        }
        val ms = m.getJSONArray("msgs")
        for (i in 0 until ms.length()) {
            val msg = parseMsg(ms.getJSONObject(i))
            dao.insertMessage(msg)
            messages.add(msg)
        }
        applyVocals(m.getJSONArray("vocals"))
    }

    private suspend fun applyVocals(a: JSONArray) {
        val list = ArrayList<VocalLineEntity>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            list.add(VocalLineEntity(id = o.getLong("id"), projectId = pid, name = o.getString("n"), position = o.getInt("p")))
        }
        if (list.isEmpty()) return
        dao.deleteVocals(pid)
        list.forEach { dao.insertVocal(it) }
        vocals.clear(); vocals.addAll(list)
    }

    private suspend fun upsertPending(key: String, section: String, text: String) {
        val i = messages.indexOfFirst { it.controlKey == key && it.status == "PENDING" }
        if (i >= 0) {
            val u = messages[i].copy(text = text)
            dao.updateMessage(u); messages[i] = u
            Link.broadcast(msgJson(u))
        } else {
            val m0 = MessageEntity(projectId = pid, controlKey = key, section = section, text = text,
                status = "PENDING", sender = "controller", time = System.currentTimeMillis())
            val m = m0.copy(id = dao.insertMessage(m0))
            messages.add(m)
            Link.broadcast(msgJson(m))
        }
    }

    private suspend fun addText(text: String, sender: String) {
        if (text.isBlank()) return
        val m0 = MessageEntity(projectId = pid, controlKey = null, section = "Text", text = text,
            status = "TEXT", sender = sender, time = System.currentTimeMillis())
        val m = m0.copy(id = dao.insertMessage(m0))
        messages.add(m)
        Link.broadcast(msgJson(m))
    }

    // ------------------------------------------------------------ JSON helpers

    private fun jd(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.getDouble(k)

    private fun valJson(k: String): JSONObject {
        val v = vals[k]
        return JSONObject().put("t", "val").put("k", k).put("l", v?.logged ?: JSONObject.NULL).put("a", v?.ask ?: JSONObject.NULL)
    }

    private fun msgJson(m: MessageEntity): JSONObject = JSONObject().put("t", "msg").put(
        "m", JSONObject().put("id", m.id).put("k", m.controlKey ?: JSONObject.NULL).put("sec", m.section)
            .put("tx", m.text).put("st", m.status).put("by", m.sender).put("time", m.time),
    )

    private fun parseMsg(o: JSONObject) = MessageEntity(
        id = o.getLong("id"), projectId = pid, controlKey = if (o.isNull("k")) null else o.getString("k"),
        section = o.getString("sec"), text = o.getString("tx"), status = o.getString("st"),
        sender = o.getString("by"), time = o.getLong("time"),
    )

    private fun vocalsJson(): JSONObject {
        val a = JSONArray()
        vocals.toList().forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("p", it.position)) }
        return JSONObject().put("t", "vocals").put("v", a)
    }

    companion object {
        fun factory(app: Application, pid: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProjectViewModel(app, pid) as T
        }
    }
}
