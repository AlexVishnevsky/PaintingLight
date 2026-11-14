package io.github.alexvishnevsky.flashlight3d

import android.content.Context
import android.content.SharedPreferences

/** Every tunable of the app. Order here is the order in the settings panel. */
enum class P(
    val section: String,
    val title: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val toggle: Boolean = false,
) {
    SIZE("Ліхтарик", "Розмір (частка висоти екрана)", 0.3f, 1.5f, 0.85f),
    LEAN("Ліхтарик", "Нахил у глибину, °", 0f, 90f, 35f),
    SPIN("Ліхтарик", "Поворот навколо осі, °", 0f, 360f, 0f),
    ROLL("Ліхтарик", "Нахил убік, °", -180f, 180f, 0f),
    OFFSET_X("Ліхтарик", "Зсув по X, см", -5f, 5f, 0f),
    OFFSET_Y("Ліхтарик", "Зсув по Y, см", -10f, 10f, 0f),
    GAP("Ліхтарик", "Відступ від скла, см", -5f, 10f, 0.05f),
    GLOW("Ліхтарик", "Яскравість лінзи", 0f, 30f, 9f),

    TRACK("Паралакс", "Трекінг голови", 0f, 1f, 1f, true),
    GYRO("Паралакс", "Резерв на гіроскоп", 0f, 1f, 1f, true),
    STRENGTH_X("Паралакс", "Сила паралаксу X", 0f, 4f, 1f),
    STRENGTH_Y("Паралакс", "Сила паралаксу Y", 0f, 4f, 1f),
    INVERT_X("Паралакс", "Інвертувати X", 0f, 1f, 0f, true),
    INVERT_Y("Паралакс", "Інвертувати Y", 0f, 1f, 0f, true),
    EYE_Z("Паралакс", "Відстань до ока за замовчуванням, см", 15f, 80f, 38f),
    LOCK_Z("Паралакс", "Фіксувати відстань до ока", 0f, 1f, 0f, true),
    SMOOTH_XY("Паралакс", "Згладжування XY, мс", 0f, 500f, 70f),
    SMOOTH_Z("Паралакс", "Згладжування відстані, мс", 0f, 1000f, 250f),
    LOST_MS("Паралакс", "Обличчя втрачено через, мс", 100f, 2000f, 400f),
    SCREEN_SCALE("Паралакс", "Поправка фізичного розміру екрана", 0.5f, 1.5f, 1f),
    CAM_X("Паралакс", "Зсув фронталки по X, см", -5f, 5f, 0f),
    CAM_Y("Паралакс", "Зсув фронталки по Y, см", -5f, 5f, 0f),
    CAM_FOV("Паралакс", "Поправка кута огляду фронталки", 0.6f, 1.6f, 1f),

    LIVE("Відбиття", "Жива cubemap з камери", 0f, 1f, 1f, true),
    WORLD_LOCK("Відбиття", "Прив'язка оточення до світу (гіроскоп)", 0f, 1f, 1f, true),
    IBL("Відбиття", "Яскравість оточення", 0f, 5f, 1f),
    GAIN("Відбиття", "Експозиція камери", 0.2f, 8f, 2f),
    BOOST("Відбиття", "Підсилення яскравих джерел", 0f, 30f, 7f),
    BLEND("Відбиття", "Швидкість оновлення", 0.05f, 1f, 0.5f),
    FILL("Відбиття", "Яскравість ще не бачених зон", 0f, 1.5f, 0.5f),
    MIRROR_X("Відбиття", "Дзеркалити кадр по горизонталі", 0f, 1f, 0f, true),
    MIRROR_Y("Відбиття", "Дзеркалити кадр по вертикалі", 0f, 1f, 0f, true),

    BLOOM("Рендер", "Bloom", 0f, 1f, 1f, true),
    BLOOM_STRENGTH("Рендер", "Сила bloom", 0f, 1f, 0.25f),
    AO("Рендер", "Ambient occlusion", 0f, 1f, 1f, true),
    SAFE("Рендер", "Режим сумісності: 0 усе, 1 без камери, 2 Vulkan, 3 мінімум (після перезапуску)", 0f, 3f, 0f),
}

/** Current values, persisted. Read from any thread; written on the main thread. */
object Settings {
    private val values = FloatArray(P.entries.size) { P.entries[it].def }
    private var prefs: SharedPreferences? = null

    /** Bumped on every change, so consumers can cheaply notice. */
    @Volatile var version = 0
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        // A new build deserves a full attempt: forget the compatibility level
        // an older, broken build may have fallen back to.
        val build = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        if (p.getInt("build", 0) != build) {
            p.edit().putInt("build", build).remove(P.SAFE.name).apply()
        }
        for (param in P.entries) {
            values[param.ordinal] = p.getFloat(param.name, param.def).coerceIn(param.min, param.max)
        }
        version++
    }

    operator fun get(param: P): Float = values[param.ordinal]

    fun on(param: P): Boolean = values[param.ordinal] > 0.5f

    operator fun set(param: P, value: Float) {
        values[param.ordinal] = value.coerceIn(param.min, param.max)
        prefs?.edit()?.putFloat(param.name, values[param.ordinal])?.apply()
        version++
    }

    fun reset() {
        for (param in P.entries) values[param.ordinal] = param.def
        val build = prefs?.getInt("build", 0) ?: 0
        prefs?.edit()?.clear()?.putInt("build", build)?.apply()
        version++
    }

    fun describe(): String =
        P.entries.filter { this[it] != it.def }.joinToString { "${it.name}=${this[it]}" }
            .ifEmpty { "усі за замовчуванням" }
}
