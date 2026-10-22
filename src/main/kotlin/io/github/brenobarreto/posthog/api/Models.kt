package io.github.brenobarreto.posthog.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class Project(val id: Long, val name: String)

data class FlagVariant(val key: String, val rolloutPercentage: Double)

/**
 * The parts of a PostHog feature flag the editor needs. The derived properties are ported from
 * posthog-vscode (`src/utils/flagClassification.ts`, MIT; see NOTICE).
 */
data class FeatureFlag(
    val id: Long,
    val key: String,
    val name: String,
    val active: Boolean,
    val deleted: Boolean,
    /** Top-level `rollout_percentage`, set on simple flags. */
    val rolloutPercentage: Double?,
    val filters: JsonObject?,
) {
    private val groups: List<JsonObject> by lazy {
        filters?.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.asObjectOrNull() } ?: emptyList()
    }

    /** Multivariate variants; empty for boolean flags. */
    val variants: List<FlagVariant> by lazy {
        filters?.get("multivariate")?.asObjectOrNull()?.get("variants")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { v ->
                val obj = v.asObjectOrNull() ?: return@mapNotNull null
                FlagVariant(obj.string("key") ?: return@mapNotNull null, obj.number("rollout_percentage") ?: 0.0)
            } ?: emptyList()
    }

    /** The flag's rollout: the top-level value, else the first release condition that has one. */
    val rollout: Double? by lazy { rolloutPercentage ?: groups.firstNotNullOfOrNull { it.number("rollout_percentage") } }

    /** Release conditions that filter on properties. */
    val conditionCount: Int by lazy {
        groups.count { g -> g.get("properties")?.takeIf { it.isJsonArray }?.asJsonArray?.let { it.size() > 0 } == true }
    }
}

enum class ExperimentStatus { DRAFT, RUNNING, COMPLETE }

data class Experiment(
    val id: Long,
    val name: String,
    val startDate: String?,
    val endDate: String?,
    val featureFlagKey: String,
    /** `won` or `lost` once concluded. */
    val conclusion: String?,
) {
    val status: ExperimentStatus
        get() = when {
            endDate != null -> ExperimentStatus.COMPLETE
            startDate != null -> ExperimentStatus.RUNNING
            else -> ExperimentStatus.DRAFT
        }
}

internal fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.number(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

internal fun JsonObject.long(name: String): Long? = number(name)?.toLong()

internal fun JsonObject.bool(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

internal fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonObject.parseProject(): Project? {
    val id = long("id") ?: return null
    val name = string("name") ?: return null
    return Project(id, name)
}

internal fun JsonObject.parseFlag(): FeatureFlag? {
    val id = long("id") ?: return null
    val key = string("key") ?: return null
    return FeatureFlag(
        id = id,
        key = key,
        name = string("name").orEmpty(),
        active = bool("active") ?: false,
        deleted = bool("deleted") ?: false,
        rolloutPercentage = number("rollout_percentage"),
        filters = get("filters")?.asObjectOrNull(),
    )
}

internal fun JsonObject.parseExperiment(): Experiment? {
    val id = long("id") ?: return null
    val flagKey = string("feature_flag_key") ?: return null
    return Experiment(
        id = id,
        name = string("name").orEmpty(),
        startDate = string("start_date"),
        endDate = string("end_date"),
        featureFlagKey = flagKey,
        conclusion = string("conclusion"),
    )
}
