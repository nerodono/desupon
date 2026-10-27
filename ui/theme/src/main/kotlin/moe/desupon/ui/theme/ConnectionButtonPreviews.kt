package moe.desupon.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun PreviewFrame(
    themeMode: ThemeMode = ThemeMode.Dark,
    desuMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    DesuponTheme(themeMode = themeMode, desuMode = desuMode) {
        Surface { Box(Modifier.padding(32.dp)) { content() } }
    }
}

@Preview(name = "Disconnected")
@Composable
private fun DisconnectedPreview() = PreviewFrame {
    ConnectionButton(ConnectionButtonState.Disconnected, onClick = {})
}

@Preview(name = "Checking 2/3")
@Composable
private fun CheckingPreview() = PreviewFrame {
    ConnectionButton(
        ConnectionButtonState.Checking(listOf(true, false, true)),
        onClick = {},
        label = "Проверяю 12/48 · 3 подписки",
    )
}

@Preview(name = "Connecting")
@Composable
private fun ConnectingPreview() = PreviewFrame {
    ConnectionButton(ConnectionButtonState.Connecting, onClick = {}, label = "Подключаюсь к Амстердам-2")
}

@Preview(name = "Connected")
@Composable
private fun ConnectedPreview() = PreviewFrame {
    ConnectionButton(
        ConnectionButtonState.Connected,
        onClick = {},
        label = "Амстердам-2 · из «Сад»",
        timer = { ConnectionTimer(elapsedSeconds = 1745) },
    )
}

@Preview(name = "Switching")
@Composable
private fun SwitchingPreview() = PreviewFrame {
    ConnectionButton(ConnectionButtonState.Switching, onClick = {}, label = "Амстердам-2 не отвечает → Хельсинки-1")
}

@Preview(name = "Error")
@Composable
private fun ErrorPreview() = PreviewFrame {
    ConnectionButton(ConnectionButtonState.Error, onClick = {}, label = "Ни один из 48 серверов не ответил")
}

@Preview(name = "Connected, light")
@Composable
private fun ConnectedLightPreview() = PreviewFrame(themeMode = ThemeMode.Light) {
    ConnectionButton(
        ConnectionButtonState.Connected,
        onClick = {},
        timer = { ConnectionTimer(elapsedSeconds = 3_725) },
    )
}

@Preview(name = "Disconnected, desu")
@Composable
private fun DisconnectedDesuPreview() = PreviewFrame(desuMode = true) {
    ConnectionButton(ConnectionButtonState.Disconnected, onClick = {})
}
