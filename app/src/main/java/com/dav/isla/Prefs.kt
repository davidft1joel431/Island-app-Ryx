package com.dav.isla

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("isla", Context.MODE_PRIVATE)

    /**
     * El historial se escribe desde el servicio de notificaciones, el de accesibilidad y la app a la vez.
     * SharedPreferences no hace atómica la secuencia leer-modificar-escribir, así que dos avisos simultáneos
     * podían perder una entrada. Todo el acceso al historial pasa por este cerrojo.
     */
    private val histLock = Any()

    /** Origen interno de una entrada: una notificación normal o algo que se vio en pantalla. */
    const val SRC_APP = "app"
    const val SRC_SCREEN = "screen"

    fun durationSeconds(c: Context): Int = sp(c).getInt("duration_s", 5)
    fun durationMs(c: Context): Long = durationSeconds(c) * 1000L
    fun setDurationSeconds(c: Context, s: Int) = sp(c).edit().putInt("duration_s", s).apply()



    fun permanent(c: Context): Boolean = sp(c).getBoolean("permanent", false)
    fun setPermanent(c: Context, v: Boolean) = sp(c).edit().putBoolean("permanent", v).apply()

    fun hideHeadsUp(c: Context): Boolean = sp(c).getBoolean("hide_headsup", false)
    fun setHideHeadsUp(c: Context, v: Boolean) = sp(c).edit().putBoolean("hide_headsup", v).apply()

    fun style(c: Context): String = sp(c).getString("style", "hyperos") ?: "hyperos"
    fun setStyle(c: Context, v: String) = sp(c).edit().putString("style", v).apply()

    fun yOffsetDp(c: Context): Int = sp(c).getInt("y_dp", 8)
    fun setYOffsetDp(c: Context, v: Int) = sp(c).edit().putInt("y_dp", v).apply()

    fun idleWidthDp(c: Context): Int = sp(c).getInt("idle_w", IslandGeometry.COLLAPSED_WIDTH_DP)
    fun setIdleWidthDp(c: Context, v: Int) = sp(c).edit().putInt("idle_w", v).apply()

    /**
     * Migración de la geometría anterior (96 dp) a la base de Ryx 0.9.50:
     * 160 × 32 dp contraída y 350 × 74 dp expandida. Solo se toca el 96 de fábrica;
     * si el usuario había elegido otro ancho, se conserva.
     */
    fun migrateGeometry(c: Context) {
        val s = sp(c)
        if (s.getInt("geom_v", 0) >= 1) return
        val e = s.edit()
        if (s.getInt("idle_w", 96) == 96) e.putInt("idle_w", IslandGeometry.COLLAPSED_WIDTH_DP)
        e.putInt("geom_v", 1)
        e.apply()
    }

    fun lastLog(c: Context): String = sp(c).getString("last_log", "") ?: ""
    fun setLastLog(c: Context, s: String) = sp(c).edit().putString("last_log", s.takeLast(8000)).apply()

    // ---- Avisos de conectividad de la isla ----
    // 0 = avisar siempre (como al principio) · 1 = solo cuando cambia de verdad la red (por defecto) · 2 = sin avisos
    fun netMode(c: Context): Int = sp(c).getInt("net_mode", 1)
    fun setNetMode(c: Context, v: Int) = sp(c).edit().putInt("net_mode", v).apply()
    /** Última red/transporte ya anunciado: la misma red no se vuelve a anunciar aunque la ROM revalide la conexión. */
    fun netKey(c: Context): String = sp(c).getString("net_key", "") ?: ""
    fun setNetKey(c: Context, v: String) = sp(c).edit().putString("net_key", v).apply()
    fun netAt(c: Context): Long = sp(c).getLong("net_at", 0L)
    fun setNetAt(c: Context, v: Long) = sp(c).edit().putLong("net_at", v).apply()

    /** Versión de la que YA se avisó (para no repetir el aviso en cada comprobación). */
    fun updSeen(c: Context): String = sp(c).getString("upd_seen", "") ?: ""
    fun setUpdSeen(c: Context, v: String) = sp(c).edit().putString("upd_seen", v).apply()

    // ---- Eventos ----
    private fun land(c: Context) =
        c.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    fun eventOn(c: Context, id: String): Boolean = eventOnFor(c, id, land(c))
    fun eventOnFor(c: Context, id: String, landscape: Boolean): Boolean =
        sp(c).getBoolean((if (landscape) "evl_" else "ev_") + id, true)
    fun setEventOnFor(c: Context, id: String, landscape: Boolean, v: Boolean) =
        sp(c).edit().putBoolean((if (landscape) "evl_" else "ev_") + id, v).apply()
    fun eventExpand(c: Context, id: String): Boolean = sp(c).getBoolean("ex_$id", true)
    fun setEventExpand(c: Context, id: String, v: Boolean) = sp(c).edit().putBoolean("ex_$id", v).apply()

    // ---- General ----
    fun islandOn(c: Context): Boolean = sp(c).getBoolean("island_on", true)
    fun setIslandOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("island_on", v).apply()
    fun demoExpanded(c: Context): Boolean = sp(c).getBoolean("demo_exp", true)
    fun setDemoExpanded(c: Context, v: Boolean) = sp(c).edit().putBoolean("demo_exp", v).apply()
    fun speed(c: Context): Int = sp(c).getInt("speed", 1)   // 0 relajada, 1 normal, 2 ágil
    fun setSpeed(c: Context, v: Int) = sp(c).edit().putInt("speed", v).apply()
    fun heightDp(c: Context): Int = sp(c).getInt("idle_h", 32)
    fun setHeightDp(c: Context, v: Int) = sp(c).edit().putInt("idle_h", v).apply()
    fun xOffsetDp(c: Context): Int = sp(c).getInt("x_dp", 0)
    fun setXOffsetDp(c: Context, v: Int) = sp(c).edit().putInt("x_dp", v).apply()
    fun cornerDp(c: Context): Int = sp(c).getInt("corner_dp", -1)   // -1 = el del estilo (isla expandida)
    fun setCornerDp(c: Context, v: Int) = sp(c).edit().putInt("corner_dp", v).apply()
    fun idleCornerDp(c: Context): Int = sp(c).getInt("idle_corner_dp", -1)   // -1 = el del estilo (isla contraída)
    fun setIdleCornerDp(c: Context, v: Int) = sp(c).edit().putInt("idle_corner_dp", v).apply()
    fun opacity(c: Context): Int = sp(c).getInt("opacity", 100)
    fun setOpacity(c: Context, v: Int) = sp(c).edit().putInt("opacity", v).apply()
    fun shadow(c: Context): Boolean = sp(c).getBoolean("shadow", false)
    fun setShadow(c: Context, v: Boolean) = sp(c).edit().putBoolean("shadow", v).apply()
    fun tapOpens(c: Context): Boolean = sp(c).getBoolean("tap_opens", true)
    fun setTapOpens(c: Context, v: Boolean) = sp(c).edit().putBoolean("tap_opens", v).apply()
    fun vibrate(c: Context): Boolean = sp(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = sp(c).edit().putBoolean("vibrate", v).apply()
    fun lockScreen(c: Context): Boolean = sp(c).getBoolean("lock", true)
    fun setLockScreen(c: Context, v: Boolean) = sp(c).edit().putBoolean("lock", v).apply()
    fun keepHistory(c: Context): Boolean = sp(c).getBoolean("hist_on", true)
    fun setKeepHistory(c: Context, v: Boolean) = sp(c).edit().putBoolean("hist_on", v).apply()
    /** Modo máximo: activa de una vez todo lo que Android permite sin root ni Shizuku. */
    fun maxMode(c: Context): Boolean = sp(c).getBoolean("max_mode", false)
    fun setMaxMode(c: Context, v: Boolean) = sp(c).edit().putBoolean("max_mode", v).apply()
    fun history(c: Context): String = sp(c).getString("hist", "") ?: ""
    fun addHistory(c: Context, line: String) =
        sp(c).edit().putString("hist", (line + "\n" + history(c)).lines().take(50).joinToString("\n")).apply()
    fun clearHistory(c: Context) = sp(c).edit().remove("hist").apply()
    fun exempt(c: Context): Set<String> = sp(c).getStringSet("exempt", emptySet()) ?: emptySet()
    fun setExempt(c: Context, v: Set<String>) = sp(c).edit().putStringSet("exempt", v).apply()
    fun pinned(c: Context): List<String> =
        (sp(c).getString("pinned", "") ?: "").split(",").filter { it.isNotBlank() }
    fun setPinned(c: Context, v: List<String>) = sp(c).edit().putString("pinned", v.joinToString(",")).apply()
    fun allowedApps(c: Context): Set<String>? = sp(c).getStringSet("blocked", null)
    fun blocked(c: Context): Set<String> = sp(c).getStringSet("blocked", emptySet()) ?: emptySet()
    fun setBlocked(c: Context, v: Set<String>) = sp(c).edit().putStringSet("blocked", v).apply()

    // ---- Clonados conocidos (por copia, "paquete@usuario") ----
    // Se aprenden de las notificaciones y de los perfiles del sistema. Sirven para
    // que «Aplicaciones» muestre cada copia, incluidas las de un segundo espacio
    // o perfil de trabajo que no aparecen en el cajón de apps.
    fun knownClones(c: Context): Set<String> = sp(c).getStringSet("known_clones", emptySet()) ?: emptySet()
    fun addKnownClone(c: Context, key: String) = synchronized(histLock) {
        val cur = knownClones(c).toMutableSet()
        if (cur.add(key)) sp(c).edit().putStringSet("known_clones", cur).apply()
    }
    fun fontBold(c: Context): Boolean = sp(c).getBoolean("font_bold", true)
    fun setFontBold(c: Context, v: Boolean) = sp(c).edit().putBoolean("font_bold", v).apply()

    // ---- Respaldo ----
    fun exportAll(c: Context): String =
        org.json.JSONObject().also { o -> sp(c).all.forEach { (k, v) ->
            if (k != "hist" && k != "hist2" && k != "last_log" && !k.startsWith("cloud_") && !k.startsWith("upd_")
                && k != "backup_last" && k != "backup_stamp") o.put(k, if (v is Set<*>) org.json.JSONArray(v.toList()) else v) } }.toString()
    fun importAll(c: Context, json: String): Boolean = try {
        val o = org.json.JSONObject(json); val e = sp(c).edit()
        o.keys().forEach { k -> when (val v = o.get(k)) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is String -> e.putString(k, v)
            is org.json.JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
        } }
        e.apply(); true
    } catch (e: Exception) { false }


    // ---- Puente de notificaciones ----
    /** Quitar de la isla lo que ya leíste (abriste la app o se canceló la notificación). */
    fun autoRead(c: Context): Boolean = sp(c).getBoolean("auto_read", true)
    fun setAutoRead(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_read", v).apply()
    fun blockSystem(c: Context): Boolean = sp(c).getBoolean("block_sys", true)
    fun setBlockSystem(c: Context, v: Boolean) = sp(c).edit().putBoolean("block_sys", v).apply()
    fun iconMode(c: Context): Int = sp(c).getInt("icon_mode", 0)       // 0 sistema, 1 notificación, 2 monocromo
    fun setIconMode(c: Context, v: Int) = sp(c).edit().putInt("icon_mode", v).apply()
    fun iconShape(c: Context): Int = sp(c).getInt("icon_shape", 1)     // 0 círculo, 1 squircle, 2 flor, 3 hexágono
    fun setIconShape(c: Context, v: Int) = sp(c).edit().putInt("icon_shape", v).apply()
    fun smartColor(c: Context): Boolean = sp(c).getBoolean("smart_color", true)
    fun setSmartColor(c: Context, v: Boolean) = sp(c).edit().putBoolean("smart_color", v).apply()
    fun hiddenWordsRaw(c: Context): String = sp(c).getString("hidden_words", "") ?: ""
    fun setHiddenWords(c: Context, v: String) = sp(c).edit().putString("hidden_words", v).apply()
    fun hiddenWords(c: Context): List<String> =
        hiddenWordsRaw(c).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    // ---- Ventana flotante ----
    fun fwWidthPct(c: Context): Int = sp(c).getInt("fw_w", 72)
    fun setFwWidthPct(c: Context, v: Int) = sp(c).edit().putInt("fw_w", v).apply()
    fun fwHeightPct(c: Context): Int = sp(c).getInt("fw_h", 58)
    fun setFwHeightPct(c: Context, v: Int) = sp(c).edit().putInt("fw_h", v).apply()
    fun fwPos(c: Context): Int = sp(c).getInt("fw_pos", 0)             // 0 centro, 1 arriba, 2 abajo
    fun setFwPos(c: Context, v: Int) = sp(c).edit().putInt("fw_pos", v).apply()
    fun fwTestPkg(c: Context): String = sp(c).getString("fw_test", "") ?: ""
    fun setFwTestPkg(c: Context, v: String) = sp(c).edit().putString("fw_test", v).apply()

    // ---- Historial por app (JSON) con imágenes ----
    /**
     * [b64] lleva el código Base64 del texto completo (solo en mensajes cortos), [bin] el archivo con
     * los bytes UTF-8 y [b64f] el archivo con el Base64 de los mensajes largos. Son copias internas:
     * permiten reconstruir el texto aunque la vista no haya podido cargarlo y no se muestran al usuario.
     */
    data class HistItem(val pkg: String, val title: String, val text: String, val ts: Long,
                        val img: String = "", val kind: String = "",
                        val b64: String = "", val bin: String = "", val b64f: String = "",
                        val src: String = SRC_APP, val extraImgs: List<String> = emptyList()) {
        /** Todas las imágenes de la entrada: la principal y los recortes adicionales de «En pantalla». */
        fun mediaNames(): List<String> =
            (if (img.isEmpty()) emptyList() else listOf(img)) +
                extraImgs.filter { it.isNotEmpty() && it != img }
    }

    fun histDir(c: Context): java.io.File = java.io.File(c.filesDir, "hist").also { it.mkdirs() }

    private fun imgArray(names: List<String>): org.json.JSONArray =
        org.json.JSONArray().apply { names.forEach { if (it.isNotEmpty()) put(it) } }

    private fun imgList(a: org.json.JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun deleteEntryFiles(c: Context, o: org.json.JSONObject) {
        MsgBase.deleteFiles(c, o.optString("i"), o.optString("bin"), o.optString("b64f"))
        imgList(o.optJSONArray("ii")).forEach {
            try { java.io.File(histDir(c), it).delete() } catch (t: Throwable) {}
        }
    }

    /**
     * [src] distingue una notificación normal de una captura de «En pantalla».
     * [extraImgs] guarda varios recortes del mismo mensaje (una foto, un sticker y un GIF visibles a la vez).
     */
    fun addHist(c: Context, pkg: String, title: String, text: String, img: String = "", kind: String = "",
                windowMs: Long = 3000L, src: String = SRC_APP,
                extraImgs: List<String> = emptyList()) = synchronized(histLock) {
        val source = if (src == SRC_SCREEN) SRC_SCREEN else SRC_APP
        val extra = extraImgs.filter { it.isNotEmpty() && it != img }.distinct()
        val old = try { org.json.JSONArray(sp(c).getString("hist2", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        val now = System.currentTimeMillis()
        // ¿Ya está registrado lo mismo hace poco? Se revisan las últimas entradas (no solo la primera):
        // así los avisos del sistema, como la batería, no se duplican aunque llegue otra cosa entre medias.
        val scan = minOf(old.length(), 40)
        for (i in 0 until scan) {
            val o = old.optJSONObject(i) ?: continue
            // Notificación y captura de pantalla pueden tener el mismo texto: no se mezclan.
            if (o.optString("p") == pkg && o.optString("t") == title && o.optString("x") == text &&
                o.optString("s", SRC_APP) == source && now - o.optLong("ts") < windowMs) {
                // Algunas apps publican primero el texto y después actualizan el aviso con la foto/sticker.
                // Ese segundo aviso tiene el mismo título/texto; antes se descartaba y la imagen se perdía.
                // Si la entrada guardada aún no tiene imagen, se completa con la nueva.
                val prev = o.optString("i")
                val prevExtra = imgList(o.optJSONArray("ii"))
                // Las entradas antiguas (anteriores a esta versión) no tenían códigos: se completan ahora.
                var changed = false
                if (text.isNotEmpty() && o.optString("b64").isEmpty() &&
                    o.optString("bin").isEmpty() && o.optString("b64f").isEmpty()) {
                    val code = MsgBase.write(c, now, text)
                    o.put("b64", code.b64).put("bin", code.bin).put("b64f", code.b64f)
                    changed = true
                }
                if (img.isEmpty() && extra.isEmpty()) {
                    if (changed) sp(c).edit().putString("hist2", old.toString()).apply()
                    return@synchronized
                }
                if (prev.isEmpty() && img.isNotEmpty()) {
                    o.put("i", img).put("k", kind).put("ts", now)
                    changed = true
                } else if (img.isNotEmpty() && prev != img) {
                    val oldKind = o.optString("k")
                    val oldAnimated = oldKind.contains("GIF", true) || oldKind.contains("Sticker", true) ||
                        oldKind.contains("Animación", true)
                    val newAnimated = kind.contains("GIF", true) || kind.contains("Sticker", true) ||
                        kind.contains("Animación", true)
                    // Si la primera publicación trajo un fotograma estático y la segunda el sticker/GIF
                    // animado, se queda la copia animada. Si solo es el mismo aviso repetido, se descarta
                    // el archivo nuevo para no duplicar espacio.
                    if (!oldAnimated && newAnimated) {
                        if (prev != img) java.io.File(histDir(c), prev).delete()
                        o.put("i", img).put("k", kind).put("ts", now)
                        changed = true
                    } else {
                        java.io.File(histDir(c), img).delete()
                    }
                }
                // Los recortes de una misma pantalla se acumulan; si el mensaje vuelve a aparecer, la
                // misma imagen no se guarda dos veces.
                val merged = LinkedHashSet<String>()
                prevExtra.forEach { if (it != prev && it != img) merged.add(it) }
                extra.forEach { if (it != prev && it != img) merged.add(it) }
                if (merged.toList() != prevExtra) {
                    o.put("ii", imgArray(merged.toList()))
                    changed = true
                }
                if (changed) {
                    o.put("s", source)
                    sp(c).edit().putString("hist2", old.toString()).apply()
                }
                return@synchronized
            }
        }
        // Cada mensaje nuevo guarda también su binario y su Base64 como respaldo interno. Si el texto
        // normal falla al cargar, el Historial y la isla reconstruyen el mensaje completo desde estas copias.
        val code = MsgBase.write(c, now, text)
        val out = org.json.JSONArray()
        out.put(org.json.JSONObject().put("p", pkg).put("t", title).put("x", text).put("ts", now)
            .put("i", img).put("k", kind).put("s", source).put("ii", imgArray(extra))
            .put("b64", code.b64).put("bin", code.bin).put("b64f", code.b64f))
        for (i in 0 until minOf(old.length(), 599)) out.put(old.get(i))
        // Las entradas que se caen de la lista se llevan su imagen y sus códigos.
        for (i in 599 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            deleteEntryFiles(c, o)
        }
        sp(c).edit().putString("hist2", out.toString()).apply()
    }
    fun histAll(c: Context): List<HistItem> = synchronized(histLock) {
        try {
            val a = org.json.JSONArray(sp(c).getString("hist2", "[]"))
            (0 until a.length()).mapNotNull {
                val o = a.getJSONObject(it)
                val p = o.optString("p")
                val t = o.optString("t")
                // Los eventos internos de batería que guardaban versiones anteriores no son
                // notificaciones: se ocultan también mientras termina la limpieza en disco.
                if (p == "android" && t == "Batería") return@mapNotNull null
                HistItem(p, t, o.optString("x"), o.optLong("ts"),
                    o.optString("i"), o.optString("k"), o.optString("b64"), o.optString("bin"),
                    o.optString("b64f"), o.optString("s", SRC_APP), imgList(o.optJSONArray("ii")))
            }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Completa los códigos binario/Base64 de las entradas antiguas. Se ejecuta en segundo plano una sola
     * vez; las entradas nuevas ya nacen con ellos en [addHist].
     */
    @Suppress("ApplySharedPref")
    fun migrateCodes(c: Context, force: Boolean = false) {
        synchronized(histLock) {
            if (!force && sp(c).getInt("code_v", 0) >= 3) return
            val old = try { org.json.JSONArray(sp(c).getString("hist2", "[]")) } catch (e: Exception) { org.json.JSONArray() }
            val arr = org.json.JSONArray()
            var changed = false
            for (i in 0 until old.length()) {
                val o = old.optJSONObject(i) ?: continue
                val pkg = o.optString("p")
                val oldTitle = o.optString("t")
                val oldText = o.optString("x")
                val oldKind = o.optString("k")
                val wasScreen = oldKind == "Pantalla" || oldTitle.startsWith("Chat en pantalla")
                // 1) Fuera los eventos internos de batería: no eran notificaciones ni mensajes.
                if (pkg == "android" && oldTitle == "Batería") {
                    deleteEntryFiles(c, o)
                    changed = true
                    continue
                }
                // 2) Las capturas de pantalla antiguas se muestran como mensaje visto, no como un log:
                //    sin «Chat en pantalla · », sin «(pantalla · app)» y sin el tipo «Pantalla».
                var title = oldTitle
                if (title.startsWith("Chat en pantalla · ")) {
                    title = title.removePrefix("Chat en pantalla · ").trim()
                    changed = true
                }
                val marker = Regex("""\s*\(pantalla\s*·[^)]*\)""")
                var text = oldText.replace(marker, "").trim()
                if (text != oldText) changed = true
                var kind = oldKind
                if (kind == "Pantalla") { kind = ""; changed = true }
                // Si el texto cambió, los códigos binario/Base64 antiguos contenían la marca: se
                // regeneran para que el detalle tampoco la vuelva a mostrar.
                if (text != oldText) {
                    MsgBase.deleteFiles(c, "", o.optString("bin"), o.optString("b64f"))
                    val code = MsgBase.write(c, o.optLong("ts", System.currentTimeMillis()), text)
                    o.put("b64", code.b64).put("bin", code.bin).put("b64f", code.b64f)
                }
                o.put("t", title).put("x", text).put("k", kind)
                // 3) Origen de la entrada. Las capturas antiguas suelen llevar como título el nombre
                // exacto de la app de chat y un texto de varias líneas; las notificaciones normales
                // llevan el nombre del contacto o del grupo. Así se trasladan a «En pantalla» sin
                // inventar contenido.
                var source = o.optString("s", "")
                if (source.isEmpty()) {
                    val base = Clones.splitKey(pkg).first
                    val appName = try {
                        c.packageManager.getApplicationLabel(
                            c.packageManager.getApplicationInfo(base, 0)).toString()
                    } catch (t: Throwable) { "" }
                    val inferred = !Sys.isSystemPkg(pkg) && Sys.isChatApp(base) &&
                        text.contains('\n') && appName.isNotEmpty() &&
                        title.trim().equals(appName, ignoreCase = true)
                    source = if (wasScreen || inferred) SRC_SCREEN else SRC_APP
                    changed = true
                }
                o.put("s", source)
                // 4) Completar códigos que falten en entradas antiguas.
                if (text.isNotEmpty() && o.optString("b64").isEmpty() &&
                    o.optString("bin").isEmpty() && o.optString("b64f").isEmpty()) {
                    val code = MsgBase.write(c, o.optLong("ts", System.currentTimeMillis()), text)
                    o.put("b64", code.b64).put("bin", code.bin).put("b64f", code.b64f)
                    changed = true
                }
                arr.put(o)
            }
            if (changed || arr.length() != old.length()) {
                sp(c).edit().putString("hist2", arr.toString()).apply()
            }
            sp(c).edit().putInt("code_v", 3).apply()
        }
    }

    /**
     * Fuerza una lectura fresca del historial desde el disco. Sirve para el gesto «arrastra hacia abajo para
     * actualizar»: por si el servicio de notificaciones escribió algo mientras la app estaba en segundo plano.
     * Se hace en el hilo que llama (es una lectura pequeña) y se devuelve la lista ya actualizada.
     */
    @Suppress("ApplySharedPref")
    fun reloadHist(c: Context): List<HistItem> {
        try { sp(c).edit().commit() } catch (e: Exception) {}
        return histAll(c)
    }
    fun clearHist(c: Context) {
        synchronized(histLock) {
            sp(c).edit().remove("hist2").apply()
            histDir(c).listFiles()?.forEach { it.delete() }
        }
    }

    // ---- Método con privilegios ----
    fun shellMethod(c: Context): Int = sp(c).getInt("shell_m", 0)      // 0 auto, 1 Shizuku, 2 root
    fun setShellMethod(c: Context, v: Int) = sp(c).edit().putInt("shell_m", v).apply()

    // ---- Gestos: 0 nada, 1 abrir app, 2 ventana (según el modo), 3 descartar, 4 siguiente, 5 anterior, 6 contraer, 7 detalle, 8 burbuja, 9 formato libre, 10 copiar, 11 silenciar app ----
    fun gTap(c: Context): Int = sp(c).getInt("g_tap", 7)
    fun setGTap(c: Context, v: Int) = sp(c).edit().putInt("g_tap", v).apply()
    // Deslizar hacia ABAJO contrae (cierra) la isla expandida: es lo que espera cualquiera al ver el asa.
    fun gDown(c: Context): Int = sp(c).getInt("g_down", 6)
    fun setGDown(c: Context, v: Int) = sp(c).edit().putInt("g_down", v).apply()
    fun gUp(c: Context): Int = sp(c).getInt("g_up", 3)
    fun setGUp(c: Context, v: Int) = sp(c).edit().putInt("g_up", v).apply()
    fun gLeft(c: Context): Int = sp(c).getInt("g_left", 4)
    fun setGLeft(c: Context, v: Int) = sp(c).edit().putInt("g_left", v).apply()
    fun gRight(c: Context): Int = sp(c).getInt("g_right", 5)
    fun setGRight(c: Context, v: Int) = sp(c).edit().putInt("g_right", v).apply()
    fun gLong(c: Context): Int = sp(c).getInt("g_long", 6)
    fun setGLong(c: Context, v: Int) = sp(c).edit().putInt("g_long", v).apply()
    fun swipeDp(c: Context): Int = sp(c).getInt("swipe_dp", 50)   // distancia fija para deslizar: 50 dp
    fun setSwipeDp(c: Context, v: Int) = sp(c).edit().putInt("swipe_dp", v).apply()
    fun autoHideAfterTouch(c: Context): Boolean = true
    fun appTheme(c: Context): String = sp(c).getString("app_theme", "yellow") ?: "yellow"
    fun setAppTheme(c: Context, v: String) = sp(c).edit().putString("app_theme", v).apply()

    // ---- Aspecto: isla en reposo y tarjeta (cristal / color / transparencia) ----
    fun idleMode(c: Context): Int = sp(c).getInt("idle_mode", 0)       // 0 negro, 1 cristal, 2 color, 3 acento
    fun setIdleMode(c: Context, v: Int) = sp(c).edit().putInt("idle_mode", v).apply()
    fun idleColor(c: Context): Int = sp(c).getInt("idle_color", 0)     // 0 = automático
    fun setIdleColor(c: Context, v: Int) = sp(c).edit().putInt("idle_color", v).apply()
    fun idleOpacity(c: Context): Int = sp(c).getInt("idle_op", 100)
    fun setIdleOpacity(c: Context, v: Int) = sp(c).edit().putInt("idle_op", v).apply()
    fun idleBorder(c: Context): Int = sp(c).getInt("idle_border", -1)   // -1 = según el modo
    fun setIdleBorder(c: Context, v: Int) = sp(c).edit().putInt("idle_border", v).apply()
    fun idleBlur(c: Context): Boolean = sp(c).getBoolean("idle_blur", false)
    fun setIdleBlur(c: Context, v: Boolean) = sp(c).edit().putBoolean("idle_blur", v).apply()
    fun cardGlass(c: Context): Boolean = sp(c).getBoolean("card_glass", false)
    fun setCardGlass(c: Context, v: Boolean) = sp(c).edit().putBoolean("card_glass", v).apply()
    fun cardColor(c: Context): Int = sp(c).getInt("card_color", 0)     // 0 = el del estilo
    fun setCardColor(c: Context, v: Int) = sp(c).edit().putInt("card_color", v).apply()

    // ---- Visibilidad de la isla ----
    fun hideInGames(c: Context): Boolean = sp(c).getBoolean("hide_games", false)
    fun setHideInGames(c: Context, v: Boolean) = sp(c).edit().putBoolean("hide_games", v).apply()
    fun hideApps(c: Context): Set<String> = sp(c).getStringSet("hide_apps", emptySet()) ?: emptySet()
    fun setHideApps(c: Context, v: Set<String>) = sp(c).edit().putStringSet("hide_apps", v).apply()

    // ---- Formato libre y burbujas ----
    fun fwFrame(c: Context): Boolean = sp(c).getBoolean("fw_frame", true)
    fun setFwFrame(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_frame", v).apply()

    // ---- Llamadas: contactos prioritarios y comportamiento ----
    /**
     * Nombres (o números) que el usuario marca como prioritarios DENTRO de Ryx, sin depender del permiso de
     * contactos. Si la llamada viene de uno de ellos, la isla aparece SIEMPRE (aunque haya un juego al frente
     * o el interruptor de «ocultar en juegos» esté activo). Se guarda como una línea por nombre.
     */
    fun priorityNames(c: Context): List<String> = (sp(c).getString("call_prio", "") ?: "")
        .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    fun setPriorityNames(c: Context, list: List<String>) =
        sp(c).edit().putString("call_prio", list.joinToString("\n")).apply()
    fun addPriorityName(c: Context, v: String) {
        val l = priorityNames(c).toMutableList()
        val n = v.trim()
        if (n.isNotEmpty() && l.none { it.equals(n, true) }) { l.add(n); setPriorityNames(c, l) }
    }
    fun removePriorityName(c: Context, v: String) =
        setPriorityNames(c, priorityNames(c).filterNot { it.equals(v.trim(), true) })

    /** Que la isla aparezca SIEMPRE para llamadas prioritarias aunque haya un juego o app oculta al frente (por defecto: sí). */
    fun callPriorityBypass(c: Context): Boolean = sp(c).getBoolean("call_bypass", true)
    fun setCallPriorityBypass(c: Context, v: Boolean) = sp(c).edit().putBoolean("call_bypass", v).apply()

    /** Usar el grupo «Favoritos» de la agenda (necesita permiso de contactos) además de la lista propia (por defecto: sí). */
    fun callUseFavorites(c: Context): Boolean = sp(c).getBoolean("call_favs", true)
    fun setCallUseFavorites(c: Context, v: Boolean) = sp(c).edit().putBoolean("call_favs", v).apply()

    /** Qué app de llamadas usar al pulsar «Abrir» en una llamada: "" = la predeterminada del sistema. */
    fun callApp(c: Context): String = sp(c).getString("call_app", "") ?: ""
    fun setCallApp(c: Context, v: String) = sp(c).edit().putString("call_app", v).apply()

    // ---- Captura de chats por ACCESIBILIDAD ----
    /**
     * Leer por accesibilidad el texto visible de los chats que abres (incluidos los ARCHIVADOS y RESTRINGIDOS,
     * que no publican notificación). Guarda lo que se ve en pantalla en el Historial. Por defecto: apagado, porque
     * es lo más invasivo; el usuario lo activa a conciencia.
     */
    fun chatCapture(c: Context): Boolean = sp(c).getBoolean("chat_cap", false)
    fun setChatCapture(c: Context, v: Boolean) = sp(c).edit().putBoolean("chat_cap", v).apply()

    /** Apps de chat que se vigilan (por paquete). Vacío = todas las que Ryx reconozca como de chat. */
    fun chatPkgs(c: Context): Set<String> =
        (sp(c).getString("chat_pkgs", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun setChatPkgs(c: Context, v: Set<String>) = sp(c).edit().putString("chat_pkgs", v.joinToString("\n")).apply()

    /**
     * Registrar el TIPO de los adjuntos que se ven en el chat (Foto, Vídeo, Sticker, GIF, Audio…).
     * Se lee del árbol de accesibilidad, que expone la descripción pero no los píxeles.
     * Por defecto: activado.
     */
    fun chatMedia(c: Context): Boolean = sp(c).getBoolean("chat_media", true)
    fun setChatMedia(c: Context, v: Boolean) = sp(c).edit().putBoolean("chat_media", v).apply()

    /**
     * Copiar también el recorte visible de las fotos, stickers y GIF de un chat. Se hace con la captura
     * de accesibilidad (Android 11+) y solo cuando hay un adjunto en pantalla. La accesibilidad solo
     * entrega el fotograma visible, así que un GIF queda como imagen fija; las notificaciones sí
     * conservan el archivo animado cuando la app comparte su URI.
     * Por defecto: activado, porque el usuario quiere poder volver a ver el adjunto en el Historial.
     */
    fun chatShots(c: Context): Boolean = sp(c).getBoolean("chat_shots", true)
    fun setChatShots(c: Context, v: Boolean) = sp(c).edit().putBoolean("chat_shots", v).apply()

    /**
     * Último recurso para fotos de WhatsApp: si el aviso dice «Foto» pero la app no
     * comparte los píxeles, se busca la última imagen reciente de su carpeta en la
     * galería del sistema. Es opcional y solo se usa cuando no hay imagen en el aviso;
     * nunca se hace una captura de pantalla.
     */
    fun mediaFolder(c: Context): Boolean = sp(c).getBoolean("media_folder", true)
    fun setMediaFolder(c: Context, v: Boolean) = sp(c).edit().putBoolean("media_folder", v).apply()

    // ---- Copia en la nube y actualizaciones ----
    fun cloudUri(c: Context): String = sp(c).getString("cloud_uri", "") ?: ""
    fun setCloudUri(c: Context, v: String) = sp(c).edit().putString("cloud_uri", v).apply()
    fun cloudLast(c: Context): Long = sp(c).getLong("cloud_last", 0L)
    fun setCloudLast(c: Context, v: Long) = sp(c).edit().putLong("cloud_last", v).apply()
    fun updLast(c: Context): Long = sp(c).getLong("upd_last", 0L)
    fun setUpdLast(c: Context, v: Long) = sp(c).edit().putLong("upd_last", v).apply()
    /** Avisar de la versión nueva CON LA ISLA en vez del panel del sistema (por defecto: sí). */
    fun updViaIsland(c: Context): Boolean = sp(c).getBoolean("upd_island", true)
    fun setUpdViaIsland(c: Context, v: Boolean) = sp(c).edit().putBoolean("upd_island", v).apply()
    /** Poner también el aviso en el panel del sistema además del de la isla (por defecto: no). */
    fun updAlsoPanel(c: Context): Boolean = sp(c).getBoolean("upd_panel", false)
    fun setUpdAlsoPanel(c: Context, v: Boolean) = sp(c).edit().putBoolean("upd_panel", v).apply()
    /** Cada cuántos segundos se mira GitHub (por defecto 30 s). */
    fun updEverySec(c: Context): Int = sp(c).getInt("upd_every_s", 30)
    fun setUpdEverySec(c: Context, v: Int) = sp(c).edit().putInt("upd_every_s", v).apply()

    // ---- Carpeta propia de respaldos (dentro de la app): «ryx respaldo» ----
    /** Guardar respaldos solo, sin tocar nada (por defecto: sí). */
    fun backupAuto(c: Context): Boolean = sp(c).getBoolean("backup_auto", true)
    fun setBackupAuto(c: Context, v: Boolean) = sp(c).edit().putBoolean("backup_auto", v).apply()
    /** Cada cuánto revisa si hay que guardar el respaldo propio, en SEGUNDOS (por defecto 30). */
    fun backupEverySec(c: Context): Int = sp(c).getInt("backup_every_s2", 30)
    fun setBackupEverySec(c: Context, v: Int) = sp(c).edit().putInt("backup_every_s2", v).apply()

    /** Compatibilidad con la clave antigua en minutos (se sigue leyendo por si venía de una versión previa). */
    fun backupEveryMin(c: Context): Int = sp(c).getInt("backup_every_m", 30)
    /** Cuántas copias internas se conservan (por defecto 7). */
    fun backupKeep(c: Context): Int = sp(c).getInt("backup_keep", 7)
    fun setBackupKeep(c: Context, v: Int) = sp(c).edit().putInt("backup_keep", v).apply()
    /** Suma de comprobación de los datos: si cambia, hay algo nuevo que guardar. */
    fun backupStamp(c: Context): String = sp(c).getString("backup_stamp", "") ?: ""
    fun setBackupStamp(c: Context, v: String) = sp(c).edit().putString("backup_stamp", v).apply()
    fun backupLast(c: Context): Long = sp(c).getLong("backup_last", 0L)
    fun setBackupLast(c: Context, v: Long) = sp(c).edit().putLong("backup_last", v).apply()
    /** Variante de apertura directa que funcionó en esta ROM (-1 = sin calibrar). */
    fun fwVariant(c: Context): Int = sp(c).getInt("fw_variant", -1)
    fun setFwVariant(c: Context, v: Int) = sp(c).edit().putInt("fw_variant", v).apply()
    /** Activar solo «Forzar que las actividades sean redimensionables» antes de abrir (necesita WRITE_SECURE_SETTINGS, concedido una vez por ADB). */
    fun fwUnlock(c: Context): Boolean = sp(c).getBoolean("fw_unlock", true)
    fun setFwUnlock(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_unlock", v).apply()
    /** 0 formato libre directo, 1 burbuja (Android 17). Migra la elección anterior: antes la burbuja era «fw_solo_mode» = 2. */
    fun fwKind(c: Context): Int = sp(c).getInt("fw_kind", if (sp(c).getInt("fw_solo_mode", 1) == 2) 1 else 0)
    fun setFwKind(c: Context, v: Int) = sp(c).edit().putInt("fw_kind", v).apply()
    fun fwKeepApp(c: Context): Boolean = sp(c).getBoolean("fw_keep_app", true)
    fun setFwKeepApp(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_keep_app", v).apply()
    /** La ROM ignora un movimiento que no cambia el tamaño: se pide con 1 px de diferencia (se aprende cuando funciona). */
    fun fwMoveNudge(c: Context): Boolean = sp(c).getBoolean("fw_move_nudge", false)
    fun setFwMoveNudge(c: Context, v: Boolean) = sp(c).edit().putBoolean("fw_move_nudge", v).apply()
    /** Últimas decisiones sobre los avisos del sistema (qué tomó la isla y por qué dejó otros), para diagnosticar. */
    fun notifDiag(c: Context): String = sp(c).getString("notif_diag", "") ?: ""
    fun setNotifDiag(c: Context, s: String) = sp(c).edit().putString("notif_diag", s.takeLast(1500)).apply()
    fun a11yAsked(c: Context): Boolean = sp(c).getBoolean("a11y_asked", false)
    fun setA11yAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("a11y_asked", v).apply()
    fun restoreAsked(c: Context): Boolean = sp(c).getBoolean("restore_asked", false)
    fun setRestoreAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("restore_asked", v).apply()
    fun histRaw(c: Context): String = synchronized(histLock) { sp(c).getString("hist2", "[]") ?: "[]" }
    fun setHistRaw(c: Context, v: String) = synchronized(histLock) { sp(c).edit().putString("hist2", v).apply() }

    /**
     * Lee, transforma y escribe el historial en una sola operación atómica. Necesario al restaurar una copia:
     * si se leyera y escribiera por separado, una notificación que llegue entre medias se perdería.
     */
    fun updateHistRaw(c: Context, transform: (String) -> String): String = synchronized(histLock) {
        val old = sp(c).getString("hist2", "[]") ?: "[]"
        val out = transform(old)
        sp(c).edit().putString("hist2", out).apply()
        out
    }
}
