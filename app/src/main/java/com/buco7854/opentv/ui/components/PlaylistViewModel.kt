package com.buco7854.opentv.ui.components

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** ViewModel keyed by (screen, playlistId). */
@Composable
inline fun <reified VM : ViewModel> playlistViewModel(
    playlistId: Long,
    crossinline create: (Application, Long) -> VM,
): VM = viewModel(
    key = "${VM::class.java.simpleName}-$playlistId",
    factory = viewModelFactory {
        initializer { create(this[APPLICATION_KEY]!! as Application, playlistId) }
    },
)
