package com.example.screenmonitor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.screenmonitor.data.SecurityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun LockScreen(
    securityManager: SecurityManager,
    onUnlocked: () -> Unit
) {
    val isSetupMode = remember { !securityManager.isPinSet() }
    var step by remember { mutableStateOf(if (isSetupMode) 1 else 0) }
    var enteredPin by remember { mutableStateOf("") }
    var firstPinDraft by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lockoutSeconds by remember { mutableIntStateOf(securityManager.getRemainingLockoutSeconds()) }

    val coroutineScope = rememberCoroutineScope()
    val shakeOffset = remember { Animatable(0f) }

    fun triggerShake() {
        coroutineScope.launch {
            val sequence = listOf(-24f, 24f, -16f, 16f, -8f, 8f, 0f)
            for (offset in sequence) {
                shakeOffset.animateTo(offset, tween(35))
            }
        }
    }

    LaunchedEffect(lockoutSeconds) {
        if (lockoutSeconds > 0) {
            delay(1000L)
            lockoutSeconds = securityManager.getRemainingLockoutSeconds()
        }
    }

    val title = when {
        lockoutSeconds > 0 -> "تم قفل الدخول مؤقتاً"
        isSetupMode && step == 1 -> "تعيين رمز المرور"
        isSetupMode && step == 2 -> "تأكيد رمز المرور"
        else -> "التحقق من الهوية"
    }

    val subtitle = when {
        lockoutSeconds > 0 -> "يرجى الانتظار $lockoutSeconds ثانية للمحاولة مجدداً"
        isSetupMode && step == 1 -> "أدخل رمز PIN مكوّن من 4 أرقام لحماية التطبيق"
        isSetupMode && step == 2 -> "أعد إدخال الرمز للتأكيد والمتابعة"
        else -> "أدخل رمز PIN للوصول إلى اللقطات وإدارة المراقبة"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            if (lockoutSeconds > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (lockoutSeconds > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (lockoutSeconds > 0) Icons.Outlined.HourglassTop
                        else if (isSetupMode) Icons.Outlined.Shield
                        else Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = if (lockoutSeconds > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (lockoutSeconds > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Modern PIN Dots Indicator
                Row(
                    modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val isFilled = index < enteredPin.length
                        val dotSize by animateDpAsState(
                            targetValue = if (isFilled) 18.dp else 14.dp,
                            animationSpec = spring(),
                            label = "dot_size"
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (isFilled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            label = "dot_color"
                        )

                        Box(
                            modifier = Modifier
                                .size(dotSize)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }

                AnimatedVisibility(visible = errorMessage != null) {
                    errorMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Numeric Keypad
            NumericKeypad(
                enabled = lockoutSeconds == 0,
                onDigitClick = { digit ->
                    if (enteredPin.length < 4) {
                        val newPin = enteredPin + digit
                        enteredPin = newPin
                        errorMessage = null

                        if (newPin.length == 4) {
                            if (isSetupMode) {
                                if (step == 1) {
                                    firstPinDraft = newPin
                                    enteredPin = ""
                                    step = 2
                                } else if (step == 2) {
                                    if (newPin == firstPinDraft) {
                                        securityManager.setPin(newPin)
                                        onUnlocked()
                                    } else {
                                        errorMessage = "الرمزان غير متطابقين، حاول مجدداً"
                                        triggerShake()
                                        enteredPin = ""
                                        step = 1
                                    }
                                }
                            } else {
                                val success = securityManager.verifyPin(newPin)
                                if (success) {
                                    onUnlocked()
                                } else {
                                    triggerShake()
                                    val remaining = securityManager.getRemainingLockoutSeconds()
                                    if (remaining > 0) {
                                        lockoutSeconds = remaining
                                        errorMessage = "تجاوزت عدد المحاولات، تم القفل مؤقتاً"
                                    } else {
                                        val failed = securityManager.getFailedAttempts()
                                        errorMessage = "رمز غير صحيح ($failed/${SecurityManager.MAX_FAILED_ATTEMPTS})"
                                    }
                                    enteredPin = ""
                                }
                            }
                        }
                    }
                },
                onBackspaceClick = {
                    if (enteredPin.isNotEmpty()) {
                        enteredPin = enteredPin.dropLast(1)
                        errorMessage = null
                    }
                }
            )
        }
    }
}

@Composable
fun NumericKeypad(
    enabled: Boolean,
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9")
        )

        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (digit in row) {
                    KeypadDigitButton(
                        digit = digit,
                        enabled = enabled,
                        onClick = { onDigitClick(digit) }
                    )
                }
            }
        }

        // Bottom Row: Empty spacer, 0, Backspace icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Empty placeholder for symmetry
            Box(modifier = Modifier.size(72.dp))

            // Digit 0
            KeypadDigitButton(
                digit = "0",
                enabled = enabled,
                onClick = { onDigitClick("0") }
            )

            // Backspace button with proper vector icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, radius = 36.dp)
                    ) { onBackspaceClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Backspace,
                    contentDescription = "حذف",
                    tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
fun KeypadDigitButton(
    digit: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.65f else 0.2f))
            .border(
                BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.25f else 0.1f)
                ),
                shape = CircleShape
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 36.dp)
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = digit,
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}
