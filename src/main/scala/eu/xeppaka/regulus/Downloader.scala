package eu.xeppaka.regulus

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.Cookie
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.scaladsl.{FileIO, Flow, Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy, ThrottleMode}
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.xml.XML

object Downloader {
  val uriBase = Uri("http://regulus.route.tecomat.com:61682")
  val qsize = new AtomicInteger(0)

  def main(args: Array[String]): Unit = {
    val routePlc = args(0)
    val softPlc = args(1)
    val uriParam = Uri.Path(args(2))
    val uriBasePath = if (uriParam.endsWithSlash) uriParam else uriParam ++ Uri.Path./
    val cookies = Cookie(("RoutePLC", routePlc), ("SoftPLC", softPlc))
    val outputPathBase: Path = Paths.get(args(3))

    implicit val actorSystem: ActorSystem = ActorSystem()
    implicit val materializer: Materializer = ActorMaterializer()
    val http = Http()

    val (input, output) = Source
      .queue[DownloadEntity](10000, OverflowStrategy.fail)
      .toMat(Sink.queue())(Keep.both)
      .run()

    qsize.incrementAndGet()
    input.offer(DownloadDir(uriBasePath))
    processQueues(outputPathBase, cookies, input, output, http)(materializer, actorSystem.dispatcher)

    input
      .watchCompletion()
      .andThen { case _ =>
        println("finished")
        http.shutdownAllConnectionPools().andThen { case _ => actorSystem.terminate() }(actorSystem.dispatcher)
      }(actorSystem.dispatcher)
  }

  def processQueues(
    outputPathBase: Path,
    cookies: Cookie,
    input: SourceQueueWithComplete[DownloadEntity],
    output: SinkQueueWithCancel[DownloadEntity],
    http: HttpExt
  )(implicit materializer: Materializer, executionContext: ExecutionContext): Unit = {
    output
      .pull()
      .onComplete {
        case Success(Some(DownloadDir(uriPath))) =>
          print(s"Downloading dir: $uriPath... ")
          qsize.decrementAndGet()

          val request = HttpRequest(uri = uriBase.withPath(uriPath), headers = immutable.Seq(cookies))
          http
            .singleRequest(request)
            .flatMap(response => response.entity.toStrict(10.seconds))
            .andThen {
              case Success(entity) =>
                println("success")
                processXml(outputPathBase, uriPath, entity.data.utf8String)
                  .foreach { entity =>
                    qsize.incrementAndGet()
                    input.offer(entity)
                  }

              case Failure(exception) =>
                println("failure")
                println(exception)
            }
            .andThen { case _ =>
              if (qsize.get() == 0) {
                input.complete()
              }

              processQueues(outputPathBase, cookies, input, output, http)
            }

        case Success(Some(DownloadFile(uriPath, toPath))) =>
          print(s"Downloading file: $uriPath... ")
          qsize.decrementAndGet()

          val request = HttpRequest(uri = uriBase.withPath(uriPath), headers = immutable.Seq(cookies))
          http
            .singleRequest(request)
            .flatMap { response =>
              val contentLength = response.entity.contentLengthOption
              val downloaded = new AtomicInteger()

              response
                .entity
                .dataBytes
                .alsoToMat(Flow[ByteString]
                  .map(bytes => downloaded.addAndGet(bytes.length))
                  .throttle(1, 1.second, 1, ThrottleMode.shaping)
                  .buffer(1, OverflowStrategy.dropHead)
                  .alsoToMat(Sink.last)(Keep.right)
                  .to(
                    Sink.foreach { downloadedSize =>
                      contentLength.foreach(cl => print(s"${(downloadedSize / cl.toDouble * 100).toInt}% "))
                    }
                  )
                )(Keep.right)
                .toMat(FileIO.toPath(toPath))(Keep.left)
                .run()
                .andThen {
                  case Success(lastPrintedSize) => contentLength.foreach { cl =>
                    val percents = (lastPrintedSize / cl.toDouble * 100).toInt
                    if (percents < 100) println("100% ")
                  }
                  case Failure(_) =>
                }
            }
            .andThen {
              case Success(_) =>
                println("success")
              case Failure(exception) =>
                println("failure")
                println(exception)
            }
            .andThen { case _ =>
              if (qsize.get() == 0) {
                input.complete()
              }

              processQueues(outputPathBase, cookies, input, output, http)
            }
        case Success(None) =>
        case Failure(exception) => println(exception)
      }
  }

  def processXml(outputPathBase: Path, atUriPath: Uri.Path, xmlString: String): Seq[DownloadEntity] = {
    val xml = XML.loadString(xmlString)

    val path = xml \ "PATH" \@ "NAME"
    val fullOutputPath = outputPathBase.resolve(path)
    Files.createDirectories(fullOutputPath)

    val dirs = (xml \ "DIR")
      .map(_ \@ "NAME")
      //.filter(dirName => dirName != "11")
      .map(dirName => (atUriPath ?/ dirName) ++ Uri.Path./)
      .map(DownloadDir.apply)

    val files = (xml \ "FILE")
      .map(_ \@ "NAME")
      .map(fileName => DownloadFile(atUriPath ?/ fileName, fullOutputPath.resolve(fileName)))

    //    println(s"parsed dirs: $dirs")
    //    println(s"parsed files: $files")

    files ++ dirs
  }
}
