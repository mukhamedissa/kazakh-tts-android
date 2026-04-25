package io.github.mukhamedissa.kazakhtts.sample.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private const val MAX_INPUT_LENGTH = 2_000

@Composable
fun TextInputField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= MAX_INPUT_LENGTH) onValueChange(it) },
        label = { Text("Kazakh text") },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 10,
    )
}
