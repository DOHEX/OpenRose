package io.github.dohex.openrose.detekt

import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.psi.absolutePath
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path

const val COMMENT_BUDGET_PATH = "config/detekt/comment-budget.txt"

class NoComments(config: Config) :
    Rule(
        config,
        "Code carries no comments. Decisions go to docs/adr, workarounds to GitHub issues; " +
            "files with legacy comments may only shrink their entry in $COMMENT_BUDGET_PATH.",
    ) {

    override fun visit(root: KtFile) {
        val comments = PsiTreeUtil.collectElementsOfType(root, PsiComment::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, PsiComment::class.java) == null }
            .sortedBy { it.textOffset }
        val file = root.absolutePath()
        val budgetFile = findBudgetFile(file)
        val key = budgetFile?.parent?.parent?.parent?.relativize(file)?.joinToString("/")
        val allowed = budgetFile?.let { readBudget(it)[key] } ?: 0
        when {
            comments.size > allowed -> report(
                Finding(
                    Entity.from(comments[allowed]),
                    "$key has ${comments.size} comments; its budget is $allowed. Delete comments: " +
                        "decisions go to docs/adr, workarounds to GitHub issues.",
                ),
            )

            comments.size < allowed -> report(
                Finding(
                    Entity.atPackageOrFirstDecl(root),
                    "$key has ${comments.size} comments but its budget is $allowed. " +
                        "Lower its entry in $COMMENT_BUDGET_PATH to ${comments.size}, or remove the entry at 0.",
                ),
            )
        }
    }

    private companion object {
        fun findBudgetFile(file: Path): Path? = generateSequence(file.parent) { it.parent }
            .map { it.resolve(COMMENT_BUDGET_PATH) }
            .firstOrNull(Files::isRegularFile)

        fun readBudget(path: Path): Map<String, Int> = Files.readAllLines(path)
            .filter { it.isNotBlank() }
            .associate { line ->
                val (count, file) = line.trim().split(Regex("\\s+"), limit = 2)
                file to count.toInt()
            }
    }
}
