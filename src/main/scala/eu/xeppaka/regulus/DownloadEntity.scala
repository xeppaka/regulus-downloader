package eu.xeppaka.regulus

import java.nio.file.Path

import akka.http.scaladsl.model.Uri

sealed trait DownloadEntity {
  def uriPath: Uri.Path
}

case class DownloadFile(uriPath: Uri.Path, toPath: Path) extends DownloadEntity
case class DownloadDir(uriPath: Uri.Path) extends DownloadEntity