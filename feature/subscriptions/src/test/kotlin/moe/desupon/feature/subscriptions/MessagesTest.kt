package moe.desupon.feature.subscriptions

import moe.desupon.data.fetch.FetchError
import moe.desupon.data.repo.AddResult
import moe.desupon.data.repo.ConfigImportResult
import moe.desupon.data.repo.UpdateResult
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.ConfigError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagesTest {
    private fun res(id: Int, vararg args: Any) = UiText.Res(id, args.toList())

    @Test
    fun `no code means no error`() {
        assertNull(Messages.error(null))
        assertNull(Messages.error(""))
    }

    @Test
    fun `every fetch error code has its own text`() {
        val expected = mapOf(
            FetchError.Timeout to R.string.subs_error_timeout,
            FetchError.Network to R.string.subs_error_network,
            FetchError.EmptyBody to R.string.subs_error_empty,
            FetchError.TooLarge to R.string.subs_error_too_large,
            FetchError.Rejected to R.string.subs_error_no_nodes,
            FetchError.NoEndpoints to R.string.subs_error_no_endpoints,
        )
        for ((error, id) in expected) assertEquals(res(id), Messages.error(error.code), error.code)
    }

    @Test
    fun `http statuses are grouped and keep the status`() {
        assertEquals(res(R.string.subs_error_unauthorized, 401), Messages.error(FetchError.Http(401).code))
        assertEquals(res(R.string.subs_error_unauthorized, 403), Messages.error("HTTP_403"))
        assertEquals(res(R.string.subs_error_not_found, 404), Messages.error("HTTP_404"))
        assertEquals(res(R.string.subs_error_not_found, 410), Messages.error("HTTP_410"))
        assertEquals(res(R.string.subs_error_rate_limited, 429), Messages.error("HTTP_429"))
        assertEquals(res(R.string.subs_error_server, 500), Messages.error("HTTP_500"))
        assertEquals(res(R.string.subs_error_server, 503), Messages.error("HTTP_503"))
        assertEquals(res(R.string.subs_error_http, 418), Messages.error("HTTP_418"))
    }

    @Test
    fun `unknown or garbled codes never echo the raw value`() {
        for (code in listOf("HTTP_", "HTTP_abc", "SOMETHING_NEW", "https://example.invalid/sub/token123")) {
            assertEquals(res(R.string.subs_error_unknown), Messages.error(code), code)
        }
    }

    @Test
    fun `update results`() {
        assertEquals(UiText.Plural(R.plurals.subs_result_updated, 7), Messages.update(UpdateResult.Updated(7, 2)))
        assertEquals(res(R.string.subs_error_timeout), Messages.update(UpdateResult.Failed(FetchError.Timeout)))
        assertEquals(res(R.string.subs_result_throttled), Messages.update(UpdateResult.Throttled(null)))
        assertEquals(res(R.string.subs_result_local), Messages.update(UpdateResult.Local))
        assertEquals(res(R.string.subs_result_not_found), Messages.update(UpdateResult.NotFound))
        assertNull(Messages.update(UpdateResult.NotDue))
    }

    @Test
    fun `update all summarizes attempted subscriptions`() {
        assertEquals(res(R.string.subs_result_all_none), Messages.updateAll(emptyMap()))
        val ok = mapOf(SubId(1) to UpdateResult.Updated(3, 0), SubId(2) to UpdateResult.Updated(1, 0))
        assertEquals(res(R.string.subs_result_all_ok, 2, 2), Messages.updateAll(ok))
        val mixed = ok + (SubId(3) to UpdateResult.Failed(FetchError.Http(403))) + (SubId(4) to UpdateResult.Throttled(1L))
        assertEquals(res(R.string.subs_result_all_partial, 2, 4, 1), Messages.updateAll(mixed))
    }

    @Test
    fun `add results`() {
        assertEquals(res(R.string.subs_add_nothing), Messages.add(AddResult.NothingFound))
        assertEquals(res(R.string.subs_add_exists), Messages.add(AddResult.AlreadyExists(SubId(1))))
        assertEquals(res(R.string.subs_add_exists), Messages.add(AddResult.Added(emptyMap())))
        assertEquals(res(R.string.subs_add_looks_like_configs, 4), Messages.add(AddResult.LooksLikeConfigs(4)))
        assertEquals(res(R.string.subs_add_mixed, 1, 3), Messages.add(AddResult.LooksLikeConfigs(configs = 3, urls = 1)))
        assertEquals(
            UiText.Plural(R.plurals.subs_result_added, 2),
            Messages.add(AddResult.Added(mapOf(SubId(1) to UpdateResult.Updated(1, 0), SubId(2) to UpdateResult.Updated(2, 0)))),
        )
        assertEquals(
            res(R.string.subs_result_added_failed, res(R.string.subs_error_unauthorized, 403)),
            Messages.add(AddResult.Added(mapOf(SubId(1) to UpdateResult.Failed(FetchError.Http(403))))),
        )
    }

    @Test
    fun `config import results`() {
        val two = UiText.Plural(R.plurals.subs_configs_added, 2)
        assertEquals(two, Messages.configs(ConfigImportResult.Imported(listOf(SubId(1), SubId(2)), 2, 0, 0)))
        assertEquals(
            res(R.string.subs_configs_added_left_out, two, 1, 3),
            Messages.configs(ConfigImportResult.Imported(listOf(SubId(1), SubId(2)), 2, 1, 3)),
        )
        assertEquals(res(R.string.subs_configs_all_saved), Messages.configs(ConfigImportResult.Imported(emptyList(), 0, 2, 0)))
        assertEquals(res(R.string.subs_configs_is_subscription), Messages.configs(ConfigImportResult.SubscriptionUrls))
        assertEquals(res(R.string.subs_configs_nothing), Messages.configs(ConfigImportResult.NothingFound(null)))
        assertEquals(res(R.string.subs_config_error_format), Messages.configs(ConfigImportResult.NothingFound(ConfigError.NotAConfig)))

        val oneSub = mapOf(SubId(9) to UpdateResult.Updated(1, 0))
        assertEquals(
            res(R.string.subs_configs_and_subscriptions, two, UiText.Plural(R.plurals.subs_result_added, 1)),
            Messages.configs(ConfigImportResult.Imported(listOf(SubId(1), SubId(2)), 2, 0, 0, oneSub)),
        )
        val list = UiText.Plural(R.plurals.subs_list_saved, 7)
        assertEquals(list, Messages.configs(ConfigImportResult.Listed(SubId(3), 7, 0)))
        assertEquals(res(R.string.subs_list_saved_left_out, list, 2), Messages.configs(ConfigImportResult.Listed(SubId(3), 7, 2)))
    }

    @Test
    fun `config errors name the field in Russian but carry no content`() {
        assertEquals(
            res(R.string.subs_config_error_malformed_detail, "vless", res(R.string.subs_detail_missing, res(R.string.subs_detail_field_uuid))),
            Messages.configError(ConfigError.Malformed("vless", "missing uuid")),
        )
        assertEquals(
            res(R.string.subs_detail_bytes, res(R.string.subs_detail_field_reality_key), 32),
            ParserDetail.text("REALITY public key is not 32 bytes"),
        )
        assertEquals(res(R.string.subs_detail_not_uuid, res(R.string.subs_detail_field_uuid)), ParserDetail.text("tuic uuid is not a UUID"))
        assertEquals(
            res(R.string.subs_detail_without, res(R.string.subs_detail_field_obfs), res(R.string.subs_detail_field_password)),
            ParserDetail.text("gecko without password"),
        )
        assertEquals(res(R.string.subs_detail_bad_port_range), ParserDetail.text("bad port range"))
        assertNull(ParserDetail.text("something the parser may say later"))
        assertEquals(res(R.string.subs_config_error_malformed, "ss"), Messages.configError(ConfigError.Malformed("ss", "unheard of wording")))
        assertEquals(res(R.string.subs_config_error_malformed, "?"), Messages.configError(ConfigError.Malformed(null, null)))
        assertEquals(res(R.string.subs_config_error_several, 3), Messages.configError(ConfigError.Several(3)))
        assertEquals(res(R.string.subs_config_error_scheme, "anytls"), Messages.configError(ConfigError.UnknownScheme("anytls")))
        assertNull(Messages.configError(null))
    }

    @Test
    fun `editor save messages`() {
        assertEquals(res(R.string.subs_editor_saved), Messages.saved(null))
        assertEquals(res(R.string.subs_editor_saved), Messages.saved(UpdateResult.NotDue))
        assertEquals(
            res(R.string.subs_editor_saved_with, UiText.Plural(R.plurals.subs_result_updated, 3)),
            Messages.saved(UpdateResult.Updated(3, 0)),
        )
        assertEquals(res(R.string.subs_editor_local_no_nodes), Messages.localText(UpdateResult.Failed(FetchError.Rejected)))
    }
}
