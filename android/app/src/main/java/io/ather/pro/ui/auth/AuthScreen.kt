package io.ather.pro.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.ather.pro.data.auth.AuthStep
import io.ather.pro.data.auth.AuthUiState
import io.ather.pro.data.auth.DiscoveredScooter

@Composable
fun AuthScreen(
    state: AuthUiState,
    onPhoneChanged: (String) -> Unit,
    onOtpChanged: (String) -> Unit,
    onRequestOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onSelectScooter: (DiscoveredScooter) -> Unit,
    onRetryScooters: () -> Unit,
    onBackToPhone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Athr+",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = when (state.step) {
                AuthStep.PHONE -> "Sign in with your Ather mobile number"
                AuthStep.OTP -> "Enter the OTP sent by SMS"
                AuthStep.SCOOTER_SELECT -> "Select your scooter"
                AuthStep.READY -> "Signed in"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (state.step) {
            AuthStep.PHONE -> PhoneStep(
                phone = state.phone,
                isLoading = state.isLoading,
                onPhoneChanged = onPhoneChanged,
                onRequestOtp = onRequestOtp
            )

            AuthStep.OTP -> OtpStep(
                phone = state.phone,
                otp = state.otp,
                isLoading = state.isLoading,
                onOtpChanged = onOtpChanged,
                onVerifyOtp = onVerifyOtp,
                onBack = onBackToPhone
            )

            AuthStep.SCOOTER_SELECT -> ScooterStep(
                scooters = state.scooters,
                isLoading = state.isLoading,
                onSelectScooter = onSelectScooter,
                onRetry = onRetryScooters,
                onBack = onBackToPhone
            )

            AuthStep.READY -> Unit
        }

        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.primary
            )
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PhoneStep(
    phone: String,
    isLoading: Boolean,
    onPhoneChanged: (String) -> Unit,
    onRequestOtp: () -> Unit
) {
    OutlinedTextField(
        value = phone,
        onValueChange = onPhoneChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Mobile number") },
        placeholder = { Text("10-digit number") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        colors = authFieldColors()
    )
    Button(
        onClick = onRequestOtp,
        enabled = !isLoading && phone.length >= 10,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Text("Send OTP")
    }
}

@Composable
private fun OtpStep(
    phone: String,
    otp: String,
    isLoading: Boolean,
    onOtpChanged: (String) -> Unit,
    onVerifyOtp: () -> Unit,
    onBack: () -> Unit
) {
    Text(
        text = "Sent to +91 $phone",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = otp,
        onValueChange = onOtpChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("OTP") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = authFieldColors()
    )
    Button(
        onClick = onVerifyOtp,
        enabled = !isLoading && otp.length >= 4,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Verify & continue")
    }
    TextButton(onClick = onBack, enabled = !isLoading) {
        Text("Use a different number")
    }
}

@Composable
private fun ScooterStep(
    scooters: List<DiscoveredScooter>,
    isLoading: Boolean,
    onSelectScooter: (DiscoveredScooter) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    if (scooters.isEmpty() && !isLoading) {
        Text(
            text = "No scooters were found on this Ather account. Pull them again, or start over with another number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onRetry,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retry")
        }
    } else {
        Text(
            text = "Tap your scooter to continue",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        scooters.forEach { scooter ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isLoading) { onSelectScooter(scooter) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = scooter.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    listOfNotNull(scooter.modelType, scooter.registration, scooter.colour)
                        .forEach { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                }
            }
        }
        if (scooters.isNotEmpty()) {
            TextButton(
                onClick = onRetry,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh list")
            }
        }
    }
    TextButton(onClick = onBack, enabled = !isLoading) {
        Text("Start over")
    }
}

@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary
)
