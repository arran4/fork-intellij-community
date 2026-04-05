// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.merge

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.ThreeSide
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.VirtualFile

object MergeConflictLLMUtil {
  fun generateMergeConflictDiff(data: MergeData, file: VirtualFile, indicator: ProgressIndicator): String {
    val charset = file.charset
    val original = if (data.ORIGINAL != null) String(data.ORIGINAL, charset) else ""
    val last = if (data.LAST != null) String(data.LAST, charset) else ""
    val current = if (data.CURRENT != null) String(data.CURRENT, charset) else ""

    val fragments = ComparisonManager.getInstance().mergeLines(
      current, original, last, ComparisonPolicy.DEFAULT, indicator
    )

    val currentLines = DiffUtil.getLines(current)
    val originalLines = DiffUtil.getLines(original)
    val lastLines = DiffUtil.getLines(last)

    val sb = StringBuilder()
    var baseLine = 0

    for (fragment in fragments) {
      // Append unchanged base lines
      for (i in baseLine until fragment.getStartLine(ThreeSide.BASE)) {
        sb.append(originalLines[i]).append('\n')
      }

      val startLeft = fragment.getStartLine(ThreeSide.LEFT)
      val endLeft = fragment.getEndLine(ThreeSide.LEFT)
      val startBase = fragment.getStartLine(ThreeSide.BASE)
      val endBase = fragment.getEndLine(ThreeSide.BASE)
      val startRight = fragment.getStartLine(ThreeSide.RIGHT)
      val endRight = fragment.getEndLine(ThreeSide.RIGHT)

      sb.append("<<<<<<< Ours\n")
      for (i in startLeft until endLeft) {
        sb.append(currentLines[i]).append('\n')
      }
      sb.append("||||||| Base\n")
      for (i in startBase until endBase) {
        sb.append(originalLines[i]).append('\n')
      }
      sb.append("=======\n")
      for (i in startRight until endRight) {
        sb.append(lastLines[i]).append('\n')
      }
      sb.append(">>>>>>> Theirs\n")

      baseLine = endBase
    }

    // Append remaining unchanged base lines
    for (i in baseLine until originalLines.size) {
      sb.append(originalLines[i]).append('\n')
    }

    return sb.toString()
  }
}
