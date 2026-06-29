package com.rd.rd_app.ui.screen.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rd.rd_app.ConfigManager

@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    viewModel: ConfigViewModel = viewModel()
) {
    val apiUrl by viewModel.apiUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            if (!isEditing) {
                // Read-only view
                ConfigReadOnlySection(
                    apiUrl = ConfigManager.apiUrl,
                    apiKey = ConfigManager.apiKey,
                    modelName = ConfigManager.modelName,
                    onEditClick = viewModel::startEditing
                )
            } else {
                // Edit mode
                ConfigEditSection(
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    modelName = modelName,
                    onApiUrlChange = viewModel::updateApiUrl,
                    onApiKeyChange = viewModel::updateApiKey,
                    onModelNameChange = viewModel::updateModelName,
                    onSave = viewModel::saveConfig,
                    onCancel = viewModel::cancelEditing
                )
            }
        }
    }
}

@Composable
private fun ConfigReadOnlySection(
    apiUrl: String,
    apiKey: String,
    modelName: String,
    onEditClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = "API 配置",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        ConfigInfoRow(
            icon = Icons.Default.Api,
            label = "API 地址",
            value = apiUrl.ifBlank { "未设置" }
        )
        HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

        ConfigInfoRow(
            icon = Icons.Default.Key,
            label = "API 密钥",
            value = if (apiKey.isNotBlank()) "••••••••••••••••" else "未设置"
        )
        HorizontalDivider(modifier = Modifier.padding(start = 48.dp))

        ConfigInfoRow(
            icon = Icons.Default.SmartToy,
            label = "模型名称",
            value = modelName.ifBlank { "未设置" }
        )

        Spacer(modifier = Modifier.height(24.dp))

        FilledTonalButton(
            onClick = onEditClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("修改配置")
        }
    }
}

@Composable
private fun ConfigInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConfigEditSection(
    apiUrl: String,
    apiKey: String,
    modelName: String,
    onApiUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "编辑配置",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = apiUrl,
            onValueChange = onApiUrlChange,
            label = { Text("API 地址") },
            placeholder = { Text("https://api.deepseek.com") },
            leadingIcon = { Icon(Icons.Default.Api, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API 密钥") },
            placeholder = { Text("sk-...") },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = modelName,
            onValueChange = onModelNameChange,
            label = { Text("模型名称") },
            placeholder = { Text("deepseek-chat") },
            leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("取消")
            }

            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存")
            }
        }
    }
}

