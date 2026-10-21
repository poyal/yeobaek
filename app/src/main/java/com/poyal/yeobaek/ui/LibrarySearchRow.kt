package com.poyal.yeobaek.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The folder and recent lists share the same selector and search field geometry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun <T> LibrarySearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    options: List<Pair<T, String>>,
    selection: T,
    onSelectionChange: (T) -> Unit,
    selectorTag: String,
    selectorDescription: String,
    optionTag: (T) -> String,
    searchTag: String,
    searchDescription: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal)
    val shape = RoundedCornerShape(4.dp)
    val colors = OutlinedTextFieldDefaults.colors()
    val measurer = rememberTextMeasurer()
    val textWidth = options.maxOf { measurer.measure(AnnotatedString(it.second), style).size.width }
    val selectorWidth = with(LocalDensity.current) { textWidth.toDp() } + 72.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = selectorWidth.coerceAtMost(maxWidth / 2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = options.first { it.first == selection }.second,
                    onValueChange = {}, readOnly = true,
                    modifier = Modifier.width(width).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .testTag(selectorTag).semantics { contentDescription = selectorDescription },
                    singleLine = true, textStyle = style, shape = shape, colors = colors,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                    options.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) },
                            trailingIcon = { if (selection == value) Icon(Icons.Outlined.Check, null) },
                            modifier = Modifier.testTag(optionTag(value)).semantics { selected = selection == value },
                            onClick = { onSelectionChange(value); expanded = false })
                    }
                }
            }
            OutlinedTextField(
                value = query, onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).testTag(searchTag).semantics { contentDescription = searchDescription },
                singleLine = true, textStyle = style, shape = shape, colors = colors,
                placeholder = { Text("파일명 검색", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, "검색 지우기")
                    }
                },
            )
        }
    }
}
