package com.buco7854.opentv.data.meta

import org.json.JSONArray
import org.json.JSONObject

data class CastMember(val name: String, val photo: String?)

/** Compact JSON encoding of a cast list for the metadata cache. */
fun encodeCast(members: List<CastMember>): String =
    JSONArray().apply {
        members.forEach { member ->
            put(JSONObject().apply {
                put("n", member.name)
                member.photo?.let { put("p", it) }
            })
        }
    }.toString()

fun decodeCast(json: String?): List<CastMember> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("n")
                if (name.isNotBlank()) {
                    add(CastMember(name, obj.optString("p").takeIf { it.isNotBlank() }))
                }
            }
        }
    }.getOrDefault(emptyList())
}

/** "A, B, C" panel cast strings become photo-less members (initials avatars). */
fun castFromNames(names: String?): List<CastMember> =
    names?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?.map { CastMember(it, null) } ?: emptyList()
