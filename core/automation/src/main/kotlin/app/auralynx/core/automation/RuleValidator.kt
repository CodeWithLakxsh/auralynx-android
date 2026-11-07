package app.auralynx.core.automation

/** Validation errors are shown in the rule editor before a rule can be saved. */
enum class RuleProblem { EMPTY_NAME, NO_CONDITIONS, NO_ACTIONS, DUPLICATE_TARGET, CONTRADICTORY_CONDITIONS }

object RuleValidator {
    fun validate(rule: Rule): Set<RuleProblem> = buildSet {
        if (rule.name.isBlank()) add(RuleProblem.EMPTY_NAME)
        if (rule.conditions.isEmpty()) add(RuleProblem.NO_CONDITIONS)
        if (rule.actions.isEmpty()) add(RuleProblem.NO_ACTIONS)
        if (rule.actions.groupBy { it.target }.any { it.value.size > 1 }) add(RuleProblem.DUPLICATE_TARGET)
        // Two different Wi-Fi networks, apps or activities can never hold at the same time.
        val wifi = rule.conditions.filterIsInstance<Condition.OnWifi>().map { it.ssid }.toSet()
        val apps = rule.conditions.filterIsInstance<Condition.AppInForeground>().map { it.packageName }.toSet()
        val activities = rule.conditions.filterIsInstance<Condition.Activity>().map { it.activity }.toSet()
        if (wifi.size > 1 || apps.size > 1 || activities.size > 1) add(RuleProblem.CONTRADICTORY_CONDITIONS)
    }
}

/** JSON codec used by the database layer. */
object RuleCodec {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encodeConditions(c: List<Condition>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Condition.serializer()), c)
    fun decodeConditions(s: String): List<Condition> = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Condition.serializer()), s)
    fun encodeActions(a: List<Action>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Action.serializer()), a)
    fun decodeActions(s: String): List<Action> = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Action.serializer()), s)
}
