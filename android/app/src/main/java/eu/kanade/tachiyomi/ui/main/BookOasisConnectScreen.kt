package eu.kanade.tachiyomi.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.bookoasis.BookOasisConnection
import eu.kanade.tachiyomi.source.bookoasis.BookOasisConnectionResult
import eu.kanade.tachiyomi.source.bookoasis.BookOasisSource
import kotlinx.coroutines.launch

private fun normalizedLibraryType(value: String): String =
    value.trim().lowercase().takeIf { it in setOf("general", "adult", "both") } ?: "general"

internal class BookOasisConnectScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val source = remember { BookOasisSource(1) }
        val prefs = remember { source.getSourcePreferences() }
        val scope = rememberCoroutineScope()
        var address by remember { mutableStateOf(prefs.getString("address", "").orEmpty()) }
        var username by remember { mutableStateOf(prefs.getString("username", "").orEmpty()) }
        var password by remember { mutableStateOf("") }
        var library by remember { mutableStateOf(prefs.getString("library_type", "general") ?: "general") }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var connected by remember { mutableStateOf(address.isNotBlank()) }

        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Connect to BookOasis", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter your server and account. Connection check only reads the catalog; " +
                        "credentials are saved only after it succeeds.",
                )
                OutlinedTextField(
                    address,
                    {
                        address = it
                        error = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("Server URL") },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    username,
                    {
                        username = it
                        error = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !loading,
                )
                OutlinedTextField(
                    password,
                    {
                        password = it
                        error = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    library,
                    {
                        library = it
                        error = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text("Library (general, adult, or both)") },
                    singleLine = true,
                    enabled = !loading,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (loading) CircularProgressIndicator()
                Button(
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            when (BookOasisConnection.validate(source.client, address, username, password)) {
                                BookOasisConnectionResult.Connected -> {
                                    prefs.edit()
                                        .putString(
                                            "address",
                                            BookOasisSource.normalizeServerRoot(address),
                                        )
                                        .putString("username", username)
                                        .putString("password", password)
                                        .putString("library_type", normalizedLibraryType(library))
                                        .putBoolean(DISMISSED_KEY, true)
                                        .apply()
                                    connected = true
                                    password = ""
                                    navigator.pop()
                                }
                                BookOasisConnectionResult.InvalidUrl ->
                                    error = "Enter a valid server URL (without a username or password in the URL)."
                                BookOasisConnectionResult.Unreachable ->
                                    error = "Could not reach the server. Check the address and network, then try again."
                                BookOasisConnectionResult.AuthenticationRejected ->
                                    error =
                                        "The server rejected the account (401/403). " +
                                        "Check the username and password."
                                BookOasisConnectionResult.UnexpectedResponse ->
                                    error =
                                        "The server returned an unexpected response. " +
                                        "Check the URL and server compatibility."
                            }
                            loading = false
                        }
                    },
                    enabled = !loading && address.isNotBlank() && username.isNotBlank(),
                ) { Text(if (connected) "Reconnect" else "Connect") }
                if (connected) {
                    OutlinedButton(onClick = {
                        prefs.edit()
                            .remove("address")
                            .remove("username")
                            .remove("password")
                            .putBoolean(DISMISSED_KEY, true)
                            .apply()
                        address = ""
                        username = ""
                        password = ""
                        connected = false
                        error =
                            "BookOasis credentials and server address were removed. " +
                            "Other profile settings were kept."
                    }, enabled = !loading) { Text("Log out and remove saved connection") }
                }
                OutlinedButton(
                    onClick = {
                        prefs.edit().putBoolean(DISMISSED_KEY, true).apply()
                        navigator.pop()
                    },
                    enabled = !loading,
                ) { Text("Skip for now") }
            }
        }
    }

    companion object {
        const val DISMISSED_KEY = "bookoasis_onboarding_dismissed"
        const val FIRST_BROWSE_OPENED_KEY = "bookoasis_first_browse_opened"
    }
}
