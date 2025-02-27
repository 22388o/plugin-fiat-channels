package fr.acinq.fc.app

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import fr.acinq.bitcoin.{ByteVector32, Satoshi, Script}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel.{OFFLINE, Origin}
import fr.acinq.eclair.payment.IncomingPaymentPacket
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner.IncomingHtlc
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.wire.internal.channel.version3.FiatChannelCodecs
import fr.acinq.eclair.wire.protocol.{FailureMessage, UpdateAddHtlc}
import fr.acinq.fc.app.channel._
import fr.acinq.fc.app.db.{Blocking, HostedChannelsDb, HostedUpdatesDb, PreimagesDb}
import fr.acinq.fc.app.FC._
import fr.acinq.fc.app.network.{HostedSync, OperationalData, PHC, PreimageBroadcastCatcher}
import fr.acinq.fc.app.rate.{BinanceSourceModified, CentralBankOracle, EcbSource, RateOracle}
import scodec.bits.ByteVector

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.stm._
import scala.util.Try


object FC {
  final val HC_INVOKE_HOSTED_CHANNEL_TAG = 55535

  final val HC_INIT_HOSTED_CHANNEL_TAG = 55533

  final val HC_LAST_CROSS_SIGNED_STATE_TAG = 55531

  final val HC_STATE_UPDATE_TAG = 55529

  final val HC_STATE_OVERRIDE_TAG = 55527

  final val HC_HOSTED_CHANNEL_BRANDING_TAG = 55525

  final val HC_ANNOUNCEMENT_SIGNATURE_TAG = 55523

  final val HC_RESIZE_CHANNEL_TAG = 55521

  final val HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG = 55519

  final val HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG = 55517

  final val HC_QUERY_PREIMAGES_TAG = 55515

  final val HC_REPLY_PREIMAGES_TAG = 55513

  final val HC_ASK_BRANDING_INFO = 55511

  final val PHC_ANNOUNCE_GOSSIP_TAG = 54513

  final val PHC_ANNOUNCE_SYNC_TAG = 54511

  final val PHC_UPDATE_GOSSIP_TAG = 54509

  final val PHC_UPDATE_SYNC_TAG = 54507


  final val HC_QUERY_RATE_TAG = 52513

  final val HC_REPLY_RATE_TAG = 52511


  final val HC_UPDATE_ADD_HTLC_TAG = 53505

  final val HC_UPDATE_FULFILL_HTLC_TAG = 53503

  final val HC_UPDATE_FAIL_HTLC_TAG = 53501

  final val HC_UPDATE_FAIL_MALFORMED_HTLC_TAG = 53499

  final val HC_ERROR_TAG = 53497

  final val HC_MARGIN_CHANNEL_TAG = 53495

  val hostedMessageTags: Set[Int] =
    Set(HC_INVOKE_HOSTED_CHANNEL_TAG, HC_INIT_HOSTED_CHANNEL_TAG, HC_LAST_CROSS_SIGNED_STATE_TAG, HC_STATE_UPDATE_TAG,
      HC_STATE_OVERRIDE_TAG, HC_HOSTED_CHANNEL_BRANDING_TAG, HC_ANNOUNCEMENT_SIGNATURE_TAG, HC_RESIZE_CHANNEL_TAG,
      HC_MARGIN_CHANNEL_TAG, HC_ASK_BRANDING_INFO, HC_QUERY_RATE_TAG, HC_REPLY_RATE_TAG)

  val preimageQueryTags: Set[Int] = Set(HC_QUERY_PREIMAGES_TAG, HC_REPLY_PREIMAGES_TAG)

  val announceTags: Set[Int] = Set(PHC_ANNOUNCE_GOSSIP_TAG, PHC_ANNOUNCE_SYNC_TAG, PHC_UPDATE_GOSSIP_TAG, PHC_UPDATE_SYNC_TAG)

  val chanIdMessageTags: Set[Int] = Set(HC_UPDATE_ADD_HTLC_TAG, HC_UPDATE_FULFILL_HTLC_TAG, HC_UPDATE_FAIL_HTLC_TAG, HC_UPDATE_FAIL_MALFORMED_HTLC_TAG, HC_ERROR_TAG)

  val remoteNode2Connection: mutable.Map[PublicKey, PeerConnectedWrap] = TMap.empty[PublicKey, PeerConnectedWrap].single
}

class FC extends Plugin with RouteProvider {
  var channelsDb: HostedChannelsDb = _
  var preimageRef: ActorRef = _
  var workerRef: ActorRef = _
  var syncRef: ActorRef = _
  var rateOracleRef: ActorRef = _
  var ecbOracleRef: ActorRef = _
  var config: Config = _
  var kit: Kit = _

  override def onSetup(setup: Setup): Unit = {
    config = new Config(datadir = setup.datadir)
    Try(Blocking createTablesIfNotExist config.db)
    channelsDb = new HostedChannelsDb(config.db)
  }

  override def onKit(eclairKit: Kit): Unit = {
    implicit val coreActorSystem: ActorSystem = eclairKit.system
    preimageRef = eclairKit.system actorOf Props(classOf[PreimageBroadcastCatcher], new PreimagesDb(config.db), eclairKit, config.vals)
    syncRef = eclairKit.system actorOf Props(classOf[HostedSync], eclairKit, new HostedUpdatesDb(config.db), config.vals.phcConfig)
    rateOracleRef = eclairKit.system actorOf Props(classOf[RateOracle], eclairKit, new BinanceSourceModified(x => x, "BTCEUR", implicitly))
    ecbOracleRef = eclairKit.system actorOf Props(classOf[CentralBankOracle], eclairKit, new EcbSource("USD", implicitly))
    workerRef = eclairKit.system actorOf Props(classOf[Worker], eclairKit, syncRef, rateOracleRef, preimageRef, channelsDb, config)
    kit = eclairKit
  }

  override def params: PluginParams = new CustomFeaturePlugin with CustomCommitmentsPlugin {

    override def messageTags: Set[Int] = hostedMessageTags ++ preimageQueryTags ++ chanIdMessageTags

    override def name: String = "Fiat channels"

    override def feature: Feature = FCFeature

    override def getIncomingHtlcs(nodeParams: NodeParams, log: LoggingAdapter): Seq[IncomingHtlc] = {
      val allHotHtlcs: Seq[DirectedHtlc] = channelsDb.listHotChannels.flatMap(_.commitments.localSpec.htlcs)
      val decryptEither: UpdateAddHtlc => Either[FailureMessage, IncomingPaymentPacket] = IncomingPaymentPacket.decrypt(_: UpdateAddHtlc, nodeParams.privateKey)(log)
      val resolvePacket: PartialFunction[Either[FailureMessage, IncomingPaymentPacket], IncomingHtlc] = PostRestartHtlcCleaner.decryptedIncomingHtlcs(nodeParams.db.payments)
      allHotHtlcs.collect(DirectedHtlc.incoming).map(decryptEither).collect(resolvePacket)
    }

    private def htlcsOut = for {
      data <- channelsDb.listHotChannels
      outgoingAdd <- data.pendingHtlcs.collect(DirectedHtlc.outgoing)
      origin <- data.commitments.originChannels.get(outgoingAdd.id)
    } yield (origin, data.commitments.channelId, outgoingAdd.id)

    type PaymentHashAndHtlcId = (ByteVector32, Long)
    type PaymentLocations = Set[PaymentHashAndHtlcId]

    override def getHtlcsRelayedOut(htlcsIn: Seq[IncomingHtlc], nodeParams: NodeParams, log: LoggingAdapter): Map[Origin, PaymentLocations] =
      PostRestartHtlcCleaner.groupByOrigin(htlcsOut, htlcsIn)
  }

  override def route(eclairDirectives: EclairDirectives): Route = {
    import eclairDirectives._
    import fr.acinq.eclair.api.serde.FormParamExtractors._
    import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

    import scala.concurrent.ExecutionContext.Implicits.global

    val hostedStateUnmarshaller = "state".as[ByteVector](binaryDataUnmarshaller)

    def getHostedStateResult(state: ByteVector) = {
      val remoteState = FiatChannelCodecs.hostedStateCodec.decodeValue(state.toBitVector).require
      val remoteNodeIdOpt = Set(remoteState.nodeId1, remoteState.nodeId2).find(kit.nodeParams.nodeId.!=)
      val isLocalSigOk = remoteState.lastCrossSignedState.verifyRemoteSig(kit.nodeParams.nodeId)
      RemoteHostedStateResult(remoteState, remoteNodeIdOpt, isLocalSigOk)
    }

    def completeCommand(cmd: HasRemoteNodeIdHostedCommand)(implicit timeout: Timeout) = {
      val futureResponse = (workerRef ? cmd).mapTo[HCCommandResponse]
      complete(futureResponse)
    }

    val invoke: Route = postRequest("fc-invoke") { implicit t =>
      formFields("refundAddress", "secret".as[ByteVector](binaryDataUnmarshaller), nodeIdFormParam) { case (refundAddress, secret, remoteNodeId) =>
        val refundPubkeyScript = Script.write(fr.acinq.eclair.addressToPublicKeyScript(refundAddress, kit.nodeParams.chainHash))
        completeCommand(HC_CMD_LOCAL_INVOKE(remoteNodeId, refundPubkeyScript, secret))
      }
    }

    val externalFulfill: Route = postRequest("fc-externalfulfill") { implicit t =>
      formFields("htlcId".as[Long], "paymentPreimage".as[ByteVector32], nodeIdFormParam) { case (htlcId, paymentPreimage, remoteNodeId) =>
        completeCommand(HC_CMD_EXTERNAL_FULFILL(remoteNodeId, htlcId, paymentPreimage))
      }
    }

    val allChannels: Route = postRequest("fc-all") { implicit t =>
      val allChannelsFuture = for {
        onlineChannels <- (workerRef ? HC_CMD_GET_ALL_CHANNELS()).mapTo[HCCommandResponse]
        dbChannels = channelsDb.listAllChannels
        collectedChannels = onlineChannels match {
          case CMDAllInfo(channels) =>
            val offlineChannels = dbChannels.filter(data => !channels.contains(data.commitments.remoteNodeId.toString()))
            val offlineMap = Map.from(offlineChannels.map(data => (data.commitments.remoteNodeId.toString(), CMDResInfo(OFFLINE, data, data.commitments.localSpec))))
            CMDAllInfo(channels ++ offlineMap)
          case x => x
        }
      } yield collectedChannels
      complete(allChannelsFuture)
    }

    val findByRemoteId: Route = postRequest("fc-findbyremoteid") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_GET_INFO(remoteNodeId))
      }
    }

    val overridePropose: Route = postRequest("fc-overridepropose") { implicit t =>
      formFields("newLocalBalanceMsat".as[MilliSatoshi], nodeIdFormParam) { case (newLocalBalance, remoteNodeId) =>
        completeCommand(HC_CMD_OVERRIDE_PROPOSE(remoteNodeId, newLocalBalance))
      }
    }

    val overrideAccept: Route = postRequest("fc-overrideaccept") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_OVERRIDE_ACCEPT(remoteNodeId))
      }
    }

    val makePublic: Route = postRequest("fc-makepublic") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_PUBLIC(remoteNodeId))
      }
    }

    val makePrivate: Route = postRequest("fc-makeprivate") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_PRIVATE(remoteNodeId))
      }
    }

    val resize: Route = postRequest("fc-resize") { implicit t =>
      formFields("newCapacitySat".as[Satoshi], nodeIdFormParam) { case (newCapacity, remoteNodeId) =>
        completeCommand(HC_CMD_RESIZE(remoteNodeId, newCapacity))
      }
    }

    val margin: Route = postRequest("fc-margin") { implicit t =>
      formFields("newCapacitySat".as[Satoshi], "newRate".as[MilliSatoshi], nodeIdFormParam) { case (newCapacity, newRate, remoteNodeId) =>
        completeCommand(HC_CMD_MARGIN(remoteNodeId, newCapacity, newRate))
      }
    }

    val suspend: Route = postRequest("fc-suspend") { implicit t =>
      formFields(nodeIdFormParam) { remoteNodeId =>
        completeCommand(HC_CMD_SUSPEND(remoteNodeId))
      }
    }

    val verifyRemoteState: Route = postRequest("fc-verifyremotestate") { implicit t =>
      formFields(hostedStateUnmarshaller) { state =>
        complete(getHostedStateResult(state))
      }
    }

    val restoreFromRemoteState: Route = postRequest("fc-restorefromremotestate") { implicit t =>
      formFields(hostedStateUnmarshaller) { state =>
        val RemoteHostedStateResult(remoteState, Some(remoteNodeId), isLocalSigOk) = getHostedStateResult(state)
        require(isLocalSigOk, "Can't proceed: local signature of provided HC state is invalid")
        completeCommand(HC_CMD_RESTORE(remoteNodeId, remoteState))
      }
    }

    val broadcastPreimages: Route = postRequest("fc-broadcastpreimages") { implicit t =>
      formFields("preimages".as[List[ByteVector32]], "feerateSatByte".as[FeeratePerByte]) { case (preimages, feerateSatByte) =>
        require(feerateSatByte.feerate.toLong > 1, "Preimage broadcast funding feerate must be higher than 1 sat/byte")
        val cmd = PreimageBroadcastCatcher.SendPreimageBroadcast(FeeratePerKw(feerateSatByte), preimages.toSet)
        val broadcastTxIdResult = (preimageRef ? cmd).mapTo[ByteVector32]
        complete(broadcastTxIdResult)
      }
    }

    val hotChannels: Route = postRequest("fc-hot") { implicit t =>
      complete(channelsDb.listHotChannels)
    }

    invoke ~ externalFulfill ~ allChannels ~ findByRemoteId ~ overridePropose ~ overrideAccept ~
      makePublic ~ makePrivate ~ resize ~ suspend ~ verifyRemoteState ~ restoreFromRemoteState ~
      broadcastPreimages ~ hotChannels
  }
}

case object FCFeature extends Feature with InitFeature with NodeFeature {
  val plugin: UnknownFeature = UnknownFeature(optional)
  val rfcName = "hosted_channels"
  lazy val mandatory = 52972
}

case object ResizeableFCFeature extends Feature with InitFeature with NodeFeature {
  val plugin: UnknownFeature = UnknownFeature(optional)
  val rfcName = "resizeable_hosted_channels"
  lazy val mandatory = 52974
}

// Depends on https://github.com/engenegr/eclair-alarmbot-plugin
case class AlmostTimedoutIncomingHtlc(add: wire.protocol.UpdateAddHtlc, fulfill: wire.protocol.UpdateFulfillHtlc, nodeId: PublicKey, blockHeight: Long) extends fr.acinq.alarmbot.CustomAlarmBotMessage {
  override def message: String = s"AlmostTimedoutIncomingHtlc, id=${add.id}, amount=${add.amountMsat}, hash=${add.paymentHash}, expiry=${add.cltvExpiry.toLong}, tip=$blockHeight, preimage=${fulfill.paymentPreimage}, peer=$nodeId"
  override def senderEntity: String = "FC"
}

case class FCSuspended(nodeId: PublicKey, isHost: Boolean, isLocal: Boolean, description: String) extends fr.acinq.alarmbot.CustomAlarmBotMessage {
  override def message: String = s"FCSuspended, isHost=$isHost, isLocal=$isLocal, peer=$nodeId, description=$description"
  override def senderEntity: String = "FC"
}

case class FCHedgeLiability(channel: String, amount: MilliSatoshi, rate: MilliSatoshi) extends fr.acinq.alarmbot.ExternalHedgeMessage {
  override def senderEntity: String = "FC"
}