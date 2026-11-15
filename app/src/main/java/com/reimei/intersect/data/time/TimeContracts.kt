package com.reimei.intersect.data.time

/**
 * T1.4 —— 数据契约(JSON Schema 的等价物)。
 *
 * 五份契约:[CLOCK] / [SUMMARY_META] / [TIME_EVENT] / [DIRECTOR_TRANSACTION] / [ROUND_TYPE]。
 *
 * 本工程不引第三方 Schema 库(见 `handoff/README.md`:禁止引入新的 JSON 库),所以这里用
 * 「字段表 + 校验函数」表达 Schema:字段名、类型、是否必填、枚举取值、能否为 null,
 * 以及**未声明字段一律报错**(这样多写一个 `round_type` 就会被单测逮住)。
 *
 * 校验函数是纯函数([validate] 输入一个 [JsonValue]),所以整份契约都能在 JVM 单测里跑 ——
 * 这正是本子系统自带 [JsonValue] 而不是用 `org.json` 的原因。
 */
enum class FieldType { STRING, INT, NUMBER, BOOL, OBJECT, ARRAY, ENUM }

data class FieldSpec(
  val name: String,
  val type: FieldType,
  val required: Boolean = true,
  /** 仅 [FieldType.ENUM] 用。 */
  val allowed: Set<String> = emptySet(),
  /** 允许显式 null(字段仍在,值为 null)。 */
  val allowNull: Boolean = false,
)

data class ContractSchema(val name: String, val fields: List<FieldSpec>) {
  val requiredFields: List<FieldSpec> get() = fields.filter { it.required }
}

/** 时间事件的种类 —— 注入层与事务层共用同一套词。 */
enum class TimeEventKind {
  ALIGN,
  ADVANCE,
  SET,
  PAUSE,
  RESUME,
  RATE,
  SUMMARY,
  FUSE,
  TEMP_QUERY,
  DIRECTOR_EVENT;

  companion object {
    fun fromName(name: String?): TimeEventKind? =
      entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
  }
}

object TimeContracts {
  const val CLOCK = "clock"
  const val SUMMARY_META = "summary_meta"
  const val TIME_EVENT = "time_event"
  const val DIRECTOR_TRANSACTION = "director_transaction"
  const val ROUND_TYPE = "round_type"

  private val PERIOD_NAMES: Set<String> = Period.entries.map { it.name }.toSet()
  private val FALLBACK_NAMES: Set<String> = FallbackLevel.entries.map { it.name }.toSet()

  private val CLOCK_FIELDS = listOf(
    FieldSpec("exact_time", FieldType.INT),
    FieldSpec("period", FieldType.ENUM, allowed = PERIOD_NAMES),
    FieldSpec("date_anchor", FieldType.INT),
    FieldSpec("rate", FieldType.NUMBER),
    FieldSpec("paused", FieldType.BOOL),
    FieldSpec("last_summary_period", FieldType.ENUM, required = false, allowed = PERIOD_NAMES, allowNull = true),
    FieldSpec("pending_director_patch", FieldType.OBJECT, required = false, allowNull = true),
    // 导演模式的场景字段。声明成「非必填」:旧版本落盘的账本里没有这两个键,
    // 读回来是空串(未设定),不该因此判成坏数据。
    FieldSpec("place", FieldType.STRING, required = false),
    FieldSpec("weather", FieldType.STRING, required = false),
    // 纪年(非公元历):纪年名 + 年份是否显式带 `+`。同样声明成非必填,
    // 旧账本里没有这两个键时读回「公元」。
    FieldSpec("era_name", FieldType.STRING, required = false),
    FieldSpec("era_plus_sign", FieldType.BOOL, required = false),
  )

  /** 导演补丁的字段(嵌在 clock 里,也出现在事务日志的备注里)。 */
  val PATCH_FIELDS = listOf(
    FieldSpec("period", FieldType.ENUM, allowed = PERIOD_NAMES),
    FieldSpec("noun", FieldType.STRING, required = false, allowNull = true),
    FieldSpec("op", FieldType.STRING),
    FieldSpec("at_round", FieldType.INT, required = false),
    FieldSpec("at", FieldType.INT),
    /** 导演面板的场景变更明细(旧值 → 新值),只有 `op=SCENE` 会带。 */
    FieldSpec("changes", FieldType.ARRAY, required = false),
  )

  private val SUMMARY_META_FIELDS = listOf(
    FieldSpec("span", FieldType.STRING),
    FieldSpec("start_period", FieldType.ENUM, required = false, allowed = PERIOD_NAMES),
    FieldSpec("end_period", FieldType.ENUM, allowed = PERIOD_NAMES),
    FieldSpec("noun_hint", FieldType.STRING),
    FieldSpec("elapsed", FieldType.INT),
    FieldSpec("key_events", FieldType.ARRAY),
    FieldSpec("fallback", FieldType.ENUM, allowed = FALLBACK_NAMES),
  )

  private val TIME_EVENT_FIELDS = listOf(
    FieldSpec("kind", FieldType.ENUM, allowed = TimeEventKind.entries.map { it.name }.toSet()),
    FieldSpec("period", FieldType.ENUM, required = false, allowed = PERIOD_NAMES),
    FieldSpec("noun", FieldType.STRING, required = false),
    FieldSpec("exact_time", FieldType.INT, required = false),
    FieldSpec("date_anchor", FieldType.INT, required = false),
    FieldSpec("at_round", FieldType.INT),
    FieldSpec("fallback", FieldType.ENUM, required = false, allowed = FALLBACK_NAMES),
    FieldSpec("round_type", FieldType.ENUM, required = false, allowed = RoundType.entries.map { it.name }.toSet()),
  )

  private val DIRECTOR_TRANSACTION_FIELDS = listOf(
    FieldSpec("tx_id", FieldType.STRING),
    FieldSpec("op", FieldType.ENUM, allowed = ClockOp.entries.map { it.name }.toSet()),
    FieldSpec("operator", FieldType.ENUM, allowed = TxOperator.entries.map { it.name }.toSet()),
    FieldSpec("before", FieldType.OBJECT),
    FieldSpec("after", FieldType.OBJECT),
    FieldSpec("snapshot_ref", FieldType.STRING),
    FieldSpec("at", FieldType.INT),
    FieldSpec("pending", FieldType.BOOL),
    FieldSpec("at_round", FieldType.INT),
    FieldSpec("note", FieldType.STRING, required = false),
  )

  private val ROUND_TYPE_FIELDS = listOf(
    FieldSpec("round_type", FieldType.ENUM, allowed = RoundType.entries.map { it.name }.toSet()),
    FieldSpec("segments", FieldType.ARRAY),
  )

  private val SCHEMAS: Map<String, ContractSchema> = mapOf(
    CLOCK to ContractSchema(CLOCK, CLOCK_FIELDS),
    SUMMARY_META to ContractSchema(SUMMARY_META, SUMMARY_META_FIELDS),
    TIME_EVENT to ContractSchema(TIME_EVENT, TIME_EVENT_FIELDS),
    DIRECTOR_TRANSACTION to ContractSchema(DIRECTOR_TRANSACTION, DIRECTOR_TRANSACTION_FIELDS),
    ROUND_TYPE to ContractSchema(ROUND_TYPE, ROUND_TYPE_FIELDS),
  )

  fun schemaOf(name: String): ContractSchema? = SCHEMAS[name]

  fun allSchemas(): List<ContractSchema> = SCHEMAS.values.toList()

  /**
   * 校验一份契约实例。返回**问题清单**,空表示通过。
   *
   * 检查四种问题:不是对象 / 缺必填字段 / 类型(或枚举值)不对 / 出现未声明字段。
   */
  fun validate(name: String, value: JsonValue?): List<String> {
    val schema = SCHEMAS[name] ?: return listOf("未知契约:$name")
    val obj = value as? JsonValue.Obj ?: return listOf("$name:必须是对象")
    val problems = mutableListOf<String>()

    schema.fields.forEach { spec ->
      val field = obj.fields[spec.name]
      when {
        field == null -> if (spec.required) problems += "$name:缺字段 ${spec.name}"
        field is JsonValue.Null && !spec.allowNull -> problems += "$name:字段 ${spec.name} 不能为 null"
        field is JsonValue.Null -> Unit
        !typeMatches(spec, field) -> problems += "$name:字段 ${spec.name} 类型应为 ${spec.type}"
        spec.type == FieldType.ENUM -> {
          val text = (field as JsonValue.Str).value
          if (!spec.allowed.contains(text)) problems += "$name:字段 ${spec.name} 取值 '$text' 不在枚举内"
        }
      }
    }
    val declared = schema.fields.map { it.name }.toSet()
    obj.fields.keys.filter { !declared.contains(it) }
      .forEach { problems += "$name:出现未声明字段 $it" }
    return problems
  }

  /** 导演补丁单独校验(它嵌在 clock 里)。 */
  fun validatePatch(value: JsonValue?): List<String> {
    val obj = value as? JsonValue.Obj ?: return listOf("patch:必须是对象")
    val problems = mutableListOf<String>()
    PATCH_FIELDS.forEach { spec ->
      val field = obj.fields[spec.name]
      when {
        field == null -> if (spec.required) problems += "patch:缺字段 ${spec.name}"
        field is JsonValue.Null && !spec.allowNull -> problems += "patch:字段 ${spec.name} 不能为 null"
        field is JsonValue.Null -> Unit
        !typeMatches(spec, field) -> problems += "patch:字段 ${spec.name} 类型应为 ${spec.type}"
        spec.type == FieldType.ENUM -> {
          val text = (field as JsonValue.Str).value
          if (!spec.allowed.contains(text)) problems += "patch:字段 ${spec.name} 取值 '$text' 不在枚举内"
        }
      }
    }
    val declared = PATCH_FIELDS.map { it.name }.toSet()
    obj.fields.keys.filter { !declared.contains(it) }.forEach { problems += "patch:出现未声明字段 $it" }
    return problems
  }

  /**
   * T1.4 验收的复合检查:一份 clock 对象 + 它内嵌的补丁都要过 Schema,
   * 且 clock 里**不许有 round_type**(T1.5 修订:T2.1 的「不含 round_type」)。
   */
  fun validateClock(value: JsonValue?): List<String> {
    val problems = validate(CLOCK, value).toMutableList()
    val obj = value as? JsonValue.Obj
    if (obj != null) {
      if (obj.fields.containsKey("round_type")) problems += "clock:不允许出现 round_type(由编排层确定)"
      (obj.fields["pending_director_patch"] as? JsonValue.Obj)?.let { problems += validatePatch(it) }
    }
    return problems
  }

  private fun typeMatches(spec: FieldSpec, value: JsonValue): Boolean = when (spec.type) {
    FieldType.STRING -> value is JsonValue.Str
    FieldType.INT -> value is JsonValue.Num && !value.raw.contains('.')
    FieldType.NUMBER -> value is JsonValue.Num
    FieldType.BOOL -> value is JsonValue.Bool
    FieldType.OBJECT -> value is JsonValue.Obj
    FieldType.ARRAY -> value is JsonValue.Arr
    FieldType.ENUM -> value is JsonValue.Str
  }
}
