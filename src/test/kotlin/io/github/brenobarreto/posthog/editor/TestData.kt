package io.github.brenobarreto.posthog.editor

import com.google.gson.JsonParser
import io.github.brenobarreto.posthog.api.Experiment
import io.github.brenobarreto.posthog.api.FeatureFlag

internal fun flag(
    key: String,
    active: Boolean = true,
    rollout: Double? = null,
    filters: String? = null,
    id: Long = key.hashCode().toLong(),
    deleted: Boolean = false,
    createdBy: String? = null,
    createdAt: String? = null,
) = FeatureFlag(id, key, "", active, deleted, rollout, filters?.let { JsonParser.parseString(it).asJsonObject }, createdBy, createdAt)

internal fun experiment(flagKey: String, start: String? = null, end: String? = null, name: String = "Experiment") =
    Experiment(1, name, start, end, flagKey, null)

internal fun multivariate(vararg variants: Pair<String, Int>) =
    """{"multivariate":{"variants":[${variants.joinToString(",") { """{"key":"${it.first}","rollout_percentage":${it.second}}""" }}]}}"""
