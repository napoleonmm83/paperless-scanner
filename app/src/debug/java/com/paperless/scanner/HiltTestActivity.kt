package com.paperless.scanner

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Empty Hilt-enabled host for instrumented Compose tests (T-003). createComposeRule() hosts
 * content in a plain ComponentActivity, which Hilt cannot inject — every hiltViewModel() in the
 * nav graph then fails. Debug source set only, so it never ships in a release build.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
