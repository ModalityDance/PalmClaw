package com.palmclaw.channels

fun buildFeishuAdapterSeed(
    appId: String,
    appSecret: String
): String? {
    val normalizedAppId = appId.trim()
    val normalizedAppSecret = appSecret.trim()
    if (normalizedAppId.isBlank() || normalizedAppSecret.isBlank()) return null
    return "$normalizedAppId|$normalizedAppSecret"
}

fun buildFeishuAdapterSeeds(
    appId: String,
    appSecret: String,
    encryptKey: String = "",
    verificationToken: String = ""
): List<String> {
    val canonicalSeed = buildFeishuAdapterSeed(appId = appId, appSecret = appSecret) ?: return emptyList()
    val legacySeed = "$canonicalSeed|${encryptKey.trim()}|${verificationToken.trim()}"
    return buildList {
        add(canonicalSeed)
        if (legacySeed != canonicalSeed) {
            add(legacySeed)
        }
    }
}
