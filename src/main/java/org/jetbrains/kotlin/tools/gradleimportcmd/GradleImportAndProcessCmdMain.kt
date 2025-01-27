package org.jetbrains.kotlin.tools.gradleimportcmd

import com.intellij.compiler.CompilerConfigurationImpl
import com.intellij.compiler.impl.InternalCompileDriver
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarterBase
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil.refreshProject
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.LowMemoryWatcher
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ThreeState
import org.jetbrains.plugins.gradle.settings.DefaultGradleProjectSettings
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val cmd = "importAndProcess"

class GradleImportAndProcessCmdMain : ApplicationStarterBase(cmd, 3) {
    // exit codes
    val invalidCommandLine = 2
    val lowMemory = 3
    val internalError = 4
    val compilationFailed = 5

    override fun isHeadless(): Boolean = true
    private val IMPORT_AND_BUILD = "importAndBuild"
    override fun getUsageMessage(): String = "Usage: idea $cmd [importAndSave|$IMPORT_AND_BUILD] <path-to-gradle-project> <path-to-jdk>"


    private lateinit var projectPath: String
    private lateinit var jdkPath: String
    private var isBuildEnabled: Boolean = false
    private lateinit var mySdk: Sdk

    private fun printHelp() {
        println(usageMessage)
        exitProcess(invalidCommandLine)
    }

    override fun premain(args: Array<out String>) {
        if (args.size != 4) {
            printHelp()
        }

        isBuildEnabled = args[1] == IMPORT_AND_BUILD
        projectPath = args[2]
        jdkPath = args[3]
        if (!File(projectPath).isDirectory) {
            printMessage("$projectPath is not directory", MessageStatus.ERROR)
            printHelp()
        }
    }

    private fun printMemory(afterGc: Boolean) {
        val runtime = Runtime.getRuntime()
        printMessage("Low memory ${if (afterGc) "after GC" else ", invoking GC"}. Total memory=${runtime.totalMemory()}, free=${runtime.freeMemory()}", if (afterGc) MessageStatus.ERROR else MessageStatus.WARNING)
    }

    private fun getUsedMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    override fun processCommand(args: Array<String>, currentDirectory: String?) {
        printProgress("Processing command $args in working directory $currentDirectory")
        System.setProperty("idea.skip.indices.initialization", "true")

        // add low memory notifications
        var afterGcLowMemoryNotifier = LowMemoryWatcher.register({
            printMemory(true)
            exitProcess(lowMemory)

        }, LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC)
        var beforeGcLowMemoryNotifier = LowMemoryWatcher.register({ printMemory(false) },
                LowMemoryWatcher.LowMemoryWatcherType.ALWAYS)


        val application = ApplicationManagerEx.getApplicationEx()

        try {
            val project = application.runReadAction(Computable<Project?> {
                return@Computable try {
                    application.isSaveAllowed = true
                    importProject()
                } catch (e: Exception) {
                    printException(e)
                    null
                }
            })
            if (isBuildEnabled) {
                buildProject(project)
            }
        } catch (t: Throwable) {
            printException(t)
        } finally {
            afterGcLowMemoryNotifier = null // low memory notifications are not required any more
            beforeGcLowMemoryNotifier = null
            printProgress("Exit application")
            application.exit(true, true)
        }
    }

    private fun buildProject(project: Project?) {
        val finishedLautch = CountDownLatch(1)
        if (project == null) {
            printMessage("Project is null", MessageStatus.ERROR)
            exitProcess(internalError)
        } else {
            startOperation(OperationType.COMPILATION,"Compile project with JPS")
            var errorsCount = 0
            var abortedStatus = false
            var compilationStarted = System.nanoTime()
            val callback = CompileStatusNotification { aborted, errors, warnings, compileContext ->
                run {
                    try {
                        errorsCount = errors
                        abortedStatus = aborted
                        printMessage("Compilation done. Aborted=$aborted, Errors=$errors, Warnings=$warnings", MessageStatus.WARNING)
                        reportStatistics("jps_compilation_errors", errors.toString())
                        reportStatistics("jps_compilation_warnings", warnings.toString())
                        reportStatistics("jps_compilation_duration", ((System.nanoTime() - compilationStarted)/1000_000).toString())

                        CompilerMessageCategory.values().forEach { category ->
                            compileContext.getMessages(category).forEach {
                                val message = "$category - ${it.virtualFile?.canonicalPath ?: "-"}: ${it.message}"
                                when (category) {
                                    CompilerMessageCategory.ERROR -> {
                                        printMessage(message, MessageStatus.ERROR)
                                        //reportTestError("Compile project with JPS", message)
                                    }
                                    CompilerMessageCategory.WARNING -> printMessage(message, MessageStatus.WARNING)
                                    else -> printMessage(message)
                                }
                            }
                        }
                    } finally {
                        finishedLautch.countDown()
                    }
                }
            }

            CompilerConfigurationImpl.getInstance(project).setBuildProcessHeapSize(3500)

            val compileContext = InternalCompileDriver(project).rebuild(callback)
            while (!finishedLautch.await(1, TimeUnit.MINUTES)) {
                if (!compileContext.progressIndicator.isRunning) {
                    printMessage("Progress indicator says that compilation is not running.", MessageStatus.ERROR)
                    break
                }
                printProgress("Compilation status: Errors: ${compileContext.getMessages(CompilerMessageCategory.ERROR).size}. Warnings: ${compileContext.getMessages(CompilerMessageCategory.WARNING).size}.")
            }

            if (errorsCount > 0 || abortedStatus) {
                finishOperation(OperationType.COMPILATION,"Compile project with JPS", "Compilation failed with $errorsCount errors")
                exitProcess(compilationFailed)
            } else {
                finishOperation(OperationType.COMPILATION,"Compile project with JPS")
            }
        }
    }


    private fun importProject(): Project? {
        printProgress("Opening project")
        projectPath = projectPath.replace(File.separatorChar, '/')
        val vfsProject = LocalFileSystem.getInstance().findFileByPath(projectPath)
        if (vfsProject == null) {
            printMessage("Cannot find directory $projectPath", MessageStatus.ERROR)
            printHelp()
        }

        val project = ProjectUtil.openProject(projectPath, null, false)

        if (project == null) {
            printMessage("Unable to open project", MessageStatus.ERROR)
            gracefulExit(project)
            return null
        }
        DefaultGradleProjectSettings.getInstance(project).isDelegatedBuild = false
        printProgress("Project loaded, refreshing from Gradle")
        WriteAction.runAndWait<RuntimeException> {
            val sdkType = JavaSdk.getInstance()
            mySdk = sdkType.createJdk("JDK_1.8", jdkPath, false)

            ProjectJdkTable.getInstance().addJdk(mySdk)
            ProjectRootManager.getInstance(project).projectSdk = mySdk
        }

        val projectSettings = GradleProjectSettings()
        projectSettings.externalProjectPath = projectPath
        projectSettings.delegatedBuild = ThreeState.NO // disable delegated build in irder to run JPS build
        projectSettings.distributionType = DistributionType.DEFAULT_WRAPPED // use default wrapper
        projectSettings.storeProjectFilesExternally = ThreeState.NO
        projectSettings.withQualifiedModuleNames()

        val systemSettings = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
        val linkedSettings: Collection<ExternalProjectSettings> = systemSettings.getLinkedProjectsSettings() as Collection<ExternalProjectSettings>
        linkedSettings.filter { it is GradleProjectSettings }.forEach { systemSettings.unlinkExternalProject(it.externalProjectPath) }

        systemSettings.linkProject(projectSettings)

        val startTime = System.nanoTime() //NB do not use currentTimeMillis() as it is sensitive to time adjustment
        startOperation(OperationType.TEST,"Import project")
        reportStatistics("used_memory_before_import", getUsedMemory().toString())
        reportStatistics("total_memory_before_import", Runtime.getRuntime().totalMemory().toString())
        refreshProject(
                project,
                GradleConstants.SYSTEM_ID,
                projectPath,
                object : ExternalProjectRefreshCallback {
                    override fun onSuccess(externalProject: DataNode<ProjectData>?) {
                        reportStatistics("import_duration", ((System.nanoTime() - startTime)/1000_000).toString())
                        if (externalProject != null) {
                            finishOperation(OperationType.TEST,"Import project", duration = (System.nanoTime() - startTime) / 1000_000)
                            ServiceManager.getService(ProjectDataManager::class.java)
                                    .importData(externalProject, project, true)
                        } else {
                            finishOperation(OperationType.TEST,"Import project", "Filed to import project. See IDEA logs for details")
                            gracefulExit(project)
                        }
                        if (externalProject != null) {
                            val start = System.nanoTime()
                            val memoryLeakTestName = "Check for memory leaks"
                            startOperation(OperationType.TEST, memoryLeakTestName)
                            val memoryLeakChecker = MemoryLeakChecker(externalProject) {printMessage(it, MessageStatus.ERROR)}
                            reportStatistics("memory_number_of_leaked_objects", memoryLeakChecker.leakedObjects.size.toString())
                            if (memoryLeakChecker.errorsCount == 0) {
                                finishOperation(OperationType.TEST, memoryLeakTestName, duration = ((System.nanoTime() - start) / 1000_000))
                            } else {
                                finishOperation(OperationType.TEST, memoryLeakTestName, failureMessage = "Check for memory leaks finished with ${memoryLeakChecker.errorsCount} errors.", duration = ((System.nanoTime() - start) / 1000_000))
                            }
                        }
                    }

                    override fun onFailure(externalTaskId: ExternalSystemTaskId, errorMessage: String, errorDetails: String?) {
                        finishOperation(OperationType.TEST,"Import project", "Filed to import project: $errorMessage. Details: $errorDetails")
                    }
                },
                false,
                ProgressExecutionMode.MODAL_SYNC,
                true
        )
        reportStatistics("used_memory_after_import", getUsedMemory().toString())
        reportStatistics("total_memory_after_import", Runtime.getRuntime().totalMemory().toString())
        System.gc()
        reportStatistics("used_memory_after_import_gc", getUsedMemory().toString())
        reportStatistics("total_memory_after_import_gc", Runtime.getRuntime().totalMemory().toString())

        printProgress("Unloading buildSrc modules")

        val moduleManager = ModuleManager.getInstance(project)
        val buildSrcModuleNames = moduleManager.sortedModules
                .filter { it.name.contains("buildSrc") }
                .map { it.name }
        moduleManager.setUnloadedModules(buildSrcModuleNames)

        printProgress("Save IDEA projects")

        project.save()
        ProjectManagerEx.getInstanceEx().openProject(project)
        FileDocumentManager.getInstance().saveAllDocuments()
        ApplicationManager.getApplication().saveSettings()
        ApplicationManager.getApplication().saveAll()

        printProgress("Import done")
        return project
    }


    private fun gracefulExit(project: Project?) {
        if (project?.isDisposed == false) {
            ProjectUtil.closeAndDispose(project)
        }
        throw RuntimeException("Failed to proceed")
    }
}