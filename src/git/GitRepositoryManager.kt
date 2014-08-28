package org.jetbrains.plugins.settingsRepository.git

import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.SmartList
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.*
import org.jetbrains.annotations.TestOnly
import org.jetbrains.plugins.settingsRepository.BaseRepositoryManager
import org.jetbrains.plugins.settingsRepository.CredentialsStore

import java.io.File
import java.io.IOException

public class GitRepositoryManager(private val credentialsStore: NotNullLazyValue<CredentialsStore>) : BaseRepositoryManager() {
  val git: GitEx

  private var credentialsProvider: CredentialsProvider? = null

  {
    val repository = FileRepositoryBuilder().setWorkTree(dir).build()
    git = GitEx(repository)
    if (!dir.exists()) {
      repository.create()
      git.disableAutoCrLf()
    }
  }

  TestOnly
  public fun testOnlyGetGit(): GitEx {
    return git
  }

  throws(javaClass<IOException>())
  override fun initRepository(dir: File) {
    GitEx.createBareRepository(dir)
  }

  fun getCredentialsProvider(): CredentialsProvider {
    if (credentialsProvider == null) {
      credentialsProvider = JGitCredentialsProvider(credentialsStore, git.getRepository())
    }
    return credentialsProvider!!
  }

  override fun getRemoteRepositoryUrl(): String? {
    return StringUtil.nullize(git.getRepository().getConfig().getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, ConfigConstants.CONFIG_KEY_URL))
  }

  throws(javaClass<Exception>())
  override fun setUpstream(url: String?, branch: String?) {
    git.setUpstream(url, branch)
  }

  fun createCommitCommand(): CommitCommand {
    val author = PersonIdent(git.getRepository())
    val committer = PersonIdent(ApplicationInfoEx.getInstanceEx()!!.getFullApplicationName(), author.getEmailAddress())
    return git.commit().setAuthor(author).setCommitter(committer)
  }

  override fun hasUpstream(): Boolean {
    return !StringUtil.isEmptyOrSpaces(git.getRepository().getConfig().getString("remote", "origin", "url"))
  }

  override fun addToIndex(file: File, path: String) {
    git.add(path)
  }

  override fun deleteFromIndex(path: String, isFile: Boolean) {
    git.remove(path, isFile)
  }

  override fun commit(indicator: ProgressIndicator) {
    synchronized (lock) {
      CommitTask.execute(this, indicator)
    }
  }

  override fun commit(paths: List<String>) {
  }

  override fun push(indicator: ProgressIndicator) {
    BaseRepositoryManager.LOG.debug("Push")

    val repository = git.getRepository()
    val refSpecs = SmartList(RemoteConfig(repository.getConfig(), Constants.DEFAULT_REMOTE_NAME).getPushRefSpecs())
    if (refSpecs.isEmpty()) {
      val head = repository.getRef(Constants.HEAD)
      if (head != null && head.isSymbolic())
        refSpecs.add(RefSpec(head.getLeaf().getName()))
    }

    val monitor = JGitProgressMonitor(indicator)
    for (transport in Transport.openAll(repository, Constants.DEFAULT_REMOTE_NAME, Transport.Operation.PUSH)) {
      transport.setCredentialsProvider(getCredentialsProvider())

      try {
        val result = transport.push(monitor, transport.findRemoteRefUpdatesFor(refSpecs))
        if (BaseRepositoryManager.LOG.isDebugEnabled()) {
          printMessages(result)

          for (refUpdate in result.getRemoteUpdates()) {
            BaseRepositoryManager.LOG.debug(refUpdate.toString())
          }
        }
      }
      catch (e: TransportException) {
        wrapIfNeedAndReThrow(e)
      }
      finally {
        transport.close()
      }
    }
  }

  override fun pull(indicator: ProgressIndicator) {
    pull(this, indicator)
  }

  override fun isValidRepository(file: File): Boolean {
    if (File(file, Constants.DOT_GIT).exists()) {
      return true
    }
    // existing bare repository
    try {
      FileRepositoryBuilder().setGitDir(file).setMustExist(true).build()
    }
    catch (e: IOException) {
      return false
    }

    return true
  }

  class object {

    fun printMessages(fetchResult: OperationResult) {
      if (BaseRepositoryManager.LOG.isDebugEnabled()) {
        val messages = fetchResult.getMessages()
        if (!StringUtil.isEmptyOrSpaces(messages)) {
          BaseRepositoryManager.LOG.debug(messages)
        }
      }
    }
  }
}
