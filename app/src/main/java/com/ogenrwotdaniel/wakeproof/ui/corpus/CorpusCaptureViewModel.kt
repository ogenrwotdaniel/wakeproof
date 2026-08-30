package com.ogenrwotdaniel.wakeproof.ui.corpus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.verify.water.corpus.CorpusRecorder
import com.ogenrwotdaniel.wakeproof.verify.water.corpus.CorpusShot
import com.ogenrwotdaniel.wakeproof.verify.water.corpus.corpusFilename
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The form and running tally behind the depth-corpus capture screen.
 *
 * The bookkeeping is the whole reason this exists in the app rather than being
 * done with the stock camera and a notebook. Shooting the corpus is mechanically
 * easy — photograph a bottle, pour a measured amount, photograph it again — and
 * the part that actually goes wrong is recording which file had how many
 * millilitres out of it. A row that names the wrong file produces data that
 * still parses and is simply untrue, which is worse than no data at all.
 */
data class CorpusCaptureState(
    val sequence: String = "s1",
    val bottle: String = "clear-750",
    val capacityMl: String = "750",
    val lighting: String = "daylight",
    val pouredTotalMl: String = "0",
    val unreadable: Boolean = false,
    val shotsInSequence: Int = 0,
    val totalShots: Int = 0,
    val lastSaved: String? = null,
    val directory: String = ""
) {
    /**
     * A sequence needs at least two shots before it yields any pair at all, and
     * the useful range starts around eight (28 pairs).
     */
    val pairsSoFar: Int get() = shotsInSequence * (shotsInSequence - 1) / 2
}

@HiltViewModel
class CorpusCaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val recorder = CorpusRecorder(context)

    private val _state = MutableStateFlow(CorpusCaptureState())
    val state: StateFlow<CorpusCaptureState> = _state.asStateFlow()

    init {
        _state.update { it.copy(directory = recorder.directory.absolutePath) }
        refreshCounts()
    }

    fun setSequence(value: String) {
        _state.update { it.copy(sequence = value) }
        refreshCounts()
    }

    fun setBottle(value: String) = _state.update { it.copy(bottle = value) }
    fun setCapacity(value: String) = _state.update { it.copy(capacityMl = value.filter(Char::isDigit)) }
    fun setLighting(value: String) = _state.update { it.copy(lighting = value) }
    fun setPoured(value: String) = _state.update { it.copy(pouredTotalMl = value.filter(Char::isDigit)) }
    fun setUnreadable(value: Boolean) = _state.update { it.copy(unreadable = value) }

    /**
     * Records the photograph just taken against the form as it currently stands.
     *
     * The cumulative total is deliberately NOT auto-incremented afterwards. The
     * number comes off a measuring jug and only the person holding it knows what
     * it is; advancing it by a guess would produce a corpus that looks complete
     * and is quietly wrong — exactly the failure this screen exists to prevent.
     */
    fun capture(jpeg: ByteArray) {
        val s = _state.value
        viewModelScope.launch {
            val index = recorder.shotsIn(s.sequence)
            val shot = CorpusShot(
                sequence = s.sequence.trim().ifBlank { "s1" },
                bottle = s.bottle.trim().ifBlank { "unnamed" },
                capacityMl = s.capacityMl.toIntOrNull() ?: 0,
                lighting = s.lighting.trim().ifBlank { "unspecified" },
                filename = corpusFilename(s.sequence.trim().ifBlank { "s1" }, index),
                pouredTotalMl = s.pouredTotalMl.toIntOrNull() ?: 0,
                unreadable = s.unreadable
            )
            val file = recorder.record(shot, jpeg)
            _state.update { it.copy(lastSaved = "${file.name}  @ ${shot.pouredTotalMl} ml") }
            refreshCounts()
        }
    }

    private fun refreshCounts() {
        viewModelScope.launch {
            val sequence = _state.value.sequence
            _state.update {
                it.copy(
                    shotsInSequence = recorder.shotsIn(sequence),
                    totalShots = recorder.shotCount()
                )
            }
        }
    }
}
