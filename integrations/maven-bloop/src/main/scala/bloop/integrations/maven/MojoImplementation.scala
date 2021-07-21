package bloop.integrations.maven

import java.io.File
import java.nio.file.{Files, Path, Paths}
import bloop.config.{Config, Tag}
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Resource
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.{MavenPluginManager, Mojo, MojoExecution}
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import scala_maven.AppLauncher
import scala.collection.JavaConverters._
import org.apache.maven.artifact.Artifact
import org.apache.maven.shared.invoker.DefaultInvocationRequest
import java.{util => ju}
import org.apache.maven.shared.invoker.DefaultInvoker
import org.apache.maven.shared.invoker.InvocationResult

import scala.util.{Failure, Success, Try}

object MojoImplementation {
  private val ScalaMavenGroupArtifact = "net.alchim31.maven:scala-maven-plugin"
  private val JavaMavenGroupArtifact = "org.apache.maven.plugins:maven-compiler-plugin"

  def initializeMojo(
      project: MavenProject,
      session: MavenSession,
      mojoExecution: MojoExecution,
      mavenPluginManager: MavenPluginManager,
      encoding: String
  ): Either[String, BloopMojo] = {
    val buildPlugins = project.getBuild().getPluginsAsMap();

    val (newConfig, moduleType) = Option(buildPlugins.get(ScalaMavenGroupArtifact)) match {
      case None => (None, BloopMojo.ModuleType.JAVA)
      case Some(scalaMavenPlugin) =>
        (Some(scalaMavenPlugin.getConfiguration.asInstanceOf[Xpp3Dom]), BloopMojo.ModuleType.SCALA)
    }

    val javaCompilerArgs: List[String] = Option(buildPlugins.get(JavaMavenGroupArtifact)) match {
      case None => List()
      case Some(javaMavenPlugin) =>
        val javaConfig = javaMavenPlugin.getConfiguration.asInstanceOf[Xpp3Dom]
        if (javaConfig != null) {
          val compilerArgs = Option(javaConfig.getChild("compilerArgs"))
          compilerArgs.map(_.getChildren.map(_.getValue).toList).getOrElse(Nil)
        } else List()
    }

    val currentConfig = mojoExecution.getConfiguration
    val dom = newConfig.map(nc => Xpp3Dom.mergeXpp3Dom(currentConfig, nc))
    Try {
      dom.foreach(mojoExecution.setConfiguration)
      val mojo = mavenPluginManager
        .getConfiguredMojo(classOf[Mojo], session, mojoExecution)
        .asInstanceOf[BloopMojo]
      mojo.setModuleType(moduleType)
      mojo.setJavaCompilerArgs(javaCompilerArgs.asJava)
      mojo
    } match {
      case Success(value) => Right(value)
      case Failure(e) => Left(s"Failed to init BloopMojo with conf:\n$dom\n${e.getMessage}")
    }
  }

  private val emptyLauncher = new AppLauncher("", "", Array(), Array())

  def writeCompileAndTestConfiguration(mojo: BloopMojo, session: MavenSession, log: Log): Unit = {
    import scala.collection.JavaConverters._
    def abs(file: File): Path = {
      file.mkdirs()
      file.toPath().toRealPath().toAbsolutePath()
    }

    val root = new File(session.getExecutionRootDirectory())
    val project = mojo.getProject()
    val dependencies =
      session.getProjectDependencyGraph.getUpstreamProjects(project, true).asScala.toList
    val dependencyNames = dependencies.map(_.getArtifactId()).toList

    val configDir = mojo.getBloopConfigDir.toPath()
    if (!Files.exists(configDir)) Files.createDirectory(configDir)

    val launcherId = mojo.getLauncher()
    val launchers = mojo.getLaunchers()
    val launcher = launchers
      .find(_.getId == launcherId)
      .getOrElse {
        if (launchers.isEmpty)
          log.info(s"Using empty launcher: no run setup for ${project.getName}.")
        else if (launcherId.nonEmpty)
          log.warn(s"Using empty launcher: Launcher ID '${launcherId}' does not exist")
        emptyLauncher
      }

    val scalaContext = mojo.findScalaContext()
    val compileSetup = mojo.getCompileSetup()
    val compilerAndDeps = scalaContext.findCompilerAndDependencies().asScala
    val allScalaJars = compilerAndDeps.map { artifact =>
      artifact.getFile().toPath()
    }.toList

    val scalaOrganization = compilerAndDeps
      .collectFirst {
        case artifact
            if artifact.getArtifactId() == "scala3-compiler_3" || artifact
              .getArtifactId() == "scala-compiler" =>
          artifact.getGroupId()
      }
      .getOrElse("org.scala-lang")
    val scalacArgs = mojo.getScalacArgs().asScala.toList.filter(_ != null)

    def writeConfig(
        sourceDirs0: Seq[File],
        classesDir0: File,
        classpath0: java.util.List[_],
        resources0: java.util.List[_],
        launcher: AppLauncher,
        configuration: String
    ): Unit = {
      val suffix = if (configuration == "compile") "" else s"-$configuration"
      val name = project.getArtifactId() + suffix
      val build = project.getBuild()
      val baseDirectory = abs(project.getBasedir())
      val out = baseDirectory.resolve("target")
      val analysisOut = None
      val sourceDirs = sourceDirs0.map(abs).toList
      val classesDir = abs(classesDir0)

      val resolution = if (mojo.shouldDownloadSources()) {
        // Run source dependencies download
        val request = new DefaultInvocationRequest()
        request.setPomFile(project.getBasedir)
        request.setBatchMode(true)
        request.setGoals(ju.Collections.singletonList("dependency:sources"))
        val invoker = new DefaultInvoker()
        val result: InvocationResult = invoker.execute(request)

        val modules =
          project.getArtifacts().asScala.collect {
            case art: Artifact if art.getType() == "jar" =>
              artifactToConfigModule(art, project, session)
          }
        Some(Config.Resolution(modules.toList))
      } else {
        None
      }
      val classpath = {
        val projectDependencies = dependencies.flatMap { d =>
          val build = d.getBuild()
          if (configuration == "compile") build.getOutputDirectory() :: Nil
          else build.getTestOutputDirectory() :: build.getOutputDirectory() :: Nil
        }

        val cp = classpath0.asScala.toList.asInstanceOf[List[String]].map(u => abs(new File(u)))
        (projectDependencies.map(u => abs(new File(u))) ++ cp).toList
      }

      val tags = if (configuration == "test") List(Tag.Test) else List(Tag.Library)

      // add main dependency to test project
      val fullDependencies =
        if (configuration == "test") project.getArtifactId :: dependencyNames else dependencyNames

      // FORMAT: OFF
      val config = {
        val sbt = None
        val test = Some(Config.Test.defaultConfiguration)
        val java = Some(Config.Java(mojo.getJavacArgs().asScala.toList))
        val `scala` = Some(Config.Scala(scalaOrganization, mojo.getScalaArtifactID(), scalaContext.version().toString(), scalacArgs, allScalaJars, analysisOut, Some(compileSetup)))
        val javaHome = Some(abs(mojo.getJavaHome().getParentFile.getParentFile))
        val mainClass = if (launcher.getMainClass().isEmpty) None else Some(launcher.getMainClass())
        val platform = Some(Config.Platform.Jvm(Config.JvmConfig(javaHome, launcher.getJvmArgs().toList), mainClass, None, None, None))
        val resources = Some(resources0.asScala.toList.flatMap{
          case a: Resource => Option(Paths.get(a.getDirectory()))
          case _ => None
        })
        val project = Config.Project(name, baseDirectory, Some(root.toPath), sourceDirs, None, None, fullDependencies, classpath, out, classesDir, resources, `scala`, java, sbt, test, platform, resolution, Some(tags))
        Config.File(Config.File.LatestVersion, project)
      }
      // FORMAT: ON

      val configTarget = new File(mojo.getBloopConfigDir, s"$name.json")
      val finalTarget = relativize(root, configTarget).getOrElse(configTarget.getAbsolutePath)
      log.info(s"Generated $finalTarget")
      log.debug(s"Configuration to be serialized:\n$config")
      bloop.config.write(config, configTarget.toPath)
    }

    writeConfig(
      mojo.getCompileSourceDirectories.asScala.toSeq,
      mojo.getCompileOutputDir,
      project.getCompileClasspathElements,
      project.getResources,
      launcher,
      "compile"
    )

    writeConfig(
      mojo.getTestSourceDirectories.asScala.toSeq,
      mojo.getTestOutputDir,
      project.getTestClasspathElements,
      project.getTestResources,
      launcher,
      "test"
    )
  }

  private def relativize(base: File, file: File): Option[String] = {
    import scala.util.control.Exception.catching
    val basePath = (if (base.isAbsolute) base else base.getCanonicalFile).toPath
    val filePath = (if (file.isAbsolute) file else file.getCanonicalFile).toPath
    if ((filePath startsWith basePath) || (filePath.normalize() startsWith basePath.normalize())) {
      val relativePath =
        catching(classOf[IllegalArgumentException]) opt (basePath relativize filePath)
      relativePath map (_.toString)
    } else None
  }

  private def artifactToConfigModule(
      artifact: Artifact,
      project: MavenProject,
      session: MavenSession
  ): Config.Module = {
    val base = session.getLocalRepository().getBasedir()
    val artifactPath = session.getLocalRepository().pathOf(artifact)
    val sources = artifactPath.replace(".jar", "-sources.jar")
    val sourcesPath = Paths.get(base).resolve(sources)
    val sourcesList = if (sourcesPath.toFile().exists()) {
      List(
        Config.Artifact(
          name = artifact.getArtifactId(),
          classifier = Option("sources"),
          checksum = None,
          path = sourcesPath
        )
      )
    } else {
      Nil
    }
    Config.Module(
      organization = artifact.getGroupId(),
      name = artifact.getArtifactId(),
      version = artifact.getVersion(),
      configurations = None,
      Config.Artifact(
        name = artifact.getArtifactId(),
        classifier = Option(artifact.getClassifier()),
        checksum = None,
        path = artifact.getFile().toPath()
      ) :: sourcesList
    )
  }
}
