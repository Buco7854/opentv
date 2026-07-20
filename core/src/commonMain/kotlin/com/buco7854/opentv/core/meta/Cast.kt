package com.buco7854.opentv.core.meta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

data class CastMember(val name: String, val photo: String?)

/** Compact JSON encoding of a cast list for the metadata cache. */
fun encodeCast(members: List<CastMember>): String =
    buildJsonArray {
        members.forEach { member ->
            add(buildJsonObject {
                put("n", JsonPrimitive(member.name))
                member.photo?.let { put("p", JsonPrimitive(it)) }
            })
        }
    }.toString()

fun decodeCast(json: String?): List<CastMember> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        buildList {
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val name = (obj["n"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                if (!name.isNullOrBlank()) {
                    val photo = (obj["p"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    add(CastMember(name, photo?.takeIf { it.isNotBlank() }))
                }
            }
        }
    }.getOrDefault(emptyList())
}

/** "A, B, C" panel cast strings become photo-less members (initials avatars). */
fun castFromNames(names: String?): List<CastMember> =
    names?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?.map { CastMember(it, null) } ?: emptyList()
