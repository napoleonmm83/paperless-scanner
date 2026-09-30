package com.paperless.scanner.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Flags a `.clickable` Modifier whose chain has no >=48dp touch-target guarantee.
 *
 * WCAG / Material minimum interactive size is 48dp. A clickable is considered safe
 * when its Modifier chain also contains `.minimumInteractiveComponentSize()`, a
 * fill modifier (`fillMaxSize`/`fillMaxWidth`/`fillMaxHeight`/`matchParentSize`), or
 * an explicit size modifier of >=48.dp. Plan-04 (#264/#266 enforcement).
 */
class TouchTargetSizeRule(config: Config = Config.empty) : Rule(
    config,
    description = "Clickable Modifier without a >=48dp touch target.",
) {
    override val ruleName = RuleName("TouchTargetSize")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "clickable") return
        // Glance widgets use GlanceModifier with a different (non-48dp) touch model.
        if (expression.containingKtFile.importDirectives.any {
                it.importedFqName?.asString()?.startsWith("androidx.glance") == true
            }
        ) {
            return
        }
        if (chainCalls(expression).none { it.isTouchTargetGuard() }) {
            report(
                Finding(
                    Entity.from(expression),
                    "clickable Modifier without a >=48dp touch target — add " +
                        ".minimumInteractiveComponentSize() (or a >=48.dp size) before .clickable.",
                ),
            )
        }
    }

    /** All call selectors in the same Modifier chain as [clickable] (lambda bodies excluded). */
    private fun chainCalls(clickable: KtCallExpression): List<KtCallExpression> {
        var top: KtExpression = clickable
        while (true) {
            val parent = top.parent
            if (parent is KtQualifiedExpression &&
                (parent.receiverExpression === top || parent.selectorExpression === top)
            ) {
                top = parent
            } else {
                break
            }
        }
        val calls = mutableListOf<KtCallExpression>()
        fun walk(node: KtExpression?) {
            when (node) {
                is KtQualifiedExpression -> {
                    walk(node.receiverExpression)
                    (node.selectorExpression as? KtCallExpression)?.let { calls.add(it) }
                }
                is KtCallExpression -> calls.add(node)
                else -> Unit
            }
        }
        walk(top)
        return calls
    }

    private fun KtCallExpression.isTouchTargetGuard(): Boolean = when (calleeExpression?.text) {
        "minimumInteractiveComponentSize" -> true
        in FILL_MODIFIERS -> true
        in SIZE_MODIFIERS -> firstDpArgAtLeastMinimum()
        else -> false
    }

    private fun KtCallExpression.firstDpArgAtLeastMinimum(): Boolean {
        val argText = valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return false
        val dp = argText.substringAfterLast('=').trim().takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        return dp >= MIN_TOUCH_TARGET_DP
    }

    private companion object {
        const val MIN_TOUCH_TARGET_DP = 48
        val FILL_MODIFIERS = setOf("fillMaxSize", "fillMaxWidth", "fillMaxHeight", "matchParentSize")
        val SIZE_MODIFIERS =
            setOf("size", "requiredSize", "sizeIn", "width", "height", "requiredWidth", "requiredHeight")
    }
}
