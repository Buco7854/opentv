package com.buco7854.opentv.core.util

import kotlin.time.Clock

fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
