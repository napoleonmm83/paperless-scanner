package com.paperless.scanner.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Flags a hardcoded `letterSpacing = <number>.sp` override.
 *
 * Label typography spacing must come from the design tokens in Type.kt (e.g.
 * `labelSmall` with `0.1.em`), not an inline `.sp` literal that drifts from the
 * scalable `.em` token. Token references and `.em` values are not flagged.
 * Plan-04 (#266 enforcement).
 */
class LabelLetterSpacingOverrideRule(config: Config = Config.empty) : Rule(
    config,
    description = "Hardcoded letterSpacing .sp override instead of a typography token.",
) {
    override val ruleName = RuleName("LabelLetterSpacingOverride")

    override fun visitArgument(argument: KtValueArgument) {
        super.visitArgument(argument)
        if (argument.getArgumentName()?.asName?.asString() != "letterSpacing") return
        val value = argument.getArgumentExpression() as? KtDotQualifiedExpression ?: return
        val receiver = value.receiverExpression
        // Only a non-zero hardcoded `.sp` literal is a drift; `0.sp` is a harmless no-op.
        if (value.selectorExpression?.text == "sp" &&
            receiver is KtConstantExpression &&
            receiver.text.toDoubleOrNull() != 0.0
        ) {
            report(
                Finding(
                    Entity.from(value),
                    "Hardcoded letterSpacing ${value.text} — use a typography token " +
                        "(e.g. Type.kt labelSmall with .em) instead of an inline .sp literal.",
                ),
            )
        }
    }
}
