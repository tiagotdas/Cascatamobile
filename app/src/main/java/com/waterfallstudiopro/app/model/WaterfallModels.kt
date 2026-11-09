package com.waterfallstudiopro.app.model

import androidx.compose.ui.graphics.Color

enum class AnalysisMode(val id: String, val displayName: String) {
    BRIDGE("bridge", "Ponte entre Períodos (Base Q1 ➔ Eventos ➔ Base Q2)"),
    CUMULATIVE("cumulative", "Fluxo Acumulado Livre (Partindo de Zero + Saldo Final)");

    companion object {
        fun fromId(id: String): AnalysisMode =
            entries.find { it.id == id } ?: BRIDGE
    }
}

enum class ColorPaletteType(val id: String, val displayName: String) {
    CORPORATE("corporate", "Corporativo Padrão (Azul / Esmeralda / Rose)"),
    COLORBLIND("colorblind", "Daltonismo Seguro (Azul / Ciano / Laranja)"),
    MONOCHROME("monochrome", "Monocromático Executivo (Marinho / Teal / Cinza)");

    companion object {
        fun fromId(id: String): ColorPaletteType =
            entries.find { it.id == id } ?: CORPORATE
    }
}

data class PaletteColors(
    val baseBg: Color,
    val baseBorder: Color,
    val baseText: Color,
    val baseTextDark: Color,
    val posBg: Color,
    val posBorder: Color,
    val posText: Color,
    val posTextDark: Color,
    val negBg: Color,
    val negBorder: Color,
    val negText: Color,
    val negTextDark: Color,
    val adjBg: Color,
    val adjBorder: Color,
    val adjText: Color,
    val adjTextDark: Color
)

object ExecutivePalettes {
    val Corporate = PaletteColors(
        baseBg = Color(0xE62563EB),
        baseBorder = Color(0xFF1D4ED8),
        baseText = Color(0xFF1D4ED8),
        baseTextDark = Color(0xFF60A5FA),
        posBg = Color(0xD910B981),
        posBorder = Color(0xFF059669),
        posText = Color(0xFF047857),
        posTextDark = Color(0xFF34D399),
        negBg = Color(0xD9F43F5E),
        negBorder = Color(0xFFE11D48),
        negText = Color(0xFFBE123C),
        negTextDark = Color(0xFFFB7185),
        adjBg = Color(0xD9F59E0B),
        adjBorder = Color(0xFFD97706),
        adjText = Color(0xFFB45309),
        adjTextDark = Color(0xFFFBBF24)
    )

    val Colorblind = PaletteColors(
        baseBg = Color(0xE61E40AF),
        baseBorder = Color(0xFF1E3A8A),
        baseText = Color(0xFF1E40AF),
        baseTextDark = Color(0xFF93C5FD),
        posBg = Color(0xD90284C7),
        posBorder = Color(0xFF0369A1),
        posText = Color(0xFF0369A1),
        posTextDark = Color(0xFF38BDF8),
        negBg = Color(0xD9EA580C),
        negBorder = Color(0xFFC2410C),
        negText = Color(0xFFC2410C),
        negTextDark = Color(0xFFFB923C),
        adjBg = Color(0xD9D97706),
        adjBorder = Color(0xFFB45309),
        adjText = Color(0xFFB45309),
        adjTextDark = Color(0xFFFBBF24)
    )

    val Monochrome = PaletteColors(
        baseBg = Color(0xE60F172A),
        baseBorder = Color(0xFF020617),
        baseText = Color(0xFF0F172A),
        baseTextDark = Color(0xFFE2E8F0),
        posBg = Color(0xD90D9488),
        posBorder = Color(0xFF0F766E),
        posText = Color(0xFF0F766E),
        posTextDark = Color(0xFF2DD4BF),
        negBg = Color(0xD964748B),
        negBorder = Color(0xFF475569),
        negText = Color(0xFF475569),
        negTextDark = Color(0xFF94A3B8),
        adjBg = Color(0xD9D97706),
        adjBorder = Color(0xFFB45309),
        adjText = Color(0xFFB45309),
        adjTextDark = Color(0xFFFBBF24)
    )

    fun forType(type: ColorPaletteType): PaletteColors = when (type) {
        ColorPaletteType.CORPORATE -> Corporate
        ColorPaletteType.COLORBLIND -> Colorblind
        ColorPaletteType.MONOCHROME -> Monochrome
    }
}

data class WaterfallEvent(
    val id: String,
    val label: String,
    val value: Double,
    val isSubtotal: Boolean = false
)

data class WaterfallStep(
    val label: String,
    val start: Double,
    val end: Double,
    val diff: Double,
    val isBase: Boolean = false,
    val isSubtotal: Boolean = false,
    val isTotal: Boolean = false,
    val isAdjustment: Boolean = false,
    val expectedEnd: Double? = null
) {
    val isPillar: Boolean
        get() = isBase || isSubtotal || isTotal
}

enum class BridgeStatusType {
    AUTO,
    RECONCILED,
    RECONCILED_WITH_TOL,
    PLUGGED,
    DIVERGENT
}

data class BridgeResult(
    val mode: AnalysisMode,
    val q1Missing: Boolean,
    val statusType: BridgeStatusType,
    val steps: List<WaterfallStep>,
    val initialBase: Double,
    val sumPositives: Double,
    val sumNegatives: Double,
    val explainedVariance: Double,
    val unexplainedGap: Double,
    val unexplainedPctOfBase: Double,
    val unexplainedPctOfDelta: Double,
    val netVariance: Double,
    val pctDelta: Double?,
    val finalValue: Double,
    val calculatedEnd: Double,
    val invariantHolds: Boolean,
    val oracleDeltaScaled: Long
)

data class AxisBounds(
    val ySuggestedMin: Double,
    val ySuggestedMax: Double,
    val axisBreakActive: Boolean,
    val effectiveMin: Double
)

data class WaterfallAppState(
    val analysisMode: AnalysisMode = AnalysisMode.BRIDGE,
    val title: String = "Ponte de Variação de Receita: Q1 vs Q2 2026",
    val startPeriodLabel: String = "Receita Q1",
    val startPeriodValue: String = "1200",
    val endPeriodLabel: String = "Receita Q2",
    val endPeriodValue: String = "1450",
    val autoReconcileGap: Boolean = false,
    val reconcileTolerancePct: String = "strict",
    val xAxisName: String = "Fatores de Variação entre Trimestres",
    val yAxisName: String = "Valor (R$ mil)",
    val events: List<WaterfallEvent> = defaultEvents(),
    val showTotalBar: Boolean = true,
    val totalBarLabel: String = "Saldo Final",
    val showConnectors: Boolean = true,
    val showDataLabels: Boolean = true,
    val showDeltaBracket: Boolean = true,
    val showPercentInLabels: Boolean = true,
    val enableAxisBreak: Boolean = false,
    val displayLocale: String = "pt-BR",
    val importLocale: String = "pt-BR",
    val numberPrefix: String = "R$ ",
    val numberSuffix: String = " mil",
    val decimalPlaces: Int = 0,
    val colorPalette: ColorPaletteType = ColorPaletteType.CORPORATE,
    val darkMode: Boolean = false
) {
    companion object {
        fun defaultEvents(): List<WaterfallEvent> = listOf(
            WaterfallEvent(id = "ev1", label = "Novos Contratos, Enterprise", value = 280.0, isSubtotal = false),
            WaterfallEvent(id = "ev2", label = "Expansão (Upsell)", value = 140.0, isSubtotal = false),
            WaterfallEvent(id = "ev3", label = "Reajuste de Preços", value = 65.0, isSubtotal = false),
            WaterfallEvent(id = "ev4", label = "Receita Bruta Potencial", value = 0.0, isSubtotal = true),
            WaterfallEvent(id = "ev5", label = "Cancelamentos (Churn)", value = -160.0, isSubtotal = false),
            WaterfallEvent(id = "ev6", label = "Descontos Comerciais", value = -75.0, isSubtotal = false)
        )
    }
}

data class NumberParseResult(
    val value: Double,
    val valid: Boolean,
    val warning: String? = null,
    val error: String? = null
)

data class ParsedSpreadsheetRow(
    val lineNumber: Int,
    val raw: String,
    val label: String,
    val rawValue: String,
    val value: Double,
    val valid: Boolean,
    val warning: String?,
    val error: String?,
    val role: String
)

data class ImportPreviewSummary(
    val rows: List<ParsedSpreadsheetRow>,
    val validCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val controlSumText: String,
    val bridgeEquationText: String?,
    val isError: Boolean,
    val isWarning: Boolean,
    val canApply: Boolean
)
