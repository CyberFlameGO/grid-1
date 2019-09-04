package model

import java.io.File

import com.gu.mediaservice.model.UploadInfo
import net.logstash.logback.marker.{LogstashMarker, Markers}
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}

import scala.collection.JavaConverters._

case class UploadRequest(
  id: String,
  tempFile: File,
  mimeType: Option[String],
  uploadTime: DateTime,
  uploadedBy: String,
  identifiers: Map[String, String],
  uploadInfo: UploadInfo
) {
  val identifiersMeta: Map[String, String] = identifiers.map { case (k, v) => (s"identifier!$k", v) }

  def toLogMarker: LogstashMarker = {
    val fallback = "none"

    val markers = Map (
      "id" -> id,
      "mimeType" -> mimeType.getOrElse(fallback),
      "uploadTime" -> ISODateTimeFormat.dateTime.print(uploadTime.withZone(DateTimeZone.UTC)),
      "uploadedBy" -> uploadedBy,
      "filename" -> uploadInfo.filename.getOrElse(fallback)
    ) ++ identifiersMeta

    Markers.appendEntries(markers.asJava)
  }
}
