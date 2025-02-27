package fr.acinq.fc.app

import akka.actor.{Actor, ActorRef, Props, Terminated}
import akka.pattern.{ask, pipe}
import com.google.common.collect.HashBiMap
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair
import fr.acinq.eclair.io._
import fr.acinq.eclair.router.{Router, SyncProgress}
import fr.acinq.eclair.wire.internal.channel.version3.FCProtocolCodecs
import fr.acinq.fc.app.channel._
import fr.acinq.fc.app.db.Blocking.timeout
import fr.acinq.fc.app.db.HostedChannelsDb
import fr.acinq.fc.app.network.HostedSync
import fr.acinq.fc.app.Worker._
import fr.acinq.fc.app.rate.RateOracle
import grizzled.slf4j.Logging
import scodec.Attempt

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._


object Worker {
  case object HCPeerConnected

  case object HCPeerDisconnected

  case object TickReconnectHosts { val label = "TickReconnectHosts" }

  case object TickClearIpAntiSpam { val label = "TickClearIpAntiSpam" }

  case object TickRemoveIdleChannels { val label = "TickRemoveIdleChannels" }

  case object TickUpdateRate { val label = "TickUpdateRate" }

  val notFound: CMDResFailure = CMDResFailure("HC with remote node is not found")
}

class Worker(kit: eclair.Kit, hostedSync: ActorRef, preimageCatcher: ActorRef, rateOracle: ActorRef, channelsDb: HostedChannelsDb, cfg: Config) extends Actor with Logging { me =>
  context.system.scheduler.scheduleWithFixedDelay(60.minutes, 60.minutes, self, Worker.TickClearIpAntiSpam)
  context.system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds, self, Worker.TickReconnectHosts)
  context.system.scheduler.scheduleWithFixedDelay(12.hours, 12.hours, self, TickRemoveIdleChannels)

  context.system.eventStream.subscribe(channel = classOf[UnknownMessageReceived], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerDisconnected], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerConnected], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[SyncProgress], subscriber = self)

  val inMemoryHostedChannels: HashBiMap[PublicKey, ActorRef] = HashBiMap.create[PublicKey, ActorRef]

  val ipAntiSpam: mutable.Map[Array[Byte], Int] = mutable.Map.empty[Array[Byte], Int] withDefaultValue 0

  var clientChannelRemoteNodeIds: Set[PublicKey] = Set.empty

  {
    val hotChannels = channelsDb.listHotChannels
    val clientChannels = channelsDb.listClientChannels

    val hotNodeIds = hotChannels.map(_.commitments.remoteNodeId).mkString(", ")
    val clientNodeIds = clientChannels.map(_.commitments.remoteNodeId).mkString(", ")
    logger.info(s"PLGN PHC, in-memory NodeIds, hot=$hotNodeIds, client=$clientNodeIds")

    clientChannelRemoteNodeIds ++= clientChannels.map(_.commitments.remoteNodeId)
    (clientChannels ++ hotChannels).distinctBy(_.commitments.remoteNodeId).foreach(spawnPreparedChannel)

    val nodeIdCheck = clientChannels.forall(_.commitments.localNodeId == kit.nodeParams.nodeId)
    logger.info(s"PLGN PHC, all HCs have the same NodeId which matches current NodeId=$nodeIdCheck")
    if (!nodeIdCheck) System.exit(0)
  }

  override def receive: Receive = {
    case systemMessage: PeerConnected
      if systemMessage.connectionInfo.remoteInit.features.hasPluginFeature(FCFeature.plugin) =>
      FC.remoteNode2Connection += systemMessage.nodeId -> PeerConnectedWrapNormal(info = systemMessage)
      Option(inMemoryHostedChannels get systemMessage.nodeId).foreach(_ |> HCPeerDisconnected |> HCPeerConnected)
      logger.info(s"PLGN FC, supporting peer connected, peer=${systemMessage.nodeId}")

    case systemMessage: PeerDisconnected =>
      FC.remoteNode2Connection -= systemMessage.nodeId
      Option(inMemoryHostedChannels get systemMessage.nodeId).foreach(_ ! HCPeerDisconnected)
      logger.info(s"PLGN FC, supporting peer disconnected, peer=${systemMessage.nodeId}")

    case Worker.TickClearIpAntiSpam => ipAntiSpam.clear

    case peerMsg @ UnknownMessageReceived(_, _, message, _) if FC.preimageQueryTags contains message.tag => preimageCatcher ! peerMsg
    case peerMsg @ UnknownMessageReceived(_, _, message, _) if FC.announceTags contains message.tag => hostedSync ! peerMsg

    case UnknownMessageReceived(_, nodeId, message, _) if FC.hostedMessageTags contains message.tag =>
      Tuple3(FCProtocolCodecs decodeHostedMessage message, FC.remoteNode2Connection get nodeId, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _, _) => logger.info(s"PLGN FC, hosted message decoding failed, messageTag=${message.tag}, peer=$nodeId")
        case (Attempt.Successful(_: ReplyPublicHostedChannelsEnd), Some(wrap), _) => hostedSync ! HostedSync.GotAllSyncFrom(wrap)
        case (Attempt.Successful(_: QueryPublicHostedChannels), Some(wrap), _) => hostedSync ! HostedSync.SendSyncTo(wrap)

        case (Attempt.Successful(_: AskBrandingInfo), Some(wrap), _) => if (cfg.vals.branding.enabled) wrap sendHostedChannelMsg cfg.brandingMessage
        // Special handling for InvokeHostedChannel: if chan exists neither in memory nor in db, then this is a new chan request and anti-spam rules apply
        case (Attempt.Successful(invoke: InvokeHostedChannel), Some(wrap), null) => restore(guardSpawn(nodeId, wrap, invoke), _ |> HCPeerConnected |> invoke)(nodeId)
        case (Attempt.Successful(_: HostedChannelMessage), _, null) => logger.info(s"PLGN FC, no target for HostedMessage, messageTag=${message.tag}, peer=$nodeId")
        case (Attempt.Successful(hosted: HostedChannelMessage), _, channelRef) => channelRef ! hosted
      }

    case UnknownMessageReceived(_, nodeId, message, _) if FC.chanIdMessageTags contains message.tag =>
      Tuple2(FCProtocolCodecs decodeHasChanIdMessage message, inMemoryHostedChannels get nodeId) match {
        case (_: Attempt.Failure, _) => logger.info(s"PLGN FC, HasChannelId message decoding fail, messageTag=${message.tag}, peer=$nodeId")
        case (Attempt.Successful(error: eclair.wire.protocol.Error), null) => restore(Tools.none, _ |> HCPeerConnected |> error)(nodeId)
        case (_, null) => logger.info(s"PLGN FC, no target for HasChannelIdMessage, messageTag=${message.tag}, peer=$nodeId")
        case (Attempt.Successful(msg), channelRef) => channelRef ! msg
      }

    case cmd: HC_CMD_LOCAL_INVOKE =>
      val isConnected = FC.remoteNode2Connection.contains(cmd.remoteNodeId)
      val isInDb = channelsDb.getChannelByRemoteNodeId(cmd.remoteNodeId).nonEmpty
      val isInMemory = Option(inMemoryHostedChannels get cmd.remoteNodeId).nonEmpty
      if (kit.nodeParams.nodeId == cmd.remoteNodeId) sender ! CMDResFailure("HC with itself is prohibited")
      else if (isInMemory || isInDb) sender ! CMDResFailure("HC with remote peer already exists")
      else if (!isConnected) sender ! CMDResFailure("Not yet connected to remote peer")
      else spawnChannel(cmd.remoteNodeId) |> HCPeerConnected |> cmd
      clientChannelRemoteNodeIds += cmd.remoteNodeId

    case cmd: HC_CMD_RESTORE =>
      val isInDb = channelsDb.getChannelByRemoteNodeId(cmd.remoteNodeId).nonEmpty
      val isInMemory = Option(inMemoryHostedChannels get cmd.remoteNodeId).nonEmpty
      if (kit.nodeParams.nodeId == cmd.remoteNodeId) sender ! CMDResFailure("HC with itself is prohibited")
      else if (isInMemory || isInDb) sender ! CMDResFailure("HC with remote peer already exists")
      else spawnChannel(cmd.remoteNodeId) ! cmd

    case cmd: HasRemoteNodeIdHostedCommand =>
      Option(inMemoryHostedChannels get cmd.remoteNodeId) match {
        case None => restore(sender ! notFound, _ |> cmd)(cmd.remoteNodeId)
        case Some(channelRef) => channelRef forward cmd
      }

    case cmd: HC_CMD_GET_ALL_CHANNELS =>
      val resMap = scala.collection.mutable.Map.empty[PublicKey, Future[CMDResInfo]]
      inMemoryHostedChannels.forEach((key, ref) => resMap(key) = (ref ? cmd).mapTo[CMDResInfo])

      val collected = Future.traverse(resMap.toList)((value) => value._2.map(x => (value._1.toString(), x))).map(xs => CMDAllInfo(Map.from(xs)))
      collected.pipeTo(sender())

    case Terminated(channelRef) =>
      inMemoryHostedChannels.inverse.remove(channelRef)

    case TickRemoveIdleChannels =>
      logger.info(s"PLGN FC, in-memory HC#=${inMemoryHostedChannels.size}")
      inMemoryHostedChannels.values.forEach(_ ! TickRemoveIdleChannels)

    case SyncProgress(1D) if clientChannelRemoteNodeIds.isEmpty =>
      // We need a fully loaded graph to find Host IP addresses and ports
      val clientChannels: Seq[HC_DATA_ESTABLISHED] = channelsDb.listClientChannels
      val clientRemoteNodeIds: Seq[PublicKey] = clientChannels.map(_.commitments.remoteNodeId)
      val nodeIdCheck = clientChannels.forall(_.commitments.localNodeId == kit.nodeParams.nodeId)

      if (!nodeIdCheck) {
        // Our nodeId has changed, this is very bad
        logger.info("PLGN FC, NODE ID CHECK FAILED")
        System.exit(0)
      } else {
        clientChannelRemoteNodeIds ++= clientRemoteNodeIds
        clientChannels.foreach(spawnPreparedChannel)
        self ! TickReconnectHosts
      }

    case TickReconnectHosts =>
      // Of all remote peers which are Hosts to our HCs, select those which are not connected
      val unconnectedHosts = clientChannelRemoteNodeIds.filterNot(FC.remoteNode2Connection.contains)

      if (unconnectedHosts.nonEmpty) {
        val routerData = (kit.router ? Router.GetRouterData).mapTo[Router.Data]
        logger.info(s"PLGN FC, unconnected hosts=$unconnectedHosts")

        for {
          data <- routerData
          nodeId <- unconnectedHosts
          nodeAnnouncement <- data.nodes.get(nodeId)
          sockAddress <- nodeAnnouncement.addresses.headOption.map(_.socketAddress)
          hostAndPort = HostAndPort.fromParts(sockAddress.getHostString, sockAddress.getPort)
          _ = logger.info(s"PLGN FC, trying to reconnect to ${nodeAnnouncement.nodeId}/$hostAndPort")
        } kit.switchboard ! Peer.Connect(NodeURI(nodeId, hostAndPort), self, isPersistent = false)
      }
  }

  def spawnChannel(nodeId: PublicKey): ActorRef = {
    val spawnedChannelProps = Props(classOf[HostedChannel], kit, nodeId, channelsDb, hostedSync, cfg)
    val channelRef = context watch context.actorOf(spawnedChannelProps)
    inMemoryHostedChannels.put(nodeId, channelRef)
    channelRef
  }

  def guardSpawn(nodeId: PublicKey, wrap: PeerConnectedWrap, invoke: InvokeHostedChannel): Unit = {
    if (ipAntiSpam(wrap.remoteIp) < cfg.vals.maxNewChansPerIpPerHour) spawnChannel(nodeId) |> HCPeerConnected |> invoke
    else wrap sendHasChannelIdMsg eclair.wire.protocol.Error(ByteVector32.Zeroes, ErrorCodes.ERR_HOSTED_CHANNEL_DENIED)
    // Record this request for anti-spam
    ipAntiSpam(wrap.remoteIp) += 1
  }

  def spawnPreparedChannel(data: HC_DATA_ESTABLISHED): ActorRef = {
    val channel = spawnChannel(data.commitments.remoteNodeId)
    channel ! data
    channel
  }

  def restore(onNotFound: => Unit, onFound: ActorRef => Unit)(nodeId: PublicKey): Unit =
    channelsDb.getChannelByRemoteNodeId(nodeId).map(spawnPreparedChannel) match {
      case Some(channelRef) => onFound(channelRef)
      case None => onNotFound
    }

  implicit class MultiSender(channel: ActorRef) {
    def |>(message: Any): MultiSender = {
      channel forward message
      this
    }
  }
}
