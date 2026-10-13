package cc.hafa.reader.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.reader.R
import cc.hafa.reader.core.sync.RETENTION_CHOICES
import cc.hafa.reader.core.sync.RetentionDays
import cc.hafa.reader.core.sync.SyncStatus
import cc.hafa.reader.core.sync.avatarPhotoAddress
import cc.hafa.reader.sync.SyncConfig
import coil3.compose.AsyncImage

// the status dot's pulse, as on the web: down to this opacity and back over the whole time
private const val PULSE_ALPHA = 0.45f
private const val PULSE_MS = 1400
private const val AVATAR_WEIGHT = 650
// CSS ease-in-out
private val EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Words for a sync status; signed out reads as the way to sign in. */
@Composable
fun syncStatusText(status: SyncStatus): String =
    stringResource(
        when (status) {
            SyncStatus.Off -> R.string.sync_sign_in
            SyncStatus.Connecting -> R.string.sync_connecting
            SyncStatus.Syncing -> R.string.sync_syncing
            SyncStatus.Synced -> R.string.sync_synced
            SyncStatus.Offline -> R.string.sync_offline
            SyncStatus.Error -> R.string.sync_error
            SyncStatus.Waiting -> R.string.sync_waiting
        },
    )

/** A cloud for a sync status. */
@Composable
fun SyncStatusIcon(status: SyncStatus, tint: Color, size: Dp) {
    val icon = when (status) {
        SyncStatus.Connecting, SyncStatus.Syncing -> Icons.Outlined.CloudSync
        SyncStatus.Synced -> Icons.Outlined.CloudDone
        SyncStatus.Offline -> Icons.Outlined.CloudOff
        SyncStatus.Error -> Icons.Outlined.SyncProblem
        SyncStatus.Off, SyncStatus.Waiting -> Icons.Outlined.Cloud
    }
    Icon(icon, null, tint = tint, modifier = Modifier.size(size))
}

/** The color of a sync status's dot and icon: green synced, blue at work, amber needing attention, gray otherwise. */
@Composable
fun syncStatusColor(status: SyncStatus): Color {
    val colors = ReaderTheme.colors
    return when (status) {
        SyncStatus.Synced -> colors.ok
        SyncStatus.Syncing, SyncStatus.Connecting -> colors.accent
        SyncStatus.Error, SyncStatus.Waiting -> colors.star
        SyncStatus.Offline, SyncStatus.Off -> colors.muted
    }
}

/**
 * The signed-in account as a round badge: its photo, over its email's first
 * letter, which shows while the photo loads, when there is none or when it
 * fails; with a dot for the sync status, pulsing while at work. [large] is the
 * account screen's size, set against the panel.
 */
@Composable
fun AccountAvatar(email: String, photoUrl: String, status: SyncStatus, large: Boolean = false) {
    val colors = ReaderTheme.colors
    val size = if (large) 40.dp else 22.dp
    val dotSize = if (large) 13.dp else 9.dp
    val dotOffset = if (large) 1.dp else 2.dp
    val pulsing = animationsOn() && (status == SyncStatus.Syncing || status == SyncStatus.Connecting)
    val dotAlpha = if (pulsing) {
        val pulse = rememberInfiniteTransition(label = "sync dot")
        pulse.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_ALPHA,
            animationSpec = infiniteRepeatable(tween(PULSE_MS / 2, easing = EASE_IN_OUT), RepeatMode.Reverse),
            label = "sync dot alpha",
        ).value
    } else {
        1f
    }
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                email.take(1).uppercase(),
                color = colors.page,
                fontFamily = PublicSans,
                fontWeight = FontWeight(AVATAR_WEIGHT),
                fontSize = with(LocalDensity.current) { (size / 2).toSp() },
                lineHeight = with(LocalDensity.current) { (size / 2).toSp() },
            )
            val address = avatarPhotoAddress(photoUrl, with(LocalDensity.current) { size.roundToPx() })
            if (address.isNotEmpty()) {
                // draws nothing until loaded and on failure, so the letter stays visible then
                AsyncImage(model = address, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(dotOffset, dotOffset)
                .size(dotSize)
                .graphicsLayer { alpha = dotAlpha }
                .clip(CircleShape)
                .background(if (large) colors.panel else colors.side)
                .padding(2.dp)
                .clip(CircleShape)
                .background(syncStatusColor(status)),
        )
    }
}

/** Sign-in and sync: signed out, waiting for the account choice, or signed in; with the privacy policy and terms. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountScreen(viewModel: ReaderViewModel) {
    val colors = ReaderTheme.colors
    val sync by viewModel.syncUi.collectAsStateWithLifecycle()
    val report = sync.report
    var confirming by rememberConfirming()
    val signedInAs = buildAnnotatedString {
        append(stringResource(R.string.sync_signed_in_as))
        append(" ")
        withStyle(SpanStyle(color = colors.text)) { append(report.email) }
    }
    ReaderScaffold(
        bottomBar = { scrollBehavior -> BackBottomBar(stringResource(R.string.sync_heading), scrollBehavior, viewModel::back) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report.status == SyncStatus.Waiting) {
                Note(signedInAs)
                Text(stringResource(R.string.sync_switch_body), fontSize = 14.sp, lineHeight = 20.sp, color = colors.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton(stringResource(R.string.sync_switch_replace), onClick = viewModel::replaceLocal)
                    OutlineButton(stringResource(R.string.sync_switch_cancel), onClick = viewModel::signOut)
                }
            } else if (report.email.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountAvatar(report.email, report.photoUrl, report.status, large = true)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sync_signed_in_as), fontSize = 14.sp, lineHeight = 20.sp, color = colors.muted)
                        Text(report.email, fontSize = 14.sp, lineHeight = 20.sp, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncStatusIcon(report.status, syncStatusColor(report.status), 16.dp)
                    Text(syncStatusText(report.status), fontSize = 14.sp, lineHeight = 20.sp, color = colors.text)
                }
                if (report.loadingOlder) {
                    Note(AnnotatedString(stringResource(R.string.sync_loading_older)))
                }
                RetentionChoice(report.retention, viewModel::setRetention)
                OutlineButton(stringResource(R.string.sync_sign_out), onClick = viewModel::signOut, icon = Icons.AutoMirrored.Outlined.Logout)
                Note(AnnotatedString(stringResource(R.string.sync_delete_hint)))
                ConfirmButton(
                    label = stringResource(if (sync.deleting) R.string.sync_deleting else R.string.sync_delete_account),
                    confirmLabel = stringResource(R.string.sync_delete_confirm),
                    confirming = confirming && !sync.deleting,
                    enabled = !sync.deleting,
                    onClick = {
                        if (confirming) {
                            confirming = false
                            viewModel.deleteAccount()
                        } else {
                            confirming = true
                        }
                    },
                )
                if (sync.deleteFailed) {
                    ErrorNote(stringResource(R.string.sync_delete_failed))
                }
            } else {
                Note(AnnotatedString(stringResource(R.string.sync_explain)))
                OutlineButton(
                    stringResource(R.string.sync_sign_in),
                    onClick = viewModel::signIn,
                    icon = Icons.AutoMirrored.Outlined.Login,
                    enabled = report.status != SyncStatus.Connecting,
                )
                if (sync.signInFailed) {
                    ErrorNote(stringResource(R.string.sync_sign_in_failed))
                }
            }
            Policies()
        }
    }
}

/** Why the last account action did not work, in the error color and announced when it appears. */
@Composable
private fun ErrorNote(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.error,
    )
}

/** How long the account keeps synced articles, chosen from the offered lengths. */
@Composable
private fun RetentionChoice(retention: RetentionDays, onChange: (RetentionDays) -> Unit) {
    val labels = mapOf(
        30 to stringResource(R.string.retention_30),
        90 to stringResource(R.string.retention_90),
        365 to stringResource(R.string.retention_365),
        null to stringResource(R.string.retention_forever),
    )
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.retention_label), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
        Segmented(RETENTION_CHOICES.map { choice -> choice to labels.getValue(choice) }, retention, onChange, Modifier.fillMaxWidth())
        Note(AnnotatedString(stringResource(R.string.retention_note)))
    }
}

@Composable
private fun Note(text: AnnotatedString) {
    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = ReaderTheme.colors.muted)
}

@Composable
private fun Policies() {
    val links = LocalUriHandler.current
    val colors = ReaderTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, address) in listOf(R.string.privacy to SyncConfig.PRIVACY_URL, R.string.terms to SyncConfig.TERMS_URL)) {
            TextButton(onClick = { links.openUri(address) }, colors = ButtonDefaults.textButtonColors(contentColor = colors.muted)) {
                Text(stringResource(label), textDecoration = TextDecoration.Underline)
            }
        }
    }
}
