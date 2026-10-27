package moe.desupon.feature.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.desupon.ui.theme.ConnectionButton
import moe.desupon.ui.theme.ConnectionButtonState
import moe.desupon.ui.theme.ConnectionTimer
import moe.desupon.ui.theme.DesuponGlyphs
import moe.desupon.ui.theme.DesuponTheme
import moe.desupon.ui.theme.LocalDesuMode
import moe.desupon.ui.theme.desuStringResource
import moe.desupon.ui.theme.tabular
import org.koin.compose.viewmodel.koinViewModel

/**
 * Main screen of the full UI. Window insets are the caller's concern.
 * [onManageSets] opens subscription-set management; the sheet hides its "+" chip while it is null.
 */
@Composable
fun HomeScreen(onAddSubscription: () -> Unit, modifier: Modifier = Modifier, onManageSets: (() -> Unit)? = null) {
    val viewModel = koinViewModel<HomeViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val desu by rememberUpdatedState(LocalDesuMode.current)
    val deniedText = stringResource(R.string.home_vpn_denied)
    val unavailableText = stringResource(R.string.home_vpn_unavailable)
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    val requestConnect = rememberVpnConsent(
        onGranted = viewModel::connect,
        onDenied = { scope.launch { snackbar.showSnackbar(deniedText) } },
        onUnavailable = { scope.launch { snackbar.showSnackbar(unavailableText) } },
    )
    val connect by rememberUpdatedState(requestConnect)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { viewModel.runProbeCadence() }
            viewModel.events.collect { event ->
                when (event) {
                    HomeEvent.Connect -> connect()
                    HomeEvent.LocationRejected -> launch { snackbar.showSnackbar(resources.getString(R.string.home_location_rejected)) }
                    is HomeEvent.ProbeThrottled -> Toast.makeText(
                        context,
                        resources.getString(R.string.home_probe_throttled, event.seconds.toInt()),
                        Toast.LENGTH_SHORT,
                    ).show()
                    is HomeEvent.PinFellBack -> {
                        val target = event.country ?: resources.getString(R.string.home_location_any)
                        val text = resources.getString(if (desu) R.string.home_pin_fallback_desu else R.string.home_pin_fallback, target)
                        launch { snackbar.showSnackbar(text) }
                    }
                }
            }
        }
    }

    Box(modifier) {
        HomeContent(
            state = state,
            onConnect = requestConnect,
            onDisconnect = viewModel::disconnect,
            onAddSubscription = onAddSubscription,
            onOpenLocations = { sheetOpen = true },
            onAnyCountry = viewModel::useAnyCountry,
            modifier = Modifier.fillMaxSize(),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    LaunchedEffect(viewModel, sheetOpen) { viewModel.setSheetOpen(sheetOpen) }
    if (sheetOpen) {
        val sheet by viewModel.sheet.collectAsStateWithLifecycle()
        LocationSheet(
            state = sheet,
            onDismiss = { sheetOpen = false },
            onRefresh = viewModel::refreshProbes,
            onSelectSet = viewModel::selectSet,
            onManageSets = onManageSets,
            onSelect = viewModel::selectLocation,
        )
    }
}

/** Runs [onGranted] once VPN consent is held, asking the system first when it is not. */
@Composable
internal fun rememberVpnConsent(onGranted: () -> Unit, onDenied: () -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val denied by rememberUpdatedState(onDenied)
    val unavailable by rememberUpdatedState(onUnavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) granted() else denied()
    }
    return remember(context, launcher) {
        {
            val consent = VpnService.prepare(context)
            if (consent == null) {
                granted()
            } else {
                try {
                    launcher.launch(consent)
                } catch (_: ActivityNotFoundException) {
                    unavailable()
                }
            }
        }
    }
}

@Composable
internal fun HomeContent(
    state: HomeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAddSubscription: () -> Unit,
    onOpenLocations: () -> Unit,
    onAnyCountry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        HomeUiState.Loading -> Box(modifier)
        HomeUiState.Empty -> EmptyState(onAddSubscription, modifier)
        is HomeUiState.Ready -> ReadyState(state, onConnect, onDisconnect, onOpenLocations, onAnyCountry, modifier)
    }
}

@Composable
private fun ReadyState(
    state: HomeUiState.Ready,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenLocations: () -> Unit,
    onAnyCountry: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val buttonSize = minOf(maxWidth * 0.6f, maxHeight * 0.4f, 240.dp).coerceAtLeast(140.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val start = state.sessionStartMs
                ConnectionButton(
                    state = state.button,
                    onClick = if (state.action == ButtonAction.CONNECT) onConnect else onDisconnect,
                    label = state.caption?.let { captionText(it) },
                    size = buttonSize,
                    timer = start?.let { { SessionTimer(it) } },
                )
                if (state.showRetry || state.offerAnyCountry) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.showRetry) {
                            AssistChip(
                                onClick = onConnect,
                                label = { Text(desuStringResource(R.string.home_retry, R.string.home_retry_desu)) },
                                leadingIcon = { Icon(DesuponGlyphs.Drop, null, Modifier.size(AssistChipDefaults.IconSize)) },
                            )
                        }
                        if (state.offerAnyCountry) {
                            AssistChip(
                                onClick = onAnyCountry,
                                label = { Text(stringResource(R.string.home_location_any)) },
                                leadingIcon = { Icon(HomeIcons.Bolt, null, Modifier.size(AssistChipDefaults.IconSize)) },
                            )
                        }
                    }
                }
                if (state.traffic != null || state.expiry != null) {
                    Spacer(Modifier.height(32.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.traffic?.let {
                            InfoCard(HomeIcons.Traffic, stringResource(R.string.home_card_traffic), trafficText(it), Modifier.weight(1f))
                        }
                        state.expiry?.let {
                            InfoCard(
                                icon = HomeIcons.Calendar,
                                title = stringResource(R.string.home_card_subscription),
                                value = expiryText(it),
                                modifier = Modifier.weight(1f),
                                valueColor = when {
                                    it.expired -> MaterialTheme.colorScheme.error
                                    it.endingSoon -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
        LocationRow(state.location, onOpenLocations, Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
}

@Composable
private fun SessionTimer(startMs: Long) {
    val now by produceState(System.currentTimeMillis(), startMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - value % 1000)
        }
    }
    ConnectionTimer((now - startMs) / 1000)
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.tabular,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LocationRow(summary: LocationSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val place = summary.place
                when {
                    place is Place.Named && place.flag != null -> Text(place.flag, fontSize = 28.sp)
                    place == Place.AnyCountry -> Icon(HomeIcons.Bolt, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    else -> Icon(DesuponGlyphs.Sprout, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val name = when (val place = summary.place) {
                    Place.AnyCountry -> stringResource(R.string.home_location_any)
                    is Place.Named -> place.name
                    Place.UnknownNode -> stringResource(R.string.home_location_unknown_node)
                }
                Text(
                    text = if (summary.auto) stringResource(R.string.home_location_auto, name) else name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val parts = listOfNotNull(
                    AnnotatedString(summary.setName ?: stringResource(R.string.home_set_all)),
                    summary.chain?.let { chainText(it) },
                    summary.subscription?.let { AnnotatedString(stringResource(R.string.home_server_from, subscriptionTitle(it))) },
                    if (summary.showHint) AnnotatedString(desuStringResource(R.string.home_location_hint, R.string.home_location_hint_desu)) else null,
                )
                Text(
                    text = buildAnnotatedString {
                        parts.forEachIndexed { i, part ->
                            if (i > 0) append(PART_SEPARATOR)
                            append(part)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(HomeIcons.Chevron, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(onAddSubscription: () -> Unit, modifier: Modifier) = BoxWithConstraints(modifier) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(DesuponGlyphs.WateringCan, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text(
            text = desuStringResource(R.string.home_empty_title, R.string.home_empty_title_desu),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = desuStringResource(R.string.home_empty_body, R.string.home_empty_body_desu),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddSubscription) { Text(stringResource(R.string.home_empty_action)) }
    }
}

@Composable
private fun captionText(caption: Caption): String = when (caption) {
    Caption.TurnWarmup -> desuStringResource(R.string.home_caption_turn, R.string.home_caption_turn_desu)
    Caption.Degraded -> desuStringResource(R.string.home_caption_degraded, R.string.home_caption_degraded_desu)
    Caption.Unstable -> desuStringResource(R.string.home_caption_unstable, R.string.home_caption_unstable_desu)
    Caption.FailConfig -> desuStringResource(R.string.home_caption_fail_config, R.string.home_caption_fail_config_desu)
    Caption.FailCore -> desuStringResource(R.string.home_caption_fail_core, R.string.home_caption_fail_core_desu)
    Caption.FailNotPrepared ->
        desuStringResource(R.string.home_caption_fail_not_prepared, R.string.home_caption_fail_not_prepared_desu)
    Caption.FailStartBlocked ->
        desuStringResource(R.string.home_caption_fail_start_blocked, R.string.home_caption_fail_start_blocked_desu)
    Caption.FailUnknown -> desuStringResource(R.string.home_caption_fail_unknown, R.string.home_caption_fail_unknown_desu)
    is Caption.CountryUnavailable -> caption.country?.let {
        desuStringResource(R.string.home_caption_country_unavailable, R.string.home_caption_country_unavailable_desu, it)
    } ?: desuStringResource(R.string.home_caption_country_unavailable_generic, R.string.home_caption_country_unavailable_generic_desu)
    Caption.NodeUnavailable ->
        desuStringResource(R.string.home_caption_node_unavailable, R.string.home_caption_node_unavailable_desu)
}

@Composable
private fun amountText(amount: ByteAmount): String {
    val unit = stringResource(
        when (amount.unit) {
            ByteUnit.B -> R.string.home_unit_b
            ByteUnit.KB -> R.string.home_unit_kb
            ByteUnit.MB -> R.string.home_unit_mb
            ByteUnit.GB -> R.string.home_unit_gb
            ByteUnit.TB -> R.string.home_unit_tb
        },
    )
    return stringResource(R.string.home_amount, amount.value, unit)
}

@Composable
private fun trafficText(card: TrafficCard): String = stringResource(
    R.string.home_traffic_value,
    amountText(card.used),
    card.total?.let { amountText(it) } ?: stringResource(R.string.home_traffic_unlimited),
)

@Composable
private fun expiryText(card: ExpiryCard): String =
    if (card.expired) stringResource(R.string.home_expired)
    else pluralStringResource(R.plurals.home_days_left, card.daysLeft, card.daysLeft)

@Preview
@Composable
private fun HomeConnectedPreview() {
    DesuponTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeContent(
                state = HomeUiState.Ready(
                    button = ConnectionButtonState.Connected,
                    caption = null,
                    action = ButtonAction.DISCONNECT,
                    sessionStartMs = System.currentTimeMillis() - 29 * 60_000L,
                    location = LocationSummary(
                        Place.Named(flagEmoji("SG"), "Сингапур 3"), true, null, null, false,
                        ChainLine(listOf(SubscriptionLabel("Финка", 1), SubscriptionLabel("OverSecure", 2)), active = 0),
                    ),
                    traffic = TrafficCard(ByteAmount("65,6", ByteUnit.GB), null),
                    expiry = ExpiryCard(21),
                    showRetry = false,
                ),
                onConnect = {},
                onDisconnect = {},
                onAddSubscription = {},
                onOpenLocations = {},
                onAnyCountry = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview
@Composable
private fun HomeFailedPreview() {
    DesuponTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeContent(
                state = HomeUiState.Ready(
                    button = ConnectionButtonState.Error,
                    caption = Caption.CountryUnavailable("Нидерланды"),
                    action = ButtonAction.CONNECT,
                    sessionStartMs = null,
                    location = LocationSummary(Place.Named(flagEmoji("NL"), "Нидерланды"), false, "Работа", null, true),
                    traffic = TrafficCard(ByteAmount("12", ByteUnit.GB), ByteAmount("100", ByteUnit.GB)),
                    expiry = ExpiryCard(2),
                    showRetry = true,
                    offerAnyCountry = true,
                ),
                onConnect = {},
                onDisconnect = {},
                onAddSubscription = {},
                onOpenLocations = {},
                onAnyCountry = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview
@Composable
private fun HomeEmptyPreview() {
    DesuponTheme(desuMode = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeContent(HomeUiState.Empty, {}, {}, {}, {}, {}, Modifier.fillMaxSize())
        }
    }
}
