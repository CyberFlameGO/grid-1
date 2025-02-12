import com.gu.mediaservice.lib.management.InnerServiceStatusCheckController
import com.gu.mediaservice.lib.play.GridComponents
import controllers.UsageApi
import lib._
import model._
import play.api.ApplicationLoader.Context
import router.Routes

import scala.concurrent.Future

class UsageComponents(context: Context) extends GridComponents(context, new UsageConfig(_)) {

  final override val buildInfo = utils.buildinfo.BuildInfo

  val usageMetadataBuilder = new UsageMetadataBuilder(config)
  val mediaWrapper = new MediaWrapperOps(usageMetadataBuilder)
  val liveContentApi = new LiveContentApi(config)
  val usageGroupOps = new UsageGroupOps(config, liveContentApi, mediaWrapper)
  val usageTable = new UsageTable(config)
  val usageMetrics = new UsageMetrics(config)
  val usageNotifier = new UsageNotifier(config, usageTable)
  val usageRecorder = new UsageRecorder(usageMetrics, usageTable, usageNotifier, usageNotifier)
  val notifications = new Notifications(config)

  if(!config.apiOnly) {
    val crierReader = new CrierStreamReader(config, usageGroupOps, executionContext)
    crierReader.start()
  }

  usageRecorder.start()
  context.lifecycle.addStopHook(() => {
    usageRecorder.stop()
    Future.successful(())
  })

  val controller = new UsageApi(auth, authorisation, usageTable, usageGroupOps, notifications, config, usageRecorder.usageApiSubject, liveContentApi, controllerComponents, playBodyParsers)
  val InnerServiceStatusCheckController = new InnerServiceStatusCheckController(auth, controllerComponents, config.services, wsClient)


  override lazy val router = new Routes(httpErrorHandler, controller, management, InnerServiceStatusCheckController)
}
