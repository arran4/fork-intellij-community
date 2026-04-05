// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.merge

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.treeStructure.treetable.TreeTable
import java.awt.datatransfer.StringSelection

class CopyMergeConflictDiffAction(
  private val project: Project?,
  private val table: TreeTable,
  private val mergeProvider: MergeProvider
) : DumbAwareAction(VcsBundle.message("multiple.file.merge.copy.diff.action.name")) {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  private val TreeTable.selectedFiles: List<VirtualFile>
    get() = VcsTreeModelData.selected(tree).userObjects(VirtualFile::class.java)

  override fun update(e: AnActionEvent) {
    val selectedFiles = table.selectedFiles
    e.presentation.isEnabledAndVisible = selectedFiles.isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val selectedFiles = table.selectedFiles
    if (selectedFiles.isEmpty()) return

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying Merge Conflict Diff", true) {
      override fun run(indicator: ProgressIndicator) {
        val sb = StringBuilder()
        for (file in selectedFiles) {
          indicator.checkCanceled()
          if (sb.isNotEmpty()) {
            sb.append("\n\n")
          }
          sb.append("File: ").append(file.presentableUrl).append("\n")
          sb.append("===================================================================\n")
          try {
            val mergeData = mergeProvider.loadRevisions(file)
            val diff = MergeConflictLLMUtil.generateMergeConflictDiff(mergeData, file, indicator)
            sb.append(diff)
          } catch (ex: ProcessCanceledException) {
            throw ex
          } catch (ex: Exception) {
            Logger.getInstance(CopyMergeConflictDiffAction::class.java).warn("Failed to load revisions for ${file.path}", ex)
            sb.append("<Failed to load revisions: ${ex.message}>\n")
          }
        }

        CopyPasteManager.getInstance().setContents(StringSelection(sb.toString()))
      }
    })
  }
}
