package bloop.nailgun

import java.io.PrintStream

import org.junit.Assert.{assertEquals, assertNotEquals}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import bloop.Server
import bloop.bsp.BspServer
import bloop.logging.{ProcessLogger, RecordingLogger}
import bloop.tasks.TestUtil
import com.martiansoftware.nailgun.NGServer
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.commons.io.IOUtils

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

/**
 * Base class for writing test for the nailgun integration.
 */
abstract class NailgunTest {

  private final val TEST_PORT = 8998

  private final val nailgunPool = Scheduler.computation(parallelism = 2)

  /**
   * Starts a Nailgun server, creates a client and executes operations with that client.
   * The server is shut down at the end of `op`.
   *
   * @param log  The logger that will receive all produced output.
   * @param config The config directory in which the client will be.
   * @param op   A function that will receive the instantiated Client.
   * @return The result of executing `op` on the client.
   */
  def withServer[T](log: RecordingLogger, config: Path)(op: Client => T): T = {
    val oldIn = System.in
    val oldOut = System.out
    val oldErr = System.err
    val inStream = IOUtils.toInputStream("")
    val outStream = new PrintStream(ProcessLogger.toOutputStream(log.serverInfo))
    val errStream = new PrintStream(ProcessLogger.toOutputStream(log.serverError))

    val serverIsStarted = scala.concurrent.Promise[Unit]()
    val serverIsFinished = scala.concurrent.Promise[Unit]()
    val serverLogic = Task {
      var optServer: Option[NGServer] = None

      // Trick nailgun into thinking these are the real streams
      System.setIn(inStream)
      System.setOut(outStream)
      System.setErr(errStream)
      try {
        optServer = Some(Server.instantiateServer(Array(TEST_PORT.toString)))
      } finally {
        System.setIn(oldIn)
        System.setOut(oldOut)
        System.setErr(oldErr)
      }

      val server = optServer.getOrElse(sys.error("The nailgun server failed to initialize!"))
      serverIsStarted.success(())
      server.run()
      serverIsFinished.success(())
    }

    val client = new Client(TEST_PORT, log, config)
    val clientLogic = Task(op(client))
      .onErrorRestart(3)
      .doOnFinish(_ =>
        Task {
          client.success("exit")
          outStream.flush()
          errStream.flush()
      })

    val startTrigger = Task.fromFuture(serverIsStarted.future)
    val endTrigger = Task.fromFuture(serverIsFinished.future)
    val runClient = startTrigger.flatMap(_ => clientLogic.flatMap(_ => endTrigger))
    val f = Task.zip2(serverLogic, startTrigger.flatMap(_ => clientLogic)).runAsync(nailgunPool)
    Await.result(f, FiniteDuration(60, TimeUnit.SECONDS))._2
  }

  /**
   * Starts a server and provides a client in `base`. A logger that will receive
   * all output will be created and passed to `op`.
   *
   * @param config The config directory where the client will be.
   * @param op   A function that accepts a logger and a client.
   * @return The result of executing `op` on the logger and client.
   */
  def withServerIn[T](config: Path)(op: (RecordingLogger, Client) => T): T = {
    val logger = new RecordingLogger
    withServer(logger, config)(op(logger, _))
  }

  /**
   * Starts a server and provides a client in the directory of project `name`.
   * A logger that will receive all output will be created and passed to `op`.
   *
   * @param base The base directory where the client will be.
   * @param op   A function that accepts a logger and a client.
   * @return The result of executing `op` on the logger and client.
   */
  def withServerInProject[T](name: String)(op: (RecordingLogger, Client) => T): T = {
    withServerIn(TestUtil.getBloopConfigDir(name))(op)
  }

  /**
   * A client that interacts with a running Nailgun server.
   *
   * @param port The port on which the client should communicate with the server.
   * @param base The base directory from which the client is running.
   */
  private case class Client(port: Int, log: RecordingLogger, config: Path) {
    private val base = TestUtil.getBaseFromConfigDir(config)
    private val configPath = config.toAbsolutePath.toString
    private val clientPath = bloop.internal.build.BuildInfo.nailgunClientLocation.getAbsolutePath

    assert(Files.exists(base), s"Base directory doesn't exist: '$base'.")
    assert(Files.exists(Paths.get(clientPath)), s"Couldn't find Nailgun client at '$clientPath'.")

    private def processBuilder(cmd: Seq[String]): ProcessBuilder = {
      val cmdBase =
        if (BspServer.isWindows) "python" :: clientPath.toString :: Nil
        else clientPath.toString :: Nil
      val builder = new ProcessBuilder((cmdBase ++ (s"--nailgun-port=$port" +: cmd)): _*)
      builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
      val env = builder.environment()
      env.put("BLOOP_OWNER", "owner")
      builder.directory(base.toFile)
    }

    /**
     * Execute a command `cmd` on the server and return the exit code.
     *
     * @param cmd The command to execute
     * @return The exit code of the operation.
     */
    def issue(cmd: String*): Int = {
      issueAsProcess(cmd: _*).waitFor()
    }

    /**
     * Execute a command `cmd` on the server and return the process
     * executing the specified command.
     *
     * @param cmd The command to execute
     * @return The exit code of the operation.
     */
    def issueAsProcess(cmd0: String*): Process = {
      val cmd = cmd0 ++ List("--config-dir", configPath)
      val builder = processBuilder(cmd)
      val process = builder.start()
      val processLogger = new ProcessLogger(log, process)
      processLogger.start()
      process
    }

    /**
     * Executes a command `cmd` expecting a 0 exit code.
     *
     * @param cmd The command to run.
     */
    def success(cmd: String*): Unit = {
      val failMessage: String =
        s"""Success expected, but command failed. Logs were:
           |${log.getMessages.mkString("\n")}
           |""".stripMargin
      assertEquals(failMessage, 0, issue(cmd: _*).toLong)
    }

    /**
     * Executes a command `cmd`, expecting a non-zero exit code.
     *
     * @param cmd The command to run.
     */
    def fail(cmd: String*): Unit = {
      val failMessage: String =
        s"""Failure expected, but command succeeded. Logs were:
           |${log.getMessages.mkString("\n")}
           |""".stripMargin
      assertNotEquals(failMessage, 0, issue(cmd: _*).toLong)
    }
  }
}
