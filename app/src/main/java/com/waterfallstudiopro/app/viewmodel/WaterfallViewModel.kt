package com.waterfallstudiopro.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.waterfallstudiopro.app.domain.WaterfallEngine
import com.waterfallstudiopro.app.model.AnalysisMode
import com.waterfallstudiopro.app.model.BridgeResult
import com.waterfallstudiopro.app.model.ColorPaletteType
import com.waterfallstudiopro.app.model.ImportPreviewSummary
import com.waterfallstudiopro.app.model.WaterfallAppState
import com.waterfallstudiopro.app.model.WaterfallEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class UndoEntry(
    val label: String,
    val snapshot: WaterfallAppState
)

data class ToastNotificationState(
    val message: String,
    val showUndoAction: Boolean = false
)

class WaterfallViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "waterfall_studio_pro_prefs"
        private const val STORAGE_KEY = "waterfall_studio_pro_state_v5"
        private const val MAX_UNDO = 20
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val engineSelfTestPassed: Boolean = WaterfallEngine.runEngineSelfTest()

    private val _appState = MutableStateFlow(loadStateFromStorage())
    val appState: StateFlow<WaterfallAppState> = _appState.asStateFlow()

    private val _bridgeResult = MutableStateFlow(WaterfallEngine.buildBridge(_appState.value))
    val bridgeResult: StateFlow<BridgeResult> = _bridgeResult.asStateFlow()

    private val undoStack = ArrayDeque<UndoEntry>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _selectedSidebarTab = MutableStateFlow(0) // 0=Dados, 1=Importar, 2=Estilo
    val selectedSidebarTab: StateFlow<Int> = _selectedSidebarTab.asStateFlow()

    private val _selectedBarIndex = MutableStateFlow<Int?>(null)
    val selectedBarIndex: StateFlow<Int?> = _selectedBarIndex.asStateFlow()

    private val _bulkPasteText = MutableStateFlow("")
    val bulkPasteText: StateFlow<String> = _bulkPasteText.asStateFlow()

    private val _bulkIncludeEndpoints = MutableStateFlow(true)
    val bulkIncludeEndpoints: StateFlow<Boolean> = _bulkIncludeEndpoints.asStateFlow()

    private val _importPreview = MutableStateFlow(
        WaterfallEngine.parseSpreadsheetPreview("", true, _appState.value)
    )
    val importPreview: StateFlow<ImportPreviewSummary> = _importPreview.asStateFlow()

    private val _toastState = MutableStateFlow<ToastNotificationState?>(null)
    val toastState: StateFlow<ToastNotificationState?> = _toastState.asStateFlow()
    private var toastJob: Job? = null

    private fun updateState(newState: WaterfallAppState) {
        _appState.value = newState
        _bridgeResult.value = WaterfallEngine.buildBridge(newState)
        _importPreview.value = WaterfallEngine.parseSpreadsheetPreview(
            _bulkPasteText.value,
            _bulkIncludeEndpoints.value,
            newState
        )
        saveStateToStorage(newState)
    }

    private fun pushUndoSnapshot(actionLabel: String) {
        undoStack.addLast(UndoEntry(actionLabel, _appState.value))
        if (undoStack.size > MAX_UNDO) {
            undoStack.removeFirst()
        }
        _canUndo.value = undoStack.isNotEmpty()
    }

    fun performUndo() {
        if (undoStack.isEmpty()) return
        val last = undoStack.removeLast()
        _canUndo.value = undoStack.isNotEmpty()
        updateState(last.snapshot)
        showToast("Desfeito: ${last.label}", showUndoAction = false)
    }

    fun showToast(message: String, showUndoAction: Boolean = false) {
        toastJob?.cancel()
        _toastState.value = ToastNotificationState(
            message = message,
            showUndoAction = showUndoAction && undoStack.isNotEmpty()
        )
        toastJob = viewModelScope.launch {
            delay(4000L)
            _toastState.value = null
        }
    }

    fun dismissToast() {
        toastJob?.cancel()
        _toastState.value = null
    }

    fun selectSidebarTab(index: Int) {
        _selectedSidebarTab.value = index.coerceIn(0, 2)
    }

    fun selectBarIndex(index: Int?) {
        _selectedBarIndex.value = if (_selectedBarIndex.value == index) null else index
    }

    fun setAnalysisMode(mode: AnalysisMode) {
        updateState(_appState.value.copy(analysisMode = mode))
    }

    fun updateStartPeriodLabel(label: String) {
        updateState(_appState.value.copy(startPeriodLabel = label))
    }

    fun updateStartPeriodValue(value: String) {
        updateState(_appState.value.copy(startPeriodValue = value))
    }

    fun updateEndPeriodLabel(label: String) {
        updateState(_appState.value.copy(endPeriodLabel = label))
    }

    fun updateEndPeriodValue(value: String) {
        updateState(_appState.value.copy(endPeriodValue = value))
    }

    fun setAutoReconcileGap(enabled: Boolean) {
        updateState(_appState.value.copy(autoReconcileGap = enabled))
    }

    fun setReconcileTolerancePct(tolerance: String) {
        updateState(_appState.value.copy(reconcileTolerancePct = tolerance))
    }

    fun addEventRow() {
        val current = _appState.value.events
        val next = current + WaterfallEvent(
            id = "ev_${System.currentTimeMillis()}",
            label = "Novo Impacto ${current.size + 1}",
            value = 50.0,
            isSubtotal = false
        )
        updateState(_appState.value.copy(events = next))
    }

    fun addSubtotalRow() {
        val current = _appState.value.events
        val next = current + WaterfallEvent(
            id = "sub_${System.currentTimeMillis()}",
            label = "Subtotal Parcial",
            value = 0.0,
            isSubtotal = true
        )
        updateState(_appState.value.copy(events = next))
    }

    fun updateEventLabel(index: Int, newLabel: String) {
        val list = _appState.value.events.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(label = newLabel)
            updateState(_appState.value.copy(events = list))
        }
    }

    fun updateEventValue(index: Int, rawValue: String) {
        val list = _appState.value.events.toMutableList()
        if (index in list.indices) {
            val v = rawValue.replace(',', '.').toDoubleOrNull() ?: 0.0
            list[index] = list[index].copy(value = if (v.isFinite()) v else 0.0)
            updateState(_appState.value.copy(events = list))
        }
    }

    fun moveEventUp(index: Int) {
        val list = _appState.value.events.toMutableList()
        if (index > 0 && index in list.indices) {
            val tmp = list[index - 1]
            list[index - 1] = list[index]
            list[index] = tmp
            updateState(_appState.value.copy(events = list))
        }
    }

    fun moveEventDown(index: Int) {
        val list = _appState.value.events.toMutableList()
        if (index >= 0 && index < list.lastIndex) {
            val tmp = list[index + 1]
            list[index + 1] = list[index]
            list[index] = tmp
            updateState(_appState.value.copy(events = list))
        }
    }

    fun deleteEvent(index: Int) {
        val list = _appState.value.events.toMutableList()
        if (index in list.indices) {
            val removed = list[index]
            pushUndoSnapshot("Exclusão de \"${removed.label}\"")
            list.removeAt(index)
            updateState(_appState.value.copy(events = list))
            showToast("Etapa removida.", showUndoAction = true)
        }
    }

    fun sortEventsDescending() {
        pushUndoSnapshot("Ordenação Maior ➔ Menor")
        val result = mutableListOf<WaterfallEvent>()
        val bucket = mutableListOf<WaterfallEvent>()
        fun flushBucket() {
            bucket.sortByDescending { it.value }
            result.addAll(bucket)
            bucket.clear()
        }
        _appState.value.events.forEach { ev ->
            if (ev.isSubtotal) {
                flushBucket()
                result.add(ev)
            } else {
                bucket.add(ev)
            }
        }
        flushBucket()
        updateState(_appState.value.copy(events = result))
        showToast("Impactos ordenados do maior para o menor!", showUndoAction = true)
    }

    fun reverseEventsOrder() {
        pushUndoSnapshot("Inversão de Ordem")
        updateState(_appState.value.copy(events = _appState.value.events.reversed()))
        showToast("Ordem das etapas invertida!", showUndoAction = true)
    }

    // Tab 2: Import
    fun updateBulkPasteText(text: String) {
        _bulkPasteText.value = text
        _importPreview.value = WaterfallEngine.parseSpreadsheetPreview(
            text,
            _bulkIncludeEndpoints.value,
            _appState.value
        )
    }

    fun setBulkIncludeEndpoints(include: Boolean) {
        _bulkIncludeEndpoints.value = include
        _importPreview.value = WaterfallEngine.parseSpreadsheetPreview(
            _bulkPasteText.value,
            include,
            _appState.value
        )
    }

    fun setImportLocale(locale: String) {
        updateState(_appState.value.copy(importLocale = locale))
    }

    fun fillBulkSample() {
        val sample = listOf(
            "Categoria\tValor (R$ Mil)",
            "EBITDA Q1 2026\t1.850",
            "Expansão de Volume, Brasil\t420",
            "Reajuste de Preços\t190",
            "Ganho de Eficiência\t145",
            "Inflação de Insumos\t-210",
            "Despesas com Marketing\t-95",
            "EBITDA Q2 2026\t2.300"
        ).joinToString("\n")
        _bulkPasteText.value = sample
        updateState(_appState.value.copy(importLocale = "pt-BR"))
    }

    fun applyBulkImport() {
        val preview = _importPreview.value
        val rows = preview.rows
        if (rows.isEmpty()) {
            showToast("Nenhuma linha encontrada para importar.", showUndoAction = false)
            return
        }
        if (preview.errorCount > 0) {
            showToast("Importação bloqueada: corrija ${preview.errorCount} linha(s) com erro na prévia.", showUndoAction = false)
            return
        }

        pushUndoSnapshot("Importação de Planilha")
        val isBridge = _appState.value.analysisMode == AnalysisMode.BRIDGE
        val useEndpoints = _bulkIncludeEndpoints.value && rows.size >= 3

        val now = System.currentTimeMillis()
        if (isBridge && useEndpoints) {
            val first = rows.first()
            val last = rows.last()
            val middle = rows.subList(1, rows.lastIndex)
            val newEvents = middle.mapIndexed { i, r ->
                WaterfallEvent(
                    id = "imp_${now}_$i",
                    label = r.label,
                    value = r.value,
                    isSubtotal = false
                )
            }
            updateState(
                _appState.value.copy(
                    startPeriodLabel = first.label,
                    startPeriodValue = first.value.toString().removeSuffix(".0"),
                    endPeriodLabel = last.label,
                    endPeriodValue = last.value.toString().removeSuffix(".0"),
                    events = newEvents
                )
            )
        } else {
            val newEvents = rows.mapIndexed { i, r ->
                WaterfallEvent(
                    id = "imp_${now}_$i",
                    label = r.label,
                    value = r.value,
                    isSubtotal = false
                )
            }
            updateState(_appState.value.copy(events = newEvents))
        }
        showToast("Importadas ${rows.size} linhas com sucesso!", showUndoAction = true)
    }

    // Tab 3: Scale & Style
    fun updateTitle(title: String) {
        updateState(_appState.value.copy(title = title))
    }

    fun updateXAxisName(name: String) {
        updateState(_appState.value.copy(xAxisName = name))
    }

    fun updateYAxisName(name: String) {
        updateState(_appState.value.copy(yAxisName = name))
    }

    fun setEnableAxisBreak(enabled: Boolean) {
        updateState(_appState.value.copy(enableAxisBreak = enabled))
    }

    fun setDisplayLocale(locale: String) {
        updateState(_appState.value.copy(displayLocale = locale))
    }

    fun setDecimalPlaces(places: Int) {
        updateState(_appState.value.copy(decimalPlaces = places.coerceIn(0, 2)))
    }

    fun setNumberPrefix(prefix: String) {
        updateState(_appState.value.copy(numberPrefix = prefix))
    }

    fun setNumberSuffix(suffix: String) {
        updateState(_appState.value.copy(numberSuffix = suffix))
    }

    fun setColorPalette(palette: ColorPaletteType) {
        updateState(_appState.value.copy(colorPalette = palette))
    }

    fun setShowTotalBar(show: Boolean) {
        updateState(_appState.value.copy(showTotalBar = show))
    }

    fun updateTotalBarLabel(label: String) {
        updateState(_appState.value.copy(totalBarLabel = label))
    }

    fun setShowDeltaBracket(show: Boolean) {
        updateState(_appState.value.copy(showDeltaBracket = show))
    }

    fun setShowDataLabels(show: Boolean) {
        updateState(_appState.value.copy(showDataLabels = show))
    }

    fun setShowPercentInLabels(show: Boolean) {
        updateState(_appState.value.copy(showPercentInLabels = show))
    }

    fun setShowConnectors(show: Boolean) {
        updateState(_appState.value.copy(showConnectors = show))
    }

    fun toggleDarkMode() {
        updateState(_appState.value.copy(darkMode = !_appState.value.darkMode))
    }

    fun resetToDefaultExample() {
        pushUndoSnapshot("Restauração de Exemplo")
        val keepDark = _appState.value.darkMode
        updateState(WaterfallAppState(darkMode = keepDark))
        showToast("Exemplo padrão restaurado!", showUndoAction = true)
    }

    private fun saveStateToStorage(state: WaterfallAppState) {
        try {
            val json = JSONObject().apply {
                put("analysisMode", state.analysisMode.id)
                put("title", state.title)
                put("startPeriodLabel", state.startPeriodLabel)
                put("startPeriodValue", state.startPeriodValue)
                put("endPeriodLabel", state.endPeriodLabel)
                put("endPeriodValue", state.endPeriodValue)
                put("autoReconcileGap", state.autoReconcileGap)
                put("reconcileTolerancePct", state.reconcileTolerancePct)
                put("xAxisName", state.xAxisName)
                put("yAxisName", state.yAxisName)
                put("showTotalBar", state.showTotalBar)
                put("totalBarLabel", state.totalBarLabel)
                put("showConnectors", state.showConnectors)
                put("showDataLabels", state.showDataLabels)
                put("showDeltaBracket", state.showDeltaBracket)
                put("showPercentInLabels", state.showPercentInLabels)
                put("enableAxisBreak", state.enableAxisBreak)
                put("displayLocale", state.displayLocale)
                put("importLocale", state.importLocale)
                put("numberPrefix", state.numberPrefix)
                put("numberSuffix", state.numberSuffix)
                put("decimalPlaces", state.decimalPlaces)
                put("colorPalette", state.colorPalette.id)
                put("darkMode", state.darkMode)
                val evArray = JSONArray()
                state.events.forEach { ev ->
                    evArray.put(
                        JSONObject().apply {
                            put("id", ev.id)
                            put("label", ev.label)
                            put("value", ev.value)
                            put("isSubtotal", ev.isSubtotal)
                        }
                    )
                }
                put("events", evArray)
            }
            prefs.edit().putString(STORAGE_KEY, json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadStateFromStorage(): WaterfallAppState {
        val raw = prefs.getString(STORAGE_KEY, null) ?: return WaterfallAppState()
        return try {
            val json = JSONObject(raw)
            val def = WaterfallAppState()
            val evArray = json.optJSONArray("events")
            val events = if (evArray != null) {
                buildList {
                    for (i in 0 until evArray.length()) {
                        val obj = evArray.getJSONObject(i)
                        add(
                            WaterfallEvent(
                                id = obj.optString("id", "ev_$i"),
                                label = obj.optString("label", "Etapa"),
                                value = obj.optDouble("value", 0.0),
                                isSubtotal = obj.optBoolean("isSubtotal", false)
                            )
                        )
                    }
                }
            } else {
                def.events
            }
            WaterfallAppState(
                analysisMode = AnalysisMode.fromId(json.optString("analysisMode", def.analysisMode.id)),
                title = json.optString("title", def.title),
                startPeriodLabel = json.optString("startPeriodLabel", def.startPeriodLabel),
                startPeriodValue = json.optString("startPeriodValue", def.startPeriodValue),
                endPeriodLabel = json.optString("endPeriodLabel", def.endPeriodLabel),
                endPeriodValue = json.optString("endPeriodValue", def.endPeriodValue),
                autoReconcileGap = json.optBoolean("autoReconcileGap", def.autoReconcileGap),
                reconcileTolerancePct = json.optString("reconcileTolerancePct", def.reconcileTolerancePct),
                xAxisName = json.optString("xAxisName", def.xAxisName),
                yAxisName = json.optString("yAxisName", def.yAxisName),
                events = events,
                showTotalBar = json.optBoolean("showTotalBar", def.showTotalBar),
                totalBarLabel = json.optString("totalBarLabel", def.totalBarLabel),
                showConnectors = json.optBoolean("showConnectors", def.showConnectors),
                showDataLabels = json.optBoolean("showDataLabels", def.showDataLabels),
                showDeltaBracket = json.optBoolean("showDeltaBracket", def.showDeltaBracket),
                showPercentInLabels = json.optBoolean("showPercentInLabels", def.showPercentInLabels),
                enableAxisBreak = json.optBoolean("enableAxisBreak", def.enableAxisBreak),
                displayLocale = json.optString("displayLocale", def.displayLocale),
                importLocale = json.optString("importLocale", def.importLocale),
                numberPrefix = json.optString("numberPrefix", def.numberPrefix),
                numberSuffix = json.optString("numberSuffix", def.numberSuffix),
                decimalPlaces = json.optInt("decimalPlaces", def.decimalPlaces),
                colorPalette = ColorPaletteType.fromId(json.optString("colorPalette", def.colorPalette.id)),
                darkMode = json.optBoolean("darkMode", def.darkMode)
            )
        } catch (_: Exception) {
            WaterfallAppState()
        }
    }
}
