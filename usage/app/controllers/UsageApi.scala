package controllers

import java.net.URI
import com.gu.contentapi.client.model.ItemQuery
import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.argo.model.{EntityResponse, Link, Action => ArgoAction}
import com.gu.mediaservice.lib.auth.{Authentication, Authorisation}
import com.gu.mediaservice.lib.aws.UpdateMessage
import com.gu.mediaservice.lib.usage.UsageBuilder
import com.gu.mediaservice.model.usage.{MediaUsage, Usage}
import com.gu.mediaservice.syntax.MessageSubjects
import lib._
import model._
import play.api.libs.json.{JsError, JsValue}
import play.api.mvc._
import play.utils.UriEncoding
import rx.lang.scala.Subject

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class UsageApi(
  auth: Authentication,
  authorisation: Authorisation,
  usageTable: UsageTable,
  usageGroupOps: UsageGroupOps,
  notifications: Notifications,
  config: UsageConfig,
  usageApiSubject: Subject[UsageGroup],
  liveContentApi: LiveContentApi,
  override val controllerComponents: ControllerComponents,
  playBodyParsers: PlayBodyParsers
)(
  implicit val ec: ExecutionContext
) extends BaseController with MessageSubjects with ArgoHelpers {

  private val AuthenticatedAndAuthorisedToDelete = auth andThen authorisation.CommonActionFilters.authorisedForDeleteCropsOrUsages

  private def wrapUsage(usage: Usage): EntityResponse[Usage] = {
    EntityResponse(
      uri = usageUri(usage.id),
      data = usage
    )
  }

  private def usageUri(usageId: String): Option[URI] = {
    val encodedUsageId = UriEncoding.encodePathSegment(usageId, "UTF-8")
    Try { URI.create(s"${config.usageUri}/usages/$encodedUsageId") }.toOption
  }

  val indexResponse = {
    val indexData = Map("description" -> "This is the Usage Recording service")
    val indexLinks = List(
      Link("usages-by-media", s"${config.usageUri}/usages/media/{id}"),
      Link("usages-by-id", s"${config.usageUri}/usages/{id}")
    )

    val printPostUri = URI.create(s"${config.usageUri}/usages/print")
    val actions = List(
      ArgoAction("print-usage", printPostUri, "POST")
    )

    respond(indexData, indexLinks, actions)
  }
  def index = auth { indexResponse }

  def forUsage(usageId: String) = auth.async {
    logger.info(s"Request for single usage $usageId")
    val usageFuture = usageTable.queryByUsageId(usageId)

    usageFuture.map[play.api.mvc.Result]((mediaUsageOption: Option[MediaUsage]) => {
      mediaUsageOption.foldLeft(
        respondNotFound("No usages found.")
      )((_, mediaUsage: MediaUsage) => {
        val usage = UsageBuilder.build(mediaUsage)
        val mediaId = mediaUsage.mediaId

        val uri = usageUri(usage.id)
        val links = List(
          Link("media", s"${config.services.apiBaseUri}/images/$mediaId"),
          Link("media-usage", s"${config.services.usageBaseUri}/usages/media/$mediaId")
        )

        respond[Usage](data = usage, uri = uri, links = links)
      })
    }).recover { case error: Exception =>
      logger.error("UsageApi returned an error.", error)
      respondError(InternalServerError, "usage-retrieve-failed", error.getMessage)
    }

  }

  def reindexForContent(contentId: String) = auth.async {
    val query = ItemQuery(contentId)
      .showFields("all")
      .showElements("all")

    liveContentApi.getResponse(query).map{response =>
      response.content match {
        case Some(content) =>
          liveContentApi
            .getContentFirstPublished(content)
            .map(LiveContentItem(content, _))
            .map(_.copy(isReindex = true))
            .foreach(_.emitAsUsageGroup(
              usageApiSubject,
              usageGroupOps
            ))
          Accepted
        case _ =>
          NotFound
      }
    }.recover { case error: Exception =>
        logger.error(s"UsageApi reindex for for content ($contentId) failed!", error)
        InternalServerError
      }
  }

  def forMedia(mediaId: String) = auth.async {
    val usagesFuture = usageTable.queryByImageId(mediaId)

    usagesFuture.map[play.api.mvc.Result]((mediaUsages: List[MediaUsage]) => {
      val usages = mediaUsages.map(UsageBuilder.build)

      usages match {
        case Nil => respondNotFound("No usages found.")
        case usage :: _ =>
          val uri = Try { URI.create(s"${config.services.usageBaseUri}/usages/media/$mediaId") }.toOption
          val links = List(
            Link("media", s"${config.services.apiBaseUri}/images/$mediaId")
          )

          respondCollection[EntityResponse[Usage]](
            uri = uri,
            links = links,
            data = usages.map(wrapUsage)
          )
      }
    }).recover {
      case error: BadInputException =>
        logger.error("UsageApi returned an error.", error)
        respondError(BadRequest, "image-usage-retrieve-failed", error.getMessage)
      case error: Exception =>
        logger.error("UsageApi returned an error.", error)
        respondError(InternalServerError, "image-usage-retrieve-failed", error.getMessage)
    }
  }

  val maxPrintRequestLength: Int = 1024 * config.maxPrintRequestLengthInKb
  val setPrintRequestBodyParser: BodyParser[JsValue] = playBodyParsers.json(maxLength = maxPrintRequestLength)

  def setPrintUsages = auth(setPrintRequestBodyParser) { request => {
      val printUsageRequestResult = request.body.validate[PrintUsageRequest]
      printUsageRequestResult.fold(
        e => {
          respondError(BadRequest, "print-usage-request-parse-failed", JsError.toJson(e).toString)
        },
        printUsageRequest => {
          val usageGroups = usageGroupOps.build(printUsageRequest.printUsageRecords)
          usageGroups.foreach(usageApiSubject.onNext)

          Accepted
        }
      )
    }
  }

  def setSyndicationUsages() = auth(parse.json) { req => {
    val syndicationUsageRequest = (req.body \ "data").validate[SyndicationUsageRequest]
    syndicationUsageRequest.fold(
      e => respondError(
        BadRequest,
        errorKey = "syndication-usage-parse-failed",
        errorMessage = JsError.toJson(e).toString
      ),
      sur => {
        logger.info(req.user.accessor, ImageId(sur.mediaId), "recording syndication usage")
        val group = usageGroupOps.build(sur)
        usageApiSubject.onNext(group)
        Accepted
      }
    )
  }}

  def setFrontUsages() = auth(parse.json) { req => {
    val request = (req.body \ "data").validate[FrontUsageRequest]
    request.fold(
      e => respondError(
        BadRequest,
        errorKey = "front-usage-parse-failed",
        errorMessage = JsError.toJson(e).toString
      ),
      fur => {
        logger.info(req.user.accessor, ImageId(fur.mediaId), "recording front usage")
        val group = usageGroupOps.build(fur)
        usageApiSubject.onNext(group)
        Accepted
      }
    )
  }}

  def setDownloadUsages() = auth(parse.json) { req => {
    val request = (req.body \ "data").validate[DownloadUsageRequest]
    request.fold(
      e => respondError(
        BadRequest,
        errorKey = "download-usage-parse-failed",
        errorMessage = JsError.toJson(e).toString
      ),
      usageRequest => {
        logger.info(req.user.accessor, ImageId(usageRequest.mediaId), "recording download usage")
        val group = usageGroupOps.build(usageRequest)
        usageApiSubject.onNext(group)
        Accepted
      }
    )
  }}

  def deleteUsages(mediaId: String) = AuthenticatedAndAuthorisedToDelete.async {
    usageTable.queryByImageId(mediaId).map(usages => {
      usages.foreach(usageTable.deleteRecord)
    }).recover{
      case error: BadInputException =>
        logger.warn("UsageApi returned an error.", error)
        respondError(BadRequest, "image-usage-delete-failed", error.getMessage)
      case error: Exception =>
        logger.error("UsageApi returned an error.", error)
        respondError(InternalServerError, "image-usage-delete-failed", error.getMessage)
    }

    val updateMessage = UpdateMessage(subject = DeleteUsages, id = Some(mediaId))
    notifications.publish(updateMessage)
    Future.successful(Ok)
  }
}
