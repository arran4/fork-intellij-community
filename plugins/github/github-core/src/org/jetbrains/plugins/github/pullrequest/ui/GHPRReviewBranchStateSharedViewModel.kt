// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.pullrequest.ui

import com.intellij.collaboration.async.stateInNow
import com.intellij.collaboration.util.getOrNull
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.util.coroutines.childScope
import git4idea.remote.hosting.isInCurrentHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.jetbrains.plugins.github.pullrequest.data.GHPRDataContext
import org.jetbrains.plugins.github.pullrequest.data.provider.GHPRDataProvider
import org.jetbrains.plugins.github.pullrequest.data.provider.detailsComputationFlow
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRBranchesViewModel
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRBranchesViewModel.Companion.getRemoteDescriptor
import git4idea.branch.GitBranchPair
import git4idea.remote.hosting.GitRemoteBranchesUtil
import git4idea.update.GitUpdateInfoAsLog
import git4idea.update.GitUpdateSession
import git4idea.update.GitUpdatedRanges
import kotlin.coroutines.cancellation.CancellationException

private val LOG = logger<GHPRReviewBranchStateSharedViewModel>()

internal class GHPRReviewBranchStateSharedViewModel(
  parentCs: CoroutineScope,
  private val dataContext: GHPRDataContext,
  private val dataProvider: GHPRDataProvider
) {
  private val cs = parentCs.childScope(javaClass.name)

  private val repository = dataContext.repositoryDataService.remoteCoordinates.repository

  val updateRequired: StateFlow<Boolean> =
    repository.isInCurrentHistory(
      rev = dataProvider.detailsData.detailsComputationFlow.mapNotNull { it.getOrNull() }.map { it.headRefOid }
    ).map { it?.not() ?: false }.stateInNow(cs, false)

  private val _updateErrors = MutableSharedFlow<Exception>()
  val updateErrors: SharedFlow<Exception> = _updateErrors.asSharedFlow()

  fun updateBranch() {
    cs.launch {
      doUpdateBranch()
    }
  }

  private suspend fun doUpdateBranch() {
    val details = try {
      val detailsData = dataProvider.detailsData
      detailsData.signalDetailsNeedReload()
      detailsData.loadDetails()
    }
    catch (ce: CancellationException) {
      throw ce
    }
    catch (e: Exception) {
      LOG.warn("Pull request branch update failed", e)
      _updateErrors.emit(e)
      return
    }
    val server = dataContext.repositoryDataService.repositoryCoordinates.serverPath

    var updateRanges: GitUpdatedRanges? = null
    val remoteDescriptor = details.headRepository?.owner?.login?.let { owner ->
        org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRBranchesViewModel.Companion.run {
            details.headRepository?.getRemoteDescriptor(server)
        }
    }

    if (remoteDescriptor != null) {
      val remoteBranch = GitRemoteBranchesUtil.findRemoteBranch(repository.info, remoteDescriptor, details.headRefName)
      if (remoteBranch != null) {
        val localBranch = repository.currentBranch ?: repository.branches.findLocalBranch(details.headRefName)
        if (localBranch != null) {
          updateRanges = GitUpdatedRanges.calcInitialPositions(repository.project, mapOf(repository to GitBranchPair(localBranch, remoteBranch)))
        }
      }
    }

    GHPRBranchesViewModel.fetchAndCheckoutBranch(repository, server, details)

    if (updateRanges != null) {
      val updatedPositions = updateRanges.calcCurrentPositions()
      val updateNotificationData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          GitUpdateInfoAsLog(repository.project, updatedPositions).calculateDataAndCreateLogTab()
      }
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
          GitUpdateSession(repository.project, updateNotificationData, true, emptyMap()).showNotification()
      }
    }
  }
}