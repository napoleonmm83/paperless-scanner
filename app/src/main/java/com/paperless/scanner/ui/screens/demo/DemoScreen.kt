package com.paperless.scanner.ui.screens.demo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dark Tech Precision Color Palette
private val DarkPrimary = Color(0xFFE1FF8D)
private val DarkOnPrimary = Color(0xFF000000)
private val DarkBackground = Color(0xFF0A0A0A)
private val DarkSurface = Color(0xFF141414)
private val DarkSurfaceVariant = Color(0xFF1F1F1F)
private val DarkOnBackground = Color(0xFFFFFFFF)
private val DarkOnSurface = Color(0xFFFFFFFF)
private val DarkOnSurfaceMuted = Color(0xFFA1A1AA)
private val DarkOutline = Color(0xFF27272A)
private val DarkAccentBlue = Color(0xFF2E3A59)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(
    onBackClick: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    var switchState by remember { mutableStateOf(false) }
    var filterSelected by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            DemoHeader(onBackClick = onBackClick)

            // Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // Featured Highlight Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkPrimary, RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(DarkOnPrimary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = DarkPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "FEATURED HIGHLIGHT",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = DarkOnPrimary,
                                letterSpacing = (-0.36).sp
                            )
                            Text(
                                text = "Primary color as background with high contrast",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = DarkOnPrimary.copy(alpha = 0.8f),
                                lineHeight = 20.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Typography Section
                DemoSection(title = "TYPOGRAPHY") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "HEADLINE LARGE",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DarkOnSurface,
                            letterSpacing = (-0.64).sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "HEADLINE MEDIUM",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DarkOnSurface,
                            letterSpacing = (-0.48).sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Body text appears in regular weight with optimal line height for readability. This demonstrates how longer content should be displayed.",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            color = DarkOnSurface,
                            lineHeight = 24.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "LABEL TEXT • UPPERCASE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = DarkOnSurfaceMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons Section
                DemoSection(title = "BUTTONS") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Primary Button
                        Button(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DarkPrimary,
                                contentColor = DarkOnPrimary
                            ),
                            contentPadding = PaddingValues(vertical = 16.dp, horizontal = 24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PRIMARY ACTION",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        // Secondary Button (Outlined)
                        OutlinedButton(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(DarkPrimary),
                                width = 1.dp
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = DarkPrimary
                            ),
                            contentPadding = PaddingValues(vertical = 16.dp, horizontal = 24.dp)
                        ) {
                            Text(
                                text = "SECONDARY ACTION",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        // Text Button
                        TextButton(
                            onClick = { },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = DarkOnSurfaceMuted
                            )
                        ) {
                            Text(
                                text = "TEXT BUTTON",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }

                        // Icon Buttons Row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledIconButton(
                                onClick = { },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = DarkPrimary,
                                    contentColor = DarkOnPrimary
                                )
                            ) {
                                Icon(Icons.Default.Favorite, contentDescription = null)
                            }
                            IconButton(
                                onClick = { },
                                modifier = Modifier
                                    .border(1.dp, DarkOutline, CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = null,
                                    tint = DarkOnSurface
                                )
                            }
                            IconButton(onClick = { }) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    tint = DarkOnSurfaceMuted
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Cards Section
                DemoSection(title = "CARDS") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Primary Card (with primary background)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkPrimary, RoundedCornerShape(20.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(DarkOnPrimary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = DarkPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "PREMIUM FEATURE",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = DarkOnPrimary
                                        )
                                        Text(
                                            text = "Activated successfully",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = DarkOnPrimary.copy(alpha = 0.8f)
                                        )
                                    }
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        tint = DarkOnPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Card with primary color background for highlighting important content and creating visual hierarchy.",
                                    fontSize = 14.sp,
                                    color = DarkOnPrimary.copy(alpha = 0.9f),
                                    lineHeight = 20.sp
                                )
                            }
                        }

                        // Standard Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurface, RoundedCornerShape(20.dp))
                                .border(1.dp, DarkOutline, RoundedCornerShape(20.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(DarkPrimary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Description,
                                            contentDescription = null,
                                            tint = DarkOnPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "DOCUMENT TITLE",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = DarkOnSurface
                                        )
                                        Text(
                                            text = "Added 2 hours ago",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = DarkOnSurfaceMuted
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "This is a card component with no shadow, using a subtle outline for depth. The border is defined in dark gray.",
                                    fontSize = 14.sp,
                                    color = DarkOnSurfaceMuted,
                                    lineHeight = 20.sp
                                )
                            }
                        }

                        // Interactive Card with Actions
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurfaceVariant, RoundedCornerShape(20.dp))
                                .border(1.dp, DarkOutline, RoundedCornerShape(20.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "INTERACTIVE CARD",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = DarkOnSurface
                                        )
                                        Text(
                                            text = "With action buttons",
                                            fontSize = 12.sp,
                                            color = DarkOnSurfaceMuted
                                        )
                                    }
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = DarkPrimary,
                                                contentColor = DarkOnPrimary
                                            ) {
                                                Text("3")
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Notifications,
                                            contentDescription = null,
                                            tint = DarkOnSurface
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { },
                                        shape = RoundedCornerShape(8.dp),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(
                                            brush = androidx.compose.ui.graphics.SolidColor(DarkOutline),
                                            width = 1.dp
                                        ),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = DarkOnSurface
                                        ),
                                        contentPadding = PaddingValues(
                                            vertical = 8.dp,
                                            horizontal = 16.dp
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("EDIT", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                    OutlinedButton(
                                        onClick = { },
                                        shape = RoundedCornerShape(8.dp),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(
                                            brush = androidx.compose.ui.graphics.SolidColor(
                                                Color(0xFFEF4444)
                                            ),
                                            width = 1.dp
                                        ),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color(0xFFEF4444)
                                        ),
                                        contentPadding = PaddingValues(
                                            vertical = 8.dp,
                                            horizontal = 16.dp
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("DELETE", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input Fields Section
                DemoSection(title = "INPUT FIELDS") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("LABEL TEXT") },
                            placeholder = { Text("Enter text here...") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = DarkPrimary,
                                unfocusedBorderColor = DarkOutline,
                                focusedTextColor = DarkOnSurface,
                                unfocusedTextColor = DarkOnSurface,
                                focusedLabelColor = DarkPrimary,
                                unfocusedLabelColor = DarkOnSurfaceMuted,
                                cursorColor = DarkPrimary
                            )
                        )

                        OutlinedTextField(
                            value = "",
                            onValueChange = { },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("DISABLED FIELD") },
                            enabled = false,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledContainerColor = DarkSurface,
                                disabledBorderColor = DarkOutline,
                                disabledTextColor = DarkOnSurfaceMuted,
                                disabledLabelColor = DarkOnSurfaceMuted
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Chips & Tags Section
                DemoSection(title = "CHIPS & TAGS") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AssistChip(
                                onClick = { },
                                label = {
                                    Text(
                                        "TAG",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = DarkPrimary,
                                    labelColor = DarkOnPrimary,
                                    leadingIconContentColor = DarkOnPrimary
                                ),
                                border = null
                            )
                            AssistChip(
                                onClick = { },
                                label = {
                                    Text(
                                        "DOCUMENT",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = DarkOnSurface
                                ),
                                border = BorderStroke(1.dp, DarkOutline)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = filterSelected,
                                onClick = { filterSelected = !filterSelected },
                                label = {
                                    Text(
                                        "FILTER",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = if (filterSelected) {
                                    {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = DarkOnSurface,
                                    selectedContainerColor = DarkPrimary,
                                    selectedLabelColor = DarkOnPrimary,
                                    selectedLeadingIconColor = DarkOnPrimary
                                ),
                                border = if (filterSelected) {
                                    BorderStroke(1.dp, DarkPrimary)
                                } else {
                                    BorderStroke(1.dp, DarkOutline)
                                }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { },
                                label = {
                                    Text(
                                        "ARCHIVED",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = DarkOnSurface
                                ),
                                border = BorderStroke(1.dp, DarkOutline)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Switches & Toggles Section
                DemoSection(title = "SWITCHES & TOGGLES") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "NOTIFICATIONS",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DarkOnSurface
                                )
                                Text(
                                    text = "Enable push notifications",
                                    fontSize = 12.sp,
                                    color = DarkOnSurfaceMuted
                                )
                            }
                            Switch(
                                checked = switchState,
                                onCheckedChange = { switchState = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkOnPrimary,
                                    checkedTrackColor = DarkPrimary,
                                    uncheckedThumbColor = DarkOnSurfaceMuted,
                                    uncheckedTrackColor = DarkOutline,
                                    uncheckedBorderColor = DarkOutline
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = DarkOutline)
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "AUTO SYNC",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DarkOnSurface
                                )
                                Text(
                                    text = "Sync in background",
                                    fontSize = 12.sp,
                                    color = DarkOnSurfaceMuted
                                )
                            }
                            Switch(
                                checked = false,
                                onCheckedChange = { },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkOnPrimary,
                                    checkedTrackColor = DarkPrimary,
                                    uncheckedThumbColor = DarkOnSurfaceMuted,
                                    uncheckedTrackColor = DarkOutline,
                                    uncheckedBorderColor = DarkOutline
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progress Indicators Section
                DemoSection(title = "PROGRESS INDICATORS") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "LINEAR PROGRESS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = DarkOnSurfaceMuted
                        )
                        LinearProgressIndicator(
                            progress = { 0.65f },
                            modifier = Modifier.fillMaxWidth(),
                            color = DarkPrimary,
                            trackColor = DarkOutline,
                        )

                        Text(
                            text = "INDETERMINATE LINEAR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = DarkOnSurfaceMuted
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = DarkPrimary,
                            trackColor = DarkOutline
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(40.dp),
                                    color = DarkPrimary,
                                    trackColor = DarkOutline,
                                    strokeWidth = 4.dp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "CIRCULAR",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = DarkOnSurfaceMuted
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status Icons Section
                DemoSection(title = "STATUS ICONS") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatusIcon(
                            icon = Icons.Default.CheckCircle,
                            label = "SUCCESS",
                            color = Color(0xFF10B981)
                        )
                        StatusIcon(
                            icon = Icons.Default.Warning,
                            label = "WARNING",
                            color = Color(0xFFF59E0B)
                        )
                        StatusIcon(
                            icon = Icons.Default.Error,
                            label = "ERROR",
                            color = Color(0xFFEF4444)
                        )
                        StatusIcon(
                            icon = Icons.Default.CloudUpload,
                            label = "UPLOAD",
                            color = DarkPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // List Items Section
                DemoSection(title = "LIST ITEMS") {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            icon = Icons.Default.Description,
                            title = "INVOICE_2024_01.PDF",
                            subtitle = "Uploaded today • 2.4 MB",
                            badge = "NEW",
                            badgeColor = DarkPrimary
                        )
                        HorizontalDivider(color = DarkOutline)
                        ListItem(
                            icon = Icons.Default.Description,
                            title = "CONTRACT_FINAL.PDF",
                            subtitle = "3 days ago • 1.8 MB",
                            badge = "PRIORITY",
                            badgeColor = DarkPrimary
                        )
                        HorizontalDivider(color = DarkOutline)
                        ListItem(
                            icon = Icons.Default.Description,
                            title = "RECEIPT_HARDWARE.PDF",
                            subtitle = "Last week • 456 KB",
                            badge = null,
                            badgeColor = null
                        )
                        HorizontalDivider(color = DarkOutline)
                        ListItem(
                            icon = Icons.Default.Description,
                            title = "REPORT_2024_Q4.PDF",
                            subtitle = "2 weeks ago • 3.2 MB",
                            badge = "STARRED",
                            badgeColor = DarkPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Info Boxes with Primary Background
                DemoSection(title = "INFO BOXES") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Success Info Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkPrimary, RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = DarkOnPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "SUCCESS",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = DarkOnPrimary
                                    )
                                    Text(
                                        text = "Your document was uploaded successfully",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = DarkOnPrimary.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        }

                        // Alert Info Box with Outline
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurface, RoundedCornerShape(12.dp))
                                .border(2.dp, DarkPrimary, RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(DarkPrimary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Notifications,
                                        contentDescription = null,
                                        tint = DarkOnPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "ATTENTION",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = DarkPrimary
                                    )
                                    Text(
                                        text = "Box with primary border for emphasis",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = DarkOnSurface
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun DemoHeader(onBackClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = onBackClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = DarkOnSurface
                )
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "DESIGN SYSTEM DEMO",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = DarkOnSurface,
                    letterSpacing = (-0.4).sp
                )
                Text(
                    text = "Dark Tech Precision Style",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = DarkPrimary
                )
            }
        }
    }
}

@Composable
private fun DemoSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = DarkPrimary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface, RoundedCornerShape(20.dp))
                .border(1.dp, DarkOutline, RoundedCornerShape(20.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun StatusIcon(
    icon: ImageVector,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color.copy(alpha = 0.15f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = DarkOnSurfaceMuted,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    badgeColor: Color?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(DarkSurfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DarkOnSurfaceMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = DarkOnSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = DarkOnSurfaceMuted
            )
        }
        if (badge != null && badgeColor != null) {
            Box(
                modifier = Modifier
                    .background(badgeColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = badge,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = DarkOnPrimary
                )
            }
        }
    }
}
