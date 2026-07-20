package com.buco7854.opentv.core.util

import kotlinx.datetime.Clock

fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
