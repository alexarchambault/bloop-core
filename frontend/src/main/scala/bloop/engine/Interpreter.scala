package bloop.engine

import bloop.cli.{CliOptions, Commands, CommonOptions, ExitStatus}
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.Logger
import bloop.tasks.CompilationTasks
import bloop.{CompilerCache, Project}
import sbt.internal.inc.bloop.ZincInternals

object Interpreter {
  def execute(action: Action, logger: Logger): ExitStatus = action match {
    case Exit(exitStatus) => exitStatus
    case Print(msg, commonOptions, next) =>
      printOut(msg, commonOptions)
      execute(next, logger)
    case Run(Commands.About(cliOptions), next) =>
      printAbout(cliOptions)
      execute(next, logger)
    case Run(Commands.Clean(projects, cliOptions), next) =>
      clean(projects, cliOptions, logger)
      execute(next, logger)
    case Run(Commands.Compile(projectName, incremental, cliOptions), next) =>
      compile(projectName, incremental, cliOptions, logger)
      execute(next, logger)
  }

  private final val t = "    "
  private def printAbout(cliOptions: CliOptions): ExitStatus = {
    val bloopName = bloop.internal.build.BuildInfo.name
    val bloopVersion = bloop.internal.build.BuildInfo.version
    val scalaVersion = bloop.internal.build.BuildInfo.scalaVersion
    val zincVersion = bloop.internal.build.BuildInfo.zincVersion
    val developers = bloop.internal.build.BuildInfo.developers.mkString(", ")
    val header =
      s"""|$t   _____            __         ______           __
          |$t  / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____
          |$t  \\__ \\/ ___/ __ `/ / __ `/  / /   / _ \\/ __ \\/ __/ _ \\/ ___/
          |$t ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /
          |$t/____/\\___/\\__,_/_/\\__,_/   \\____/\\___/_/ /_/\\__/\\___/_/
          |""".stripMargin
    val versions = s"""
                      |$t${bloopName.capitalize} version    `$bloopVersion`
                      |${t}Zinc version     `$zincVersion`
                      |${t}Scala version    `$scalaVersion`""".stripMargin
    cliOptions.common.out.println(header)
    cliOptions.common.out.println(t) // This is the only way to add newline, otherwise ignored
    cliOptions.common.out.println(s"$t$bloopName is made with love at the Scala Center <3")
    cliOptions.common.out.println(t)
    cliOptions.common.out.println(versions)
    cliOptions.common.out.println(t)
    cliOptions.common.out.println(s"${t}It is maintained by $developers.")

    ExitStatus.Ok
  }

  private def printOut(msg: String, commonOptions: CommonOptions): ExitStatus = {
    commonOptions.out.println(msg)
    ExitStatus.Ok
  }

  private def constructTasks(projects: Map[String, Project], logger: Logger): CompilationTasks = {
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    val compilerCache = new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"), logger)
    CompilationTasks(projects, compilerCache, logger)
  }

  private def getConfigDir(cliOptions: CliOptions): AbsolutePath = {
    cliOptions.configDir
      .map(AbsolutePath.apply)
      .getOrElse(cliOptions.common.workingPath.resolve(".bloop-config"))
  }

  private def compile(projectName: String,
                      incremental: Boolean,
                      cliOptions: CliOptions,
                      logger: Logger): ExitStatus = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val configDir = getConfigDir(cliOptions)
    val projects = Project.fromDir(configDir, logger)
    val tasks = constructTasks(projects, logger)
    val project = projects(projectName)
    if (incremental) {
      tasks.parallelCompile(project)
      ExitStatus.Ok
    } else {
      val newProjects = tasks.clean(projects.keys.toList)
      val newTasks = tasks.copy(initialProjects = newProjects)
      newTasks.parallelCompile(project)
      ExitStatus.Ok
    }
  }

  private def clean(projectNames: List[String],
                    cliOptions: CliOptions,
                    logger: Logger): ExitStatus = {
    val configDir = getConfigDir(cliOptions)
    val projects = Project.fromDir(configDir, logger)
    val tasks = constructTasks(projects, logger)
    tasks.clean(projectNames).valuesIterator.foreach { project =>
      tasks.persistAnalysis(project, logger)
    }
    ExitStatus.Ok
  }
}