/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sparkdeployer

import java.io.File
import java.util.concurrent.ForkJoinPool
import org.slf4s.Logging
import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.sys.process.stringSeqToProcess
import scala.util.{Failure, Success, Try}

class SparkDeployer(val clusterConf: ClusterConf) extends Logging {
  implicit val iClusterConf = clusterConf
  implicit val ec = ExecutionContext.fromExecutorService(new ForkJoinPool(clusterConf.threadPoolSize))
  
  private val masterName = clusterConf.clusterName + "-master"
  private val workerPrefix = clusterConf.clusterName + "-worker"

  private val machines = new EC2Machines()

  //helper functions
  def getMasterOpt() = machines.getMachines.find(_.name == masterName)
  def getWorkers() = machines.getMachines.filter(_.name.startsWith(workerPrefix))

  private def downloadSpark(machine: Machine) = {
    val extractCmd = s"tar -zxf ${clusterConf.sparkTgzName}"
    if (clusterConf.sparkTgzUrl.startsWith("s3://")) {
      val s3Cmd = Seq("aws", "s3", "cp", "--only-show-errors", clusterConf.sparkTgzUrl, "./").mkString(" ")
      SSH(machine.address).withRemoteCommand(s3Cmd + " && " + extractCmd).withAWSCredentials
    } else {
      SSH(machine.address).withRemoteCommand("wget -nv " + clusterConf.sparkTgzUrl + " && " + extractCmd)
    }
      .withRetry
      .withRunningMessage(s"[${machine.name}] Downloading Spark.")
      .withErrorMessage(s"[${machine.name}] Failed downloading Spark.")
      .run
  }

  private def setupSparkEnv(machine: Machine, masterAddressOpt: Option[String]) = {
    val sparkEnvPath = clusterConf.sparkDirName + "/conf/spark-env.sh"
    val masterAddress = masterAddressOpt.getOrElse(machine.address)
    val sparkEnvConf = (clusterConf.sparkEnv ++ Seq(s"SPARK_MASTER_IP=${masterAddress}", s"SPARK_PUBLIC_DNS=${machine.address}")).mkString("\\n")
    SSH(machine.address)
      .withRemoteCommand(s"echo -e '$sparkEnvConf' > $sparkEnvPath && chmod u+x $sparkEnvPath")
      .withRetry
      .withRunningMessage(s"[${machine.name}] Setting spark-env.")
      .withErrorMessage(s"[${machine.name}] Failed setting spark-env.")
      .run
  }

  private def runSparkSbin(machine: Machine, scriptName: String, args: Seq[String] = Seq.empty) = {
    SSH(machine.address)
      .withRemoteCommand(s"./${clusterConf.sparkDirName}/sbin/${scriptName} ${args.mkString(" ")}")
      .withRetry
      .withRunningMessage(s"[${machine.name}] ${scriptName}.")
      .withErrorMessage(s"[${machine.name}] Failed on ${scriptName}.")
      .run
  }

  private def withFailover[T](op: => T): T = {
    Try { op } match {
      case Success(x) => x
      case Failure(e) =>
        if (clusterConf.destroyOnFail) {
          destroyCluster()
        }
        throw e
    }
  }

  //main functions
  def createMaster() = withFailover {
    assert(getMasterOpt.isEmpty, s"[$masterName] Master already exists.")
    val master = machines.createMachine(Master, masterName)
    downloadSpark(master)
    setupSparkEnv(master, None)
    runSparkSbin(master, "start-master.sh")
    log.info(s"[$masterName] Master started.")
  }

  def addWorkers(num: Int) = withFailover {
    val masterAddress = getMasterOpt.map(_.address).getOrElse(sys.error("Master does not exist, can't create workers."))

    val startIndex = getWorkers()
      .map(_.name)
      .map(_.split("-").last.toInt)
      .sorted.reverse
      .headOption.getOrElse(0) + 1

    val names = (startIndex to startIndex + num - 1).map(workerPrefix + "-" + _).toSet

    val workers = machines.createMachines(Worker, names)

    val futures = workers.map { worker =>
      Future {
        downloadSpark(worker)
        setupSparkEnv(worker, Some(masterAddress))
        runSparkSbin(worker, "start-slave.sh", Seq(s"spark://$masterAddress:7077"))
        log.info(s"[${worker.name}] Worker started.")
      }.recover {
        case e: Exception =>
          log.error(s"[${worker.name}] Failed on setting up worker.", e)
          throw e
      }
    }

    Await.result(Future.sequence(futures), Duration.Inf)
  }

  def createCluster(num: Int) = {
    createMaster()
    addWorkers(num)
  }

  def restartCluster() = {
    getMasterOpt match {
      case None => sys.error("Master does not exist, can't reload cluster.")
      case Some(master) =>
        //setup spark-env
        (getWorkers :+ master).foreach {
          machine => setupSparkEnv(machine, Some(master.address))
        }

        //stop workers
        getWorkers.foreach {
          worker => runSparkSbin(worker, "stop-slave.sh")
        }

        //stop master
        runSparkSbin(master, "stop-master.sh")

        //start master
        runSparkSbin(master, "start-master.sh")

        //start workers
        getWorkers.foreach {
          worker => runSparkSbin(worker, "start-slave.sh", Seq(s"spark://${master.address}:7077"))
        }
    }
  }

  def removeWorkers(num: Int) = {
    val workers = getWorkers
      .sortBy(_.name.split("-").last.toInt).reverse
      .take(num)

    log.info("Destroying workers.")
    machines.destroyMachines(workers.map(_.id).toSet)
  }

  def destroyCluster() = {
    machines.destroyMachines((getWorkers ++ getMasterOpt).map(_.id).toSet)
  }

  def showMachines() = {
    getMasterOpt match {
      case None =>
        log.info("No master found.")
      case Some(master) =>
        log.info(Seq(
          s"[master] ${master.name}. IP address: ${master.address}",
          "Login command: " + SSH(master.address).getCommand,
          s"Web UI: http://${master.address}:8080"
        ).mkString("\n"))
    }

    getWorkers
      .sortBy(_.name.split("-").last.toInt)
      .foreach { worker =>
        log.info(s"[worker] ${worker.name}. IP address: ${worker.address}")
      }
  }

  def uploadJar(jar: File) = {
    getMasterOpt match {
      case None =>
        sys.error("No master found.")
      case Some(master) =>
        val masterAddress = master.address

        val sshCmd = Seq("ssh", "-i", clusterConf.pem,
          "-o", "UserKnownHostsFile=/dev/null",
          "-o", "StrictHostKeyChecking=no").mkString(" ")

        val uploadJarCmd = Seq(
          "rsync",
          "--progress",
          "-ve", sshCmd,
          jar.getAbsolutePath,
          s"ec2-user@$masterAddress:~/job.jar"
        )
        log.info(uploadJarCmd.mkString(" "))
        if (uploadJarCmd.! != 0) {
          sys.error("[rsync-error] Failed uploading jar.")
        } else {
          log.info("Jar uploaded, you can now login to master and submit the job. Login command: " + SSH(master.address).getCommand)
        }
    }
  }

  def submitJob(jar: File, args: Seq[String]) = withFailover {
    uploadJar(jar)

    log.warn("You're submitting job directly, please make sure you have a stable network connection.")
    getMasterOpt().foreach { master =>
      val masterAddress = master.address

      val submitJobCmd = Seq(
        s"./${clusterConf.sparkDirName}/bin/spark-submit",
        "--class", clusterConf.mainClass,
        "--master", s"spark://$masterAddress:7077"
      )
        .++(clusterConf.appName.toSeq.flatMap(n => Seq("--name", n)))
        .++(clusterConf.driverMemory.toSeq.flatMap(m => Seq("--driver-memory", m)))
        .++(clusterConf.executorMemory.toSeq.flatMap(m => Seq("--executor-memory", m)))
        .++("job.jar" +: args)
        .mkString(" ")

      SSH(masterAddress)
        .withRemoteCommand(submitJobCmd)
        .withAWSCredentials
        .withTTY
        .withErrorMessage("Job submission failed.")
        .run
    }
  }

}
