/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s.submit

import java.io.StringWriter
import java.util
import java.util.{Collections, Properties, UUID}

import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.KubernetesClient
import org.apache.spark.SparkConf
import org.apache.spark.deploy.SparkApplication
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.{ExtendConfig, KubernetesConf, KubernetesDriverSpecificConf, SparkKubernetesClientFactory}
import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils
import org.apache.spark.deploy.k8s.ExtendConfig._

import scala.collection.mutable
import scala.util.control.NonFatal

/**
  *
  * Encapsulates arguments to the submission client.
  *
  * @param mainAppResource the main application resource if any
  * @param mainClass       the main class of the application to run
  * @param driverArgs      arguments to the driver
  * @param maybePyFiles    additional Python files via --py-files
  */
private[spark] case class ClientArguments(
                                           mainAppResource: Option[MainAppResource],
                                           mainClass: String,
                                           driverArgs: Array[String],
                                           maybePyFiles: Option[String])

private[spark] object ClientArguments {

  def fromCommandLineArgs(args: Array[String]): ClientArguments = {
    var mainAppResource: Option[MainAppResource] = None
    var mainClass: Option[String] = None
    val driverArgs = mutable.ArrayBuffer.empty[String]
    var maybePyFiles: Option[String] = None

    args.sliding(2, 2).toList.foreach {
      case Array("--primary-java-resource", primaryJavaResource: String) =>
        mainAppResource = Some(JavaMainAppResource(primaryJavaResource))
      case Array("--primary-py-file", primaryPythonResource: String) =>
        mainAppResource = Some(PythonMainAppResource(primaryPythonResource))
      case Array("--primary-r-file", primaryRFile: String) =>
        mainAppResource = Some(RMainAppResource(primaryRFile))
      case Array("--other-py-files", pyFiles: String) =>
        maybePyFiles = Some(pyFiles)
      case Array("--main-class", clazz: String) =>
        mainClass = Some(clazz)
      case Array("--arg", arg: String) =>
        driverArgs += arg
      case other =>
        val invalid = other.mkString(" ")
        throw new RuntimeException(s"Unknown arguments: $invalid")
    }

    require(mainClass.isDefined, "Main class must be specified via --main-class")

    ClientArguments(
      mainAppResource,
      mainClass.get,
      driverArgs.toArray,
      maybePyFiles)
  }
}

/**
  * Submits a Spark application to run on Kubernetes by creating the driver pod and starting a
  * watcher that monitors and logs the application status. Waits for the application to terminate if
  * spark.kubernetes.submission.waitAppCompletion is true.
  *
  * @param builder              Responsible for building the base driver pod based on a composition of
  *                             implemented features.
  * @param kubernetesConf       application configuration
  * @param kubernetesClient     the client to talk to the Kubernetes API server
  * @param waitForAppCompletion a flag indicating whether the client should wait for the application
  *                             to complete
  * @param appName              the application name
  * @param watcher              a watcher that monitors and logs the application status
  */
private[spark] class Client(
                             builder: KubernetesDriverBuilder,
                             kubernetesConf: KubernetesConf[KubernetesDriverSpecificConf],
                             kubernetesClient: KubernetesClient,
                             waitForAppCompletion: Boolean,
                             appName: String,
                             watcher: LoggingPodStatusWatcher,
                             kubernetesResourceNamePrefix: String) extends Logging {

  def run(): Unit = {
    val resolvedDriverSpec = builder.buildFromFeatures(kubernetesConf)
    val configMapName = s"$kubernetesResourceNamePrefix-driver-conf-map"
    val configMap = buildConfigMap(configMapName, resolvedDriverSpec.systemProperties)
    val hadoopUserName = resolvedDriverSpec.systemProperties.get(ExtendConfig.HADOOP_USER_NAME_KEY).get
    val sftpConfig = builderSftpConfig(resolvedDriverSpec.systemProperties)

    // The include of the ENV_VAR for "SPARK_CONF_DIR" is to allow for the
    // Spark command builder to pickup on the Java Options present in the ConfigMap
    val resolvedDriverContainer = new ContainerBuilder(resolvedDriverSpec.pod.container)
      .addNewEnv()
      .withName(ENV_SPARK_CONF_DIR)
      .withValue(SPARK_CONF_DIR_INTERNAL)
      .endEnv()
      .addNewEnv()
      .withName(ExtendConfig.HADOOP_USER_NAME_KEY)
      .withValue(hadoopUserName)
      .endEnv()
      .addAllToEnv(sftpConfig)
      .addNewVolumeMount()
      .withName(SPARK_CONF_VOLUME)
      .withMountPath(SPARK_CONF_DIR_INTERNAL)
      .endVolumeMount()
      .build()
    val resolvedDriverPod = new PodBuilder(resolvedDriverSpec.pod.pod)
        .editMetadata()
        .endMetadata()
      .editSpec()
      .addToContainers(resolvedDriverContainer)
      .addNewVolume()
      .withName(SPARK_CONF_VOLUME)
      .withNewConfigMap()
      .withName(configMapName)
      .endConfigMap()
      .endVolume()
      .endSpec()
      .build()
    Utils.tryWithResource(
      kubernetesClient
        .pods()
        .withName(resolvedDriverPod.getMetadata.getName)
        .watch(watcher)) { _ =>
      val createdDriverPod = kubernetesClient.pods().create(resolvedDriverPod)
      try {
        val otherKubernetesResources =
          resolvedDriverSpec.driverKubernetesResources ++ Seq(configMap)
        addDriverOwnerReference(createdDriverPod, otherKubernetesResources)
        kubernetesClient.resourceList(otherKubernetesResources: _*).createOrReplace()
      } catch {
        case NonFatal(e) =>
          kubernetesClient.pods().delete(createdDriverPod)
          throw e
      }

      if (waitForAppCompletion) {
        logInfo(s"Waiting for application $appName to finish...")
        watcher.awaitCompletion()
        logInfo(s"Application $appName finished.")
      } else {
        logInfo(s"Deployed Spark application $appName into Kubernetes.")
      }

    }
  }

  private def builderSftpConfig(conf: Map[String, String]): util.ArrayList[EnvVar] = {
    val sftpConf = new util.ArrayList[EnvVar]()
    conf.filterKeys(key => key.contains("sftp"))
      .foreach { case (k, v) => sftpConf.add(new EnvVar(k, v, null)) }
    sftpConf
  }

  // Add a OwnerReference to the given resources making the driver pod an owner of them so when
  // the driver pod is deleted, the resources are garbage collected.
  private def addDriverOwnerReference(driverPod: Pod, resources: Seq[HasMetadata]): Unit = {
    val driverPodOwnerReference = new OwnerReferenceBuilder()
      .withName(driverPod.getMetadata.getName)
      .withApiVersion(driverPod.getApiVersion)
      .withUid(driverPod.getMetadata.getUid)
      .withKind(driverPod.getKind)
      .withController(true)
      .build()
    resources.foreach { resource =>
      val originalMetadata = resource.getMetadata
      originalMetadata.setOwnerReferences(Collections.singletonList(driverPodOwnerReference))
    }
  }

  /**
    *  绑定hadoop相关配置，最终挂载到 /opt/spark/conf文件夹下
    * @param configMapName
    * @param conf
    * @return
    */
  private def buildConfigMap(configMapName: String, conf: Map[String, String]): ConfigMap = {
    val properties = new Properties()
    conf.filterKeys(key => !key.contains(".xml"))
      .foreach { case (k, v) => properties.setProperty(k, v)
      }
    val propertiesWriter = new StringWriter()
    properties.store(propertiesWriter,
      s"Java properties built from Kubernetes config map with name: $configMapName")

    val data = new util.HashMap[String, String]()
    conf.filterKeys(key => key.contains(".xml"))
      .foreach { case (k, v) => data.put(k, v)
      }
    data.put(SPARK_CONF_FILE_NAME, propertiesWriter.toString)

    new ConfigMapBuilder()
      .withNewMetadata()
      .withName(configMapName)
      .endMetadata()
      .addToData(data)
      .build()
  }
}

/**
  * Main class and entry point of application submission in KUBERNETES mode.
  * KUBERNETES
  *
  */
private[spark] class ChildKubernetesClientApplication extends SparkApplication {

  override def start(args: Array[String], conf: SparkConf): Unit = {
    val parsedArguments = ClientArguments.fromCommandLineArgs(args)
    run(parsedArguments, conf)
  }

   def run(clientArguments: ClientArguments, sparkConf: SparkConf): String = {
    val appName = sparkConf.getOption("spark.app.name").getOrElse("spark")
    // For constructing the app ID, we can't use the Spark application name, as the app ID is going
    // to be added as a label to group resources belonging to the same application. Label values are
    // considerably restrictive, e.g. must be no longer than 63 characters in length. So we generate
    // a unique app ID (captured by spark.app.id) in the format below.
    val kubernetesAppId = s"spark-${UUID.randomUUID().toString.replaceAll("-", "")}"
    val waitForAppCompletion = sparkConf.get(WAIT_FOR_APP_COMPLETION)
    val kubernetesResourceNamePrefix = ChildKubernetesClientApplication.getResourceNamePrefix(appName)
    // pyFile
    sparkConf.set(KUBERNETES_PYSPARK_PY_FILES, clientArguments.maybePyFiles.getOrElse(""))
    val kubernetesConf = KubernetesConf.createDriverConf(
      sparkConf,
      appName,
      kubernetesResourceNamePrefix,
      kubernetesAppId,
      clientArguments.mainAppResource,
      clientArguments.mainClass,
      clientArguments.driverArgs,
      clientArguments.maybePyFiles)

    val builder = new KubernetesDriverBuilder
    val loggingInterval = if (waitForAppCompletion) Some(sparkConf.get(REPORT_INTERVAL)) else None

    val watcher = new LoggingPodStatusWatcherImpl(kubernetesAppId, loggingInterval)

     Utils.tryWithResource(SparkKubernetesClientFactory.createKubernetesClient(sparkConf, kubernetesConf)) { kubernetesClient =>
      val client = new Client(
        builder,
        kubernetesConf,
        kubernetesClient,
        waitForAppCompletion,
        appName,
        watcher,
        kubernetesResourceNamePrefix)

      client.run()
      // 返回kubernetesAppId
      kubernetesAppId
    }
  }
}

private[spark] object ChildKubernetesClientApplication {

  def getAppName(conf: SparkConf): String = conf.getOption("spark.app.name").getOrElse("spark")

  def getResourceNamePrefix(appName: String): String = {
    val launchTime = System.currentTimeMillis()
    s"$appName-$launchTime"
      .trim
      .toLowerCase
      .replaceAll("\\s+", "-")
      .replaceAll("\\.", "-")
      .replaceAll("[^a-z0-9\\-]", "")
      .replaceAll("-+", "-")
  }
}
