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

import Helpers.retry
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{BlockDeviceMapping, CreateTagsRequest, EbsBlockDevice, RunInstancesRequest, Tag, TerminateInstancesRequest}
import org.slf4s.Logging
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class EC2Machines(implicit clusterConf: ClusterConf) extends Machines with Logging {
  private val ec2 = new AmazonEC2Client().withRegion[AmazonEC2Client](Regions.fromName(clusterConf.region))

  def createMachines(machineType: MachineType, names: Set[String]) = {
    //refill the number of machines with retry to workaround AWS's bug.
    @annotation.tailrec
    def fill(existMachines: Seq[Machine], attempts: Int): Seq[Machine] = {
      val number = names.size - existMachines.size

      val req = Some(new RunInstancesRequest())
        .map {
          _.withBlockDeviceMappings(new BlockDeviceMapping()
            .withDeviceName("/dev/xvda")
            .withEbs(new EbsBlockDevice()
              .withVolumeSize(machineType match {
                case Master => clusterConf.masterDiskSize
                case Worker => clusterConf.workerDiskSize
              })
              .withVolumeType("gp2")))
            .withImageId(clusterConf.ami)
            .withInstanceType(machineType match {
              case Master => clusterConf.masterInstanceType
              case Worker => clusterConf.workerInstanceType
            })
            .withKeyName(clusterConf.keypair)
            .withMaxCount(number)
            .withMinCount(number)
        }
        .map(req => clusterConf.securityGroupIds.map(ids => req.withSecurityGroupIds(ids.asJava)).getOrElse(req))
        .map(req => clusterConf.subnetId.map(id => req.withSubnetId(id)).getOrElse(req))
        .get

      log.info(s"[EC2] Creating $number instances...")
      val instances = ec2.runInstances(req).getReservation.getInstances.asScala.toSeq

      val results: Seq[Either[Machine, String]] = instances.zip(names -- existMachines.map(_.name)).map {
        case (instance, name) =>
          val id = instance.getInstanceId
          Try {
            retry { i =>
              log.info(s"[EC2] [$id] Naming instance. Attempts: $i.")
              ec2.createTags(new CreateTagsRequest().withResources(id).withTags(new Tag("Name", name)))
            }

            //retry getting address if the instance exists.
            val address = retry { i =>
              log.info(s"[EC2] [$id] Getting instance's address. Attempts: $i.")
              getNonTerminatedInstances.find(_.getInstanceId == id) match {
                case Some(instance) =>
                  val address = if (clusterConf.usePrivateIp) instance.getPrivateIpAddress else instance.getPublicDnsName
                  if (address == null || address == "") {
                    sys.error(s"Invalid address: $address")
                  }
                  Some(address)
                case None => None
              }
            }.getOrElse(sys.error("Instance not found when getting address."))

            Machine(id, name, address)
          } match {
            case Success(m) => Left(m)
            case Failure(e) =>
              log.warn(s"[EC2] [$id] API error when creating instance.", e)
              Right(id)
          }
      }

      //since destroyMachines need to check status, destroy them all together.
      val failedIds = results.collect { case Right(id) => id }.toSet
      if (failedIds.nonEmpty) {
        destroyMachines(failedIds)
      }

      val newMachines = results.collect { case Left(machine) => machine }

      if (newMachines.size == number) {
        existMachines ++ newMachines
      } else if (attempts > 1) {
        fill(existMachines ++ newMachines, attempts - 1)
      } else {
        sys.error("[EC2] Failed on creating enough instances.")
      }
    }

    //only try 3 times for now
    fill(Seq.empty, 3)
  }

  private def getNonTerminatedInstances() = {
    ec2.describeInstances().getReservations.asScala.flatMap(_.getInstances.asScala).toSeq
      .filter(_.getKeyName == clusterConf.keypair)
      .filter(_.getState.getName != "terminated")
  }

  def destroyMachines(ids: Set[String]) = {
    val existingIds = getNonTerminatedInstances.map(_.getInstanceId).toSet & ids

    if (existingIds.nonEmpty) {
      log.info(s"[EC2] Terminating ${existingIds.size} instances.")
      ec2.terminateInstances(new TerminateInstancesRequest(existingIds.toSeq.asJava))
      retry { i =>
        log.info(s"[EC2] Checking status. Attempts: $i.")
        val nonTerminatedTargetInstances = getNonTerminatedInstances.map(_.getInstanceId).toSet & existingIds
        assert(nonTerminatedTargetInstances.isEmpty, "[EC2] Some instances are not terminated: " + nonTerminatedTargetInstances.mkString(","))
      }
      log.info("[EC2] All instances are terminated.")
    }
  }

  def getMachines() = {
    getNonTerminatedInstances.map { i =>
      Machine(
        i.getInstanceId,
        i.getTags().asScala.find(_.getKey == "Name").map(_.getValue).getOrElse(""),
        if (clusterConf.usePrivateIp) i.getPrivateIpAddress else i.getPublicDnsName
      )
    }
  }
}
