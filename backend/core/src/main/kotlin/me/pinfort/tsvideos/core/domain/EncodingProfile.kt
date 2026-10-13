package me.pinfort.tsvideos.core.domain

data class EncodingProfileSelection(
    val profile: String,
    val ruleId: String,
    val reason: String,
)

data class EncodingProfileInput(
    val channel: String,
    val channelName: String,
    val title: String,
    val durationSeconds: Double,
)

/** All predicates are combined with AND. Unknown duration cannot satisfy a duration predicate. */
data class EncodingProfileRule(
    val id: String,
    val profile: String,
    val channel: String? = null,
    val channelName: String? = null,
    val titlePattern: String? = null,
    val minDurationSeconds: Double? = null,
    val maxDurationSeconds: Double? = null,
) {
    private val titleRegex: Regex?

    init {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Invalid profile rule ID" }
        validateProfileName(profile)
        require(channel == null || channel.isNotBlank()) { "Channel must not be blank" }
        require(channelName == null || channelName.isNotBlank()) { "Channel name must not be blank" }
        require(titlePattern == null || titlePattern.isNotEmpty()) { "Title pattern must not be empty" }
        require(listOf(channel, channelName, titlePattern, minDurationSeconds, maxDurationSeconds).any { it != null }) {
            "Profile rule requires at least one predicate"
        }
        for (limit in listOfNotNull(minDurationSeconds, maxDurationSeconds)) {
            require(limit.isFinite() && limit > 0) { "Duration limits must be finite and positive" }
        }
        require(minDurationSeconds == null || maxDurationSeconds == null || minDurationSeconds <= maxDurationSeconds) {
            "Duration range is inverted"
        }
        titleRegex =
            try {
                titlePattern?.let(::Regex)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid profile title pattern")
            }
    }

    fun matches(input: EncodingProfileInput): Boolean {
        if (channel != null && channel != input.channel) return false
        if (channelName != null && channelName != input.channelName) return false
        if (titleRegex != null && !titleRegex.containsMatchIn(input.title)) return false
        if (minDurationSeconds != null || maxDurationSeconds != null) {
            if (!input.durationSeconds.isFinite() || input.durationSeconds <= 0) return false
            if (minDurationSeconds != null && input.durationSeconds < minDurationSeconds) return false
            if (maxDurationSeconds != null && input.durationSeconds > maxDurationSeconds) return false
        }
        return true
    }
}

/** Configuration order defines precedence. Remote encoder profile existence is not inferred. */
class EncodingProfilePolicy(
    rules: List<EncodingProfileRule>,
    private val fallbackProfile: String?,
    private val defaultProfile: String,
    private val atxDivProfile: String,
) {
    private val rules = rules.toList()
    private val atxDivTitleRegex = Regex("#[0-9]{1,3}-#[0-9]{1,3}")

    init {
        validateProfileName(defaultProfile)
        validateProfileName(atxDivProfile)
        fallbackProfile?.let(::validateProfileName)
        require(rules.map { it.id }.distinct().size == rules.size) { "Profile rule IDs must be unique" }
        require(rules.isEmpty() || fallbackProfile != null) { "Configured profile rules require an explicit fallback" }
    }

    fun select(input: EncodingProfileInput): EncodingProfileSelection {
        if (rules.isNotEmpty()) {
            val match = rules.firstOrNull { it.matches(input) }
            return if (match != null) {
                EncodingProfileSelection(match.profile, match.id, "MATCHED_RULE")
            } else {
                EncodingProfileSelection(checkNotNull(fallbackProfile), "fallback", "FALLBACK")
            }
        }
        val divided = input.channelName == "ＡＴ－Ｘ" && input.durationSeconds > 10800 && atxDivTitleRegex.containsMatchIn(input.title)
        return if (divided) {
            EncodingProfileSelection(atxDivProfile, "legacy-atx-div", "LEGACY_ATX_DIV")
        } else {
            EncodingProfileSelection(defaultProfile, "legacy-default", "LEGACY_DEFAULT")
        }
    }
}

internal fun validateProfileName(profile: String) {
    require(profile.isNotBlank() && profile.length <= 255 && profile.none(Char::isISOControl)) { "Invalid encoding profile name" }
}
