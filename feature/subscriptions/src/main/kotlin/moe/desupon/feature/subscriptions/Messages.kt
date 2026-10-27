package moe.desupon.feature.subscriptions

import moe.desupon.data.repo.AddResult
import moe.desupon.data.repo.ConfigImportResult
import moe.desupon.data.repo.UpdateResult
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.ConfigError

/** User-facing text for fetch results; built from codes only, so URLs and tokens can never leak into it. */
internal object Messages {
    private const val HTTP_PREFIX = "HTTP_"

    /** Text for a stored `lastError` code; null when there is no error. */
    fun error(code: String?): UiText? {
        if (code.isNullOrEmpty()) return null
        if (code.startsWith(HTTP_PREFIX)) {
            val status = code.removePrefix(HTTP_PREFIX).toIntOrNull()
                ?: return UiText.Res(R.string.subs_error_unknown)
            val id = when (status) {
                401, 403 -> R.string.subs_error_unauthorized
                404, 410 -> R.string.subs_error_not_found
                429 -> R.string.subs_error_rate_limited
                in 500..599 -> R.string.subs_error_server
                else -> R.string.subs_error_http
            }
            return UiText.Res(id, listOf(status))
        }
        return UiText.Res(
            when (code) {
                "TIMEOUT" -> R.string.subs_error_timeout
                "NETWORK" -> R.string.subs_error_network
                "EMPTY_BODY" -> R.string.subs_error_empty
                "TOO_LARGE" -> R.string.subs_error_too_large
                "NO_NODES" -> R.string.subs_error_no_nodes
                "NO_ENDPOINTS" -> R.string.subs_error_no_endpoints
                else -> R.string.subs_error_unknown
            },
        )
    }

    fun update(result: UpdateResult): UiText? = when (result) {
        is UpdateResult.Updated -> UiText.Plural(R.plurals.subs_result_updated, result.nodes)
        is UpdateResult.Failed -> error(result.error.code)
        is UpdateResult.Throttled -> UiText.Res(R.string.subs_result_throttled)
        UpdateResult.Local -> UiText.Res(R.string.subs_result_local)
        UpdateResult.NotFound -> UiText.Res(R.string.subs_result_not_found)
        UpdateResult.NotDue -> null
    }

    /** Snackbar after an editor save; [refetch] is the update that changed addresses or text triggered. */
    fun saved(refetch: UpdateResult?): UiText {
        val update = refetch?.let(::update) ?: return UiText.Res(R.string.subs_editor_saved)
        return UiText.Res(R.string.subs_editor_saved_with, listOf(update))
    }

    /** Inline error for a rejected local-text edit. */
    fun localText(result: UpdateResult): UiText = when (result) {
        is UpdateResult.Failed -> UiText.Res(R.string.subs_editor_local_no_nodes)
        else -> update(result) ?: UiText.Res(R.string.subs_error_unknown)
    }

    fun updateAll(results: Map<SubId, UpdateResult>): UiText {
        val attempted = results.values.filter { it !is UpdateResult.NotDue && it !is UpdateResult.Local }
        if (attempted.isEmpty()) return UiText.Res(R.string.subs_result_all_none)
        val ok = attempted.count { it is UpdateResult.Updated }
        val failed = attempted.count { it is UpdateResult.Failed }
        return if (failed == 0) {
            UiText.Res(R.string.subs_result_all_ok, listOf(ok, attempted.size))
        } else {
            UiText.Res(R.string.subs_result_all_partial, listOf(ok, attempted.size, failed))
        }
    }

    fun add(result: AddResult): UiText = when (result) {
        is AddResult.AlreadyExists -> UiText.Res(R.string.subs_add_exists)
        AddResult.NothingFound -> UiText.Res(R.string.subs_add_nothing)
        is AddResult.LooksLikeConfigs -> if (result.urls > 0) {
            UiText.Res(R.string.subs_add_mixed, listOf(result.urls, result.configs))
        } else {
            UiText.Res(R.string.subs_add_looks_like_configs, listOf(result.configs))
        }
        is AddResult.Added -> if (result.results.isEmpty()) UiText.Res(R.string.subs_add_exists) else {
            val single = result.results.values.singleOrNull()
            val failure = (single as? UpdateResult.Failed)?.let { error(it.error.code) }
            if (failure != null) {
                UiText.Res(R.string.subs_result_added_failed, listOf(failure))
            } else {
                UiText.Plural(R.plurals.subs_result_added, result.results.size)
            }
        }
    }

    fun configs(result: ConfigImportResult): UiText = when (result) {
        is ConfigImportResult.Imported -> {
            val added = UiText.Plural(R.plurals.subs_configs_added, result.ids.size)
            val configs = when {
                result.ids.isEmpty() -> UiText.Res(R.string.subs_configs_all_saved)
                result.duplicates == 0 && result.skipped == 0 -> added
                else -> UiText.Res(R.string.subs_configs_added_left_out, listOf(added, result.duplicates, result.skipped))
            }
            withSubscriptions(configs, result.subscriptions.size)
        }
        is ConfigImportResult.Listed -> {
            val saved = UiText.Plural(R.plurals.subs_list_saved, result.nodes)
            val text = if (result.skipped == 0) saved else UiText.Res(R.string.subs_list_saved_left_out, listOf(saved, result.skipped))
            withSubscriptions(text, result.subscriptions.size)
        }
        ConfigImportResult.SubscriptionUrls -> UiText.Res(R.string.subs_configs_is_subscription)
        is ConfigImportResult.NothingFound -> configError(result.error) ?: UiText.Res(R.string.subs_configs_nothing)
    }

    private fun withSubscriptions(configs: UiText, subscriptions: Int): UiText =
        if (subscriptions == 0) configs else UiText.Res(R.string.subs_configs_and_subscriptions, listOf(configs, UiText.Plural(R.plurals.subs_result_added, subscriptions)))

    /** Why a config could not be read; a malformed one names the field, in Russian when the parser's wording is known. */
    fun configError(error: ConfigError?): UiText? = when (error) {
        null -> null
        ConfigError.Empty -> UiText.Res(R.string.subs_config_error_empty)
        ConfigError.NotAConfig -> UiText.Res(R.string.subs_config_error_format)
        is ConfigError.Several -> UiText.Res(R.string.subs_config_error_several, listOf(error.count))
        is ConfigError.UnknownScheme -> UiText.Res(R.string.subs_config_error_scheme, listOf(error.scheme ?: "?"))
        is ConfigError.NotImported -> UiText.Res(R.string.subs_config_error_not_imported, listOf(error.scheme ?: "?"))
        ConfigError.Encrypted -> UiText.Res(R.string.subs_config_error_encrypted)
        is ConfigError.Malformed -> {
            val what = error.scheme ?: "?"
            val detail = error.detail?.let(ParserDetail::text)
            if (detail != null) {
                UiText.Res(R.string.subs_config_error_malformed_detail, listOf(what, detail))
            } else {
                UiText.Res(R.string.subs_config_error_malformed, listOf(what))
            }
        }
    }
}

/**
 * The parser's English detail, which names a field and never its value, in Russian.
 * Unknown wording yields null, so the message falls back to a generic one rather than showing English.
 */
internal object ParserDetail {
    private val MISSING = Regex("missing (.+)")
    private val NOT_BASE64 = Regex("(.+) is not base64")
    private val NOT_UUID = Regex("(.+) is not a UUID")
    private val BYTES = Regex("(.+) is not (\\d+) bytes")
    private val WITHOUT = Regex("(.+) without (.+)")

    private val EXACT = mapOf(
        "bad port" to R.string.subs_detail_bad_port,
        "bad port range" to R.string.subs_detail_bad_port_range,
        "bad finalmask range" to R.string.subs_detail_bad_port_range,
        "credentials are not method:password" to R.string.subs_detail_ss_credentials,
        "unterminated IPv6 literal" to R.string.subs_detail_ipv6,
        "junk after IPv6 literal" to R.string.subs_detail_ipv6,
        "no interface address" to R.string.subs_detail_no_address,
        "no peer" to R.string.subs_detail_no_peer,
        "dialer proxy loop" to R.string.subs_detail_chain,
        "dialer proxy target missing" to R.string.subs_detail_chain,
        "chain hop is not a proxy" to R.string.subs_detail_chain,
        "chain hop is not an object" to R.string.subs_detail_chain,
        "chain is not a list" to R.string.subs_detail_chain,
        "vmess payload is neither base64 JSON nor URL" to R.string.subs_detail_payload,
        "legacy payload is not base64" to R.string.subs_detail_payload,
        "userinfo is not base64" to R.string.subs_detail_payload,
        "vpn:// payload is not base64" to R.string.subs_detail_payload,
        "vpn:// profile is not JSON" to R.string.subs_detail_payload,
        "vpn:// payload is neither a .conf nor an Amnezia profile" to R.string.subs_detail_payload,
        "unknown type" to R.string.subs_detail_unknown_type,
    )

    private val FIELDS = mapOf(
        "uuid" to R.string.subs_detail_field_uuid,
        "id" to R.string.subs_detail_field_uuid,
        "tuic uuid" to R.string.subs_detail_field_uuid,
        "juicity uuid" to R.string.subs_detail_field_uuid,
        "password" to R.string.subs_detail_field_password,
        "credentials" to R.string.subs_detail_field_password,
        "server" to R.string.subs_detail_field_server,
        "host" to R.string.subs_detail_field_server,
        "address" to R.string.subs_detail_field_server,
        "port" to R.string.subs_detail_field_port,
        "type" to R.string.subs_detail_field_type,
        "method" to R.string.subs_detail_field_method,
        "cipher" to R.string.subs_detail_field_method,
        "private key" to R.string.subs_detail_field_private_key,
        "secret key" to R.string.subs_detail_field_private_key,
        "public key" to R.string.subs_detail_field_public_key,
        "peer public key" to R.string.subs_detail_field_peer_key,
        "peer pre-shared key" to R.string.subs_detail_field_psk,
        "REALITY public key" to R.string.subs_detail_field_reality_key,
        "shadowsocks 2022 key" to R.string.subs_detail_field_ss2022_key,
        "AmneziaWG header protection key" to R.string.subs_detail_field_awg_key,
        "settings" to R.string.subs_detail_field_settings,
        "REALITY" to R.string.subs_detail_field_reality,
        "reality" to R.string.subs_detail_field_reality,
        "peer" to R.string.subs_detail_field_peer,
        "salamander mask" to R.string.subs_detail_field_obfs,
        "gecko" to R.string.subs_detail_field_obfs,
        "salamander" to R.string.subs_detail_field_obfs,
        "wireguard" to R.string.subs_detail_field_wireguard,
    )

    fun text(detail: String): UiText? {
        EXACT[detail]?.let { return UiText.Res(it) }
        MISSING.matchEntire(detail)?.let { m -> return field(m.groupValues[1])?.let { UiText.Res(R.string.subs_detail_missing, listOf(it)) } }
        NOT_BASE64.matchEntire(detail)?.let { m -> return field(m.groupValues[1])?.let { UiText.Res(R.string.subs_detail_not_base64, listOf(it)) } }
        NOT_UUID.matchEntire(detail)?.let { m -> return field(m.groupValues[1])?.let { UiText.Res(R.string.subs_detail_not_uuid, listOf(it)) } }
        BYTES.matchEntire(detail)?.let { m ->
            return field(m.groupValues[1])?.let { UiText.Res(R.string.subs_detail_bytes, listOf(it, m.groupValues[2].toInt())) }
        }
        WITHOUT.matchEntire(detail)?.let { m ->
            val a = field(m.groupValues[1]) ?: return null
            val b = field(m.groupValues[2]) ?: return null
            return UiText.Res(R.string.subs_detail_without, listOf(a, b))
        }
        return null
    }

    private fun field(name: String): UiText? = FIELDS[name]?.let { UiText.Res(it) }
}
