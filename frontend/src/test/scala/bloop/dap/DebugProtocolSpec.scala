package bloop.dap

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.DebugSessionParamsDataKind._
import monix.eval.Task
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

object DebugProtocolSpec extends DebugBspBaseSuite {
  test("starts a debug session") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello, World!")
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            output <- client.blockForAllOutput
          } yield output
        }

        assertNoDiff(output, "Hello, World!\n")
      }
    }
  }

  // when the session detaches from the JVM, the JDI once again writes to the standard output
  test("restarted session does not contain JDI output") {
    TestUtil.retry() {
      jdiOutputTest()
    }
  }
  def jdiOutputTest(): Unit = {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    Thread.sleep(10000)
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()

            previousSession <- client.restart()

            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.disconnect()

            previousSessionOutput <- previousSession.takeCurrentOutput
          } yield previousSessionOutput
        }

        assertNoDiff(output, "")
      }
    }
  }

  test("picks up source changes across sessions") {
    TestUtil.retry() {
      crossSessionsSourceChangesTest()
    }
  }
  def crossSessionsSourceChangesTest(): Unit = {
    val correctMain =
      """
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    println("Non-blocking Hello!")
        |  }
        |}
     """.stripMargin

    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Blocking Hello!")
           |    synchronized(wait())
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        // start debug session and the immediately disconnect from it
        val blockingSessionOutput = state.withDebugSession(project, mainClassParams("Main")) {
          client =>
            for {
              _ <- client.initialize()
              _ <- client.launch()
              _ <- client.initialized
              _ <- client.configurationDone()
              output <- client.takeCurrentOutput.restartUntil(!_.isEmpty)
              _ <- client.disconnect()
            } yield output
        }

        assertNoDiff(blockingSessionOutput, "Blocking Hello!")

        // fix the main class
        val sources = state.toTestState.getProjectFor(project).sources
        val mainFile = sources.head.resolve("Main.scala")
        Files.write(mainFile.underlying, correctMain.getBytes(StandardCharsets.UTF_8))

        // start the next debug session
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            output <- client.blockForAllOutput
          } yield output
        }

        assertNoDiff(output, "Non-blocking Hello!")
      }
    }
  }

  flakyTest("starts test suites", 3) {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      for (suffix <- Seq("0", "x")) {
        loadBspBuildFromResources(s"cross-test-build-scalajs-1.$suffix", workspace, logger) {
          build =>
            val project = build.projectFor("test-project-test")
            val testFilters = List("hello.JUnitTest")

            val output = build.state.withDebugSession(project, testSuiteParams(testFilters)) {
              client =>
                for {
                  _ <- client.initialize()
                  _ <- client.launch()
                  _ <- client.configurationDone()
                  _ <- client.terminated
                  output <- client.blockForAllOutput
                } yield output
            }

            testFilters.foreach { testSuite =>
              assert(output.contains(s"All tests in $testSuite passed"))
            }
        }
      }
    }
  }

  def mainClassParams(mainClass: String): bsp.BuildTargetIdentifier => bsp.DebugSessionParams = {
    target =>
      val targets = List(target)
      val data = bsp.ScalaMainClass(mainClass, Nil, Nil, Nil)
      val json = bsp.ScalaMainClass.encodeScalaMainClass(data)
      bsp.DebugSessionParams(targets, ScalaMainClass, json)
  }

  def testSuiteParams(
      filters: List[String]
  ): bsp.BuildTargetIdentifier => bsp.DebugSessionParams = { target =>
    import io.circe.syntax._
    val targets = List(target)
    val json = filters.asJson
    bsp.DebugSessionParams(targets, ScalaTestSuites, json)
  }
}
