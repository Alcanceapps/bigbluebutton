package org.bigbluebutton.core.running

import java.io.{ PrintWriter, StringWriter }

import akka.actor._
import akka.actor.SupervisorStrategy.Resume
import org.bigbluebutton.common2.domain.DefaultProps
import org.bigbluebutton.core._
import org.bigbluebutton.core.api._
import org.bigbluebutton.core.apps._
import org.bigbluebutton.core.apps.caption.CaptionApp2x
import org.bigbluebutton.core.apps.deskshare.DeskshareApp2x
import org.bigbluebutton.core.apps.presentation.PresentationApp2x
import org.bigbluebutton.core.apps.users.UsersApp2x
import org.bigbluebutton.core.apps.sharednotes.SharedNotesApp2x
import org.bigbluebutton.core.bus._
import org.bigbluebutton.core.models._
import org.bigbluebutton.core2.MeetingStatus2x
import org.bigbluebutton.core2.message.handlers._
import org.bigbluebutton.core2.message.handlers.users._
import org.bigbluebutton.common2.msgs._
import org.bigbluebutton.core.apps.breakout._
import org.bigbluebutton.core.apps.polls._
import org.bigbluebutton.core.apps.voice._

import scala.concurrent.duration._
import org.bigbluebutton.core2.testdata.FakeTestData
import org.bigbluebutton.core.apps.layout.LayoutApp2x
import org.bigbluebutton.core.apps.meeting.SyncGetMeetingInfoRespMsgHdlr

object MeetingActor {
  def props(props: DefaultProps,
    eventBus: IncomingEventBus,
    outGW: OutMessageGateway, liveMeeting: LiveMeeting): Props =
    Props(classOf[MeetingActor], props, eventBus, outGW, liveMeeting)
}

class MeetingActor(val props: DefaultProps,
  val eventBus: IncomingEventBus,
  val outGW: OutMessageGateway,
  val liveMeeting: LiveMeeting)
    extends BaseMeetingActor
    with GuestsApp
    with LayoutApp2x
    with VoiceApp2x
    with PollApp2x
    with BreakoutApp2x
    with UsersApp2x

    with PresentationApp
    with ChatApp
    with WhiteboardApp

    with PermisssionCheck
    with UserBroadcastCamStartMsgHdlr
    with UserJoinMeetingReqMsgHdlr
    with UserBroadcastCamStopMsgHdlr
    with UserConnectedToGlobalAudioHdlr
    with UserDisconnectedFromGlobalAudioHdlr
    with MuteAllExceptPresentersCmdMsgHdlr
    with MuteMeetingCmdMsgHdlr
    with IsMeetingMutedReqMsgHdlr
    with MuteUserCmdMsgHdlr
    with EjectUserFromVoiceCmdMsgHdlr

    with SyncGetMeetingInfoRespMsgHdlr {

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case e: Exception => {
      val sw: StringWriter = new StringWriter()
      sw.write("An exception has been thrown on MeetingActor, exception message [" + e.getMessage() + "] (full stacktrace below)\n")
      e.printStackTrace(new PrintWriter(sw))
      log.error(sw.toString())
      Resume
    }
  }

  /**
   * Put the internal message injector into another actor so this
   * actor is easy to test.
   */
  var actorMonitor = context.actorOf(MeetingActorInternal.props(props, eventBus, outGW),
    "actorMonitor-" + props.meetingProp.intId)

  /** Subscribe to meeting and voice events. **/
  eventBus.subscribe(actorMonitor, props.meetingProp.intId)
  eventBus.subscribe(actorMonitor, props.voiceProp.voiceConf)
  eventBus.subscribe(actorMonitor, props.screenshareProps.screenshareConf)

  val presentationApp2x = new PresentationApp2x(liveMeeting, outGW = outGW)
  val deskshareApp2x = new DeskshareApp2x(liveMeeting, outGW = outGW)
  val captionApp2x = new CaptionApp2x(liveMeeting, outGW = outGW)
  val sharedNotesApp2x = new SharedNotesApp2x(liveMeeting, outGW = outGW)

  /*******************************************************************/
  //object FakeTestData extends FakeTestData
  //FakeTestData.createFakeUsers(liveMeeting)
  /*******************************************************************/

  def receive = {
    //=============================
    // 2x messages
    case msg: BbbCommonEnvCoreMsg => handleBbbCommonEnvCoreMsg(msg)

    case m: GetAllMeetingsReqMsg => handleGetAllMeetingsReqMsg(m)

    //======================================

    //=======================================
    // old messages
    case msg: ActivityResponse => handleActivityResponse(msg)
    case msg: MonitorNumberOfUsers => handleMonitorNumberOfUsers(msg)
    case msg: VoiceConfRecordingStartedMessage => handleVoiceConfRecordingStartedMessage(msg)

    case msg: AllowUserToShareDesktop => handleAllowUserToShareDesktop(msg)

    case msg: GetChatHistoryRequest => handleGetChatHistoryRequest(msg)
    case msg: SendPublicMessageRequest => handleSendPublicMessageRequest(msg)
    case msg: SendPrivateMessageRequest => handleSendPrivateMessageRequest(msg)
    case msg: UserConnectedToGlobalAudio => handleUserConnectedToGlobalAudio(msg)
    case msg: UserDisconnectedFromGlobalAudio => handleUserDisconnectedFromGlobalAudio(msg)
    case msg: InitializeMeeting => handleInitializeMeeting(msg)
    case msg: SetRecordingStatus => handleSetRecordingStatus(msg)
    case msg: GetRecordingStatus => handleGetRecordingStatus(msg)
    case msg: LogoutEndMeeting => handleLogoutEndMeeting(msg)
    case msg: ClearPublicChatHistoryRequest => handleClearPublicChatHistoryRequest(msg)

    case msg: ExtendMeetingDuration => handleExtendMeetingDuration(msg)
    case msg: SendTimeRemainingUpdate => handleSendTimeRemainingUpdate(msg)
    case msg: EndMeeting => handleEndMeeting(msg)

    case msg: DeskShareStartedRequest => handleDeskShareStartedRequest(msg)
    case msg: DeskShareStoppedRequest => handleDeskShareStoppedRequest(msg)
    case msg: DeskShareRTMPBroadcastStartedRequest => handleDeskShareRTMPBroadcastStartedRequest(msg)
    case msg: DeskShareRTMPBroadcastStoppedRequest => handleDeskShareRTMPBroadcastStoppedRequest(msg)
    case msg: DeskShareGetDeskShareInfoRequest => handleDeskShareGetDeskShareInfoRequest(msg)

    // Guest
    case msg: GetGuestPolicy => handleGetGuestPolicy(msg)
    case msg: SetGuestPolicy => handleSetGuestPolicy(msg)

    case _ => // do nothing
  }

  private def handleBbbCommonEnvCoreMsg(msg: BbbCommonEnvCoreMsg): Unit = {
    msg.core match {

      // Users
      case m: ValidateAuthTokenReqMsg => handleValidateAuthTokenReqMsg(m)
      case m: RegisterUserReqMsg => handleRegisterUserReqMsg(m)
      case m: UserJoinMeetingReqMsg => handleUserJoinMeetingReqMsg(m)
      case m: UserLeaveReqMsg => handleUserLeaveReqMsg(m)
      case m: UserBroadcastCamStartMsg => handleUserBroadcastCamStartMsg(m)
      case m: UserBroadcastCamStopMsg => handleUserBroadcastCamStopMsg(m)
      case m: UserJoinedVoiceConfEvtMsg => handleUserJoinedVoiceConfEvtMsg(m)

      // Whiteboard
      case m: SendCursorPositionPubMsg => handleSendCursorPositionPubMsg(m)
      case m: ClearWhiteboardPubMsg => handleClearWhiteboardPubMsg(m)
      case m: UndoWhiteboardPubMsg => handleUndoWhiteboardPubMsg(m)
      case m: ModifyWhiteboardAccessPubMsg => handleModifyWhiteboardAccessPubMsg(m)
      case m: GetWhiteboardAccessReqMsg => handleGetWhiteboardAccessReqMsg(m)
      case m: SendWhiteboardAnnotationPubMsg => handleSendWhiteboardAnnotationPubMsg(m)
      case m: GetWhiteboardAnnotationsReqMsg => handleGetWhiteboardAnnotationsReqMsg(m)

      // Poll
      case m: StartPollReqMsg => handleStartPollReqMsg(m)
      case m: StartCustomPollReqMsg => handleStartCustomPollReqMsg(m)
      case m: StopPollReqMsg => handleStopPollReqMsg(m)
      case m: ShowPollResultReqMsg => handleShowPollResultReqMsg(m)
      case m: HidePollResultReqMsg => handleHidePollResultReqMsg(m)
      case m: GetCurrentPollReqMsg => handleGetCurrentPollReqMsg(m)
      case m: RespondToPollReqMsg => handleRespondToPollReqMsg(m)

      // Breakout
      case m: BreakoutRoomsListMsg => handleBreakoutRoomsListMsg(m)
      case m: CreateBreakoutRoomSysCmdMsg => handleCreateBreakoutRoomsCmdMsg(m)
      case m: EndAllBreakoutRoomsMsg => handleEndAllBreakoutRoomsMsg(m)
      case m: RequestBreakoutJoinURLReqMsg => handleRequestBreakoutJoinURLReqMsg(m)
      case m: BreakoutRoomCreatedMsg => handleBreakoutRoomCreatedMsg(m)
      case m: BreakoutRoomEndedMsg => handleBreakoutRoomEndedMsg(m)
      case m: BreakoutRoomUsersUpdateMsg => handleBreakoutRoomUsersUpdateMsg(m)
      case m: SendBreakoutUsersUpdateMsg => handleSendBreakoutUsersUpdateMsg(m)
      case m: TransferUserToMeetingRequestMsg => handleTransferUserToMeetingRequestMsg(m)

      // Voice
      case m: UserLeftVoiceConfEvtMsg => handleUserLeftVoiceConfEvtMsg(m)
      case m: UserMutedInVoiceConfEvtMsg => handleUserMutedInVoiceConfEvtMsg(m)
      case m: UserTalkingInVoiceConfEvtMsg => handleUserTalkingInVoiceConfEvtMsg(m)

      // Layout
      case m: GetCurrentLayoutReqMsg => handleGetCurrentLayoutReqMsg(m)
      case m: LockLayoutMsg => handleLockLayoutMsg(m)
      case m: BroadcastLayoutMsg => handleBroadcastLayoutMsg(m)

      // Presentation
      case m: SetCurrentPresentationPubMsg => presentationApp2x.handleSetCurrentPresentationPubMsg(m)
      case m: GetPresentationInfoReqMsg => presentationApp2x.handleGetPresentationInfoReqMsg(m)
      case m: SetCurrentPagePubMsg => presentationApp2x.handleSetCurrentPagePubMsg(m)
      case m: ResizeAndMovePagePubMsg => presentationApp2x.handleResizeAndMovePagePubMsg(m)
      case m: RemovePresentationPubMsg => presentationApp2x.handleRemovePresentationPubMsg(m)
      case m: PreuploadedPresentationsSysPubMsg => presentationApp2x.handlePreuploadedPresentationsPubMsg(m)
      case m: PresentationConversionUpdateSysPubMsg => presentationApp2x.handlePresentationConversionUpdatePubMsg(m)
      case m: PresentationPageCountErrorSysPubMsg => presentationApp2x.handlePresentationPageCountErrorPubMsg(m)
      case m: PresentationPageGeneratedSysPubMsg => presentationApp2x.handlePresentationPageGeneratedPubMsg(m)
      case m: PresentationConversionCompletedSysPubMsg => presentationApp2x.handlePresentationConversionCompletedPubMsg(m)

      // Caption
      case m: EditCaptionHistoryPubMsg => captionApp2x.handleEditCaptionHistoryPubMsg(m)
      case m: UpdateCaptionOwnerPubMsg => captionApp2x.handleUpdateCaptionOwnerPubMsg(m)
      case m: SendCaptionHistoryReqMsg => captionApp2x.handleSendCaptionHistoryReqMsg(m)

      // SharedNotes
      case m: GetSharedNotesPubMsg => sharedNotesApp2x.handleGetSharedNotesPubMsg(m)
      case m: SyncSharedNotePubMsg => sharedNotesApp2x.handleSyncSharedNotePubMsg(m)
      case m: UpdateSharedNoteReqMsg => sharedNotesApp2x.handleUpdateSharedNoteReqMsg(m)
      case m: CreateSharedNoteReqMsg => sharedNotesApp2x.handleCreateSharedNoteReqMsg(m)
      case m: DestroySharedNoteReqMsg => sharedNotesApp2x.handleDestroySharedNoteReqMsg(m)

      // Guests
      case m: GetGuestsWaitingApprovalReqMsg => handleGetGuestsWaitingApprovalReqMsg(m)
      case m: SetGuestPolicyMsg => handleSetGuestPolicyMsg(m)
      case m: GuestsWaitingApprovedMsg => handleGuestsWaitingApprovedMsg(m)

      case _ => log.warning("***** Cannot handle " + msg.envelope.name)
    }
  }

  def handleGetAllMeetingsReqMsg(msg: GetAllMeetingsReqMsg): Unit = {
    // sync all meetings
    handleSyncGetMeetingInfoRespMsg(liveMeeting.props)

    // sync all users
    handleSyncGetUsersMeetingRespMsg()

    // sync all presentations
    presentationApp2x.handleSyncGetPresentationInfoRespMsg()

    // TODO send all chat
    // TODO send all lock settings
    // TODO send all screen sharing info
  }

  def handleDeskShareRTMPBroadcastStoppedRequest(msg: DeskShareRTMPBroadcastStoppedRequest): Unit = {
    log.info("handleDeskShareRTMPBroadcastStoppedRequest: isBroadcastingRTMP=" +
      DeskshareModel.isBroadcastingRTMP(liveMeeting.deskshareModel) + " URL:" +
      DeskshareModel.getRTMPBroadcastingUrl(liveMeeting.deskshareModel))

    // only valid if currently broadcasting
    if (DeskshareModel.isBroadcastingRTMP(liveMeeting.deskshareModel)) {
      log.info("STOP broadcast ALLOWED when isBroadcastingRTMP=true")
      DeskshareModel.broadcastingRTMPStopped(liveMeeting.deskshareModel)

      // notify viewers that RTMP broadcast stopped
      //outGW.send(new DeskShareNotifyViewersRTMP(props.meetingProp.intId,
      //  DeskshareModel.getRTMPBroadcastingUrl(liveMeeting.deskshareModel),
      //  msg.videoWidth, msg.videoHeight, false))
    } else {
      log.info("STOP broadcast NOT ALLOWED when isBroadcastingRTMP=false")
    }
  }

  def handleDeskShareGetDeskShareInfoRequest(msg: DeskShareGetDeskShareInfoRequest): Unit = {

    log.info("handleDeskShareGetDeskShareInfoRequest: " + msg.conferenceName + "isBroadcasting="
      + DeskshareModel.isBroadcastingRTMP(liveMeeting.deskshareModel) + " URL:" +
      DeskshareModel.getRTMPBroadcastingUrl(liveMeeting.deskshareModel))

    if (DeskshareModel.isBroadcastingRTMP(liveMeeting.deskshareModel)) {
      // if the meeting has an ongoing WebRTC Deskshare session, send a notification
      //outGW.send(new DeskShareNotifyASingleViewer(props.meetingProp.intId, msg.requesterID,
      //  DeskshareModel.getRTMPBroadcastingUrl(liveMeeting.deskshareModel),
      //  DeskshareModel.getDesktopShareVideoWidth(liveMeeting.deskshareModel),
      //  DeskshareModel.getDesktopShareVideoHeight(liveMeeting.deskshareModel), true))
    }
  }

  def handleGetGuestPolicy(msg: GetGuestPolicy) {
    //   outGW.send(new GetGuestPolicyReply(msg.meetingID, props.recordProp.record,
    //     msg.requesterID, MeetingStatus2x.getGuestPolicy(liveMeeting.status).toString()))
  }

  def handleSetGuestPolicy(msg: SetGuestPolicy) {
    //    MeetingStatus2x.setGuestPolicy(liveMeeting.status, msg.policy)
    //    MeetingStatus2x.setGuestPolicySetBy(liveMeeting.status, msg.setBy)
    //    outGW.send(new GuestPolicyChanged(msg.meetingID, props.recordProp.record,
    //      MeetingStatus2x.getGuestPolicy(liveMeeting.status).toString()))
  }

  def handleLogoutEndMeeting(msg: LogoutEndMeeting) {
    for {
      u <- Users2x.findWithIntId(liveMeeting.users2x, msg.userID)
    } yield {
      if (u.role == Roles.MODERATOR_ROLE) {
        handleEndMeeting(EndMeeting(props.meetingProp.intId))
      }
    }
  }

  def handleActivityResponse(msg: ActivityResponse) {
    log.info("User endorsed that meeting {} is active", props.meetingProp.intId)
    outGW.send(new MeetingIsActive(props.meetingProp.intId))
  }

  def handleEndMeeting(msg: EndMeeting) {
    // Broadcast users the meeting will end
    outGW.send(new MeetingEnding(msg.meetingId))

    MeetingStatus2x.meetingHasEnded(liveMeeting.status)

    outGW.send(new MeetingEnded(msg.meetingId, props.recordProp.record, props.meetingProp.intId))
  }

  def handleAllowUserToShareDesktop(msg: AllowUserToShareDesktop): Unit = {
    Users2x.findPresenter(liveMeeting.users2x) match {
      case Some(curPres) => {
        val allowed = msg.userID equals (curPres.intId)
        outGW.send(AllowUserToShareDesktopOut(msg.meetingID, msg.userID, allowed))
      }
      case None => // do nothing
    }
  }

  def handleVoiceConfRecordingStartedMessage(msg: VoiceConfRecordingStartedMessage) {
    if (msg.recording) {
      MeetingStatus2x.setVoiceRecordingFilename(liveMeeting.status, msg.recordStream)
      outGW.send(new VoiceRecordingStarted(props.meetingProp.intId, props.recordProp.record,
        msg.recordStream, msg.timestamp, props.voiceProp.voiceConf))
    } else {
      MeetingStatus2x.setVoiceRecordingFilename(liveMeeting.status, "")
      outGW.send(new VoiceRecordingStopped(props.meetingProp.intId, props.recordProp.record,
        msg.recordStream, msg.timestamp, props.voiceProp.voiceConf))
    }
  }

  def handleSetRecordingStatus(msg: SetRecordingStatus) {
    log.info("Change recording status. meetingId=" + props.meetingProp.intId + " recording=" + msg.recording)
    if (props.recordProp.allowStartStopRecording &&
      MeetingStatus2x.isRecording(liveMeeting.status) != msg.recording) {
      if (msg.recording) {
        MeetingStatus2x.recordingStarted(liveMeeting.status)
      } else {
        MeetingStatus2x.recordingStopped(liveMeeting.status)
      }

      outGW.send(new RecordingStatusChanged(props.meetingProp.intId, props.recordProp.record, msg.userId, msg.recording))
    }
  }

  // WebRTC Desktop Sharing

  def handleDeskShareStartedRequest(msg: DeskShareStartedRequest): Unit = {
    log.info("handleDeskShareStartedRequest: dsStarted=" + DeskshareModel.getDeskShareStarted(liveMeeting.deskshareModel))

    if (!DeskshareModel.getDeskShareStarted(liveMeeting.deskshareModel)) {
      val timestamp = System.currentTimeMillis().toString
      val streamPath = "rtmp://" + props.screenshareProps.red5ScreenshareIp + "/" + props.screenshareProps.red5ScreenshareApp +
        "/" + props.meetingProp.intId + "/" + props.meetingProp.intId + "-" + timestamp
      log.info("handleDeskShareStartedRequest: streamPath=" + streamPath)

      // Tell FreeSwitch to broadcast to RTMP
      outGW.send(new DeskShareStartRTMPBroadcast(msg.conferenceName, streamPath))

      DeskshareModel.setDeskShareStarted(liveMeeting.deskshareModel, true)
    }
  }

  def handleDeskShareStoppedRequest(msg: DeskShareStoppedRequest): Unit = {
    log.info("handleDeskShareStoppedRequest: dsStarted=" +
      DeskshareModel.getDeskShareStarted(liveMeeting.deskshareModel) +
      " URL:" + DeskshareModel.getRTMPBroadcastingUrl(liveMeeting.deskshareModel))

    // Tell FreeSwitch to stop broadcasting to RTMP
    outGW.send(new DeskShareStopRTMPBroadcast(msg.conferenceName,
      DeskshareModel.getRTMPBroadcastingUrl(liveMeeting.deskshareModel)))

    DeskshareModel.setDeskShareStarted(liveMeeting.deskshareModel, false)
  }

  def handleDeskShareRTMPBroadcastStartedRequest(msg: DeskShareRTMPBroadcastStartedRequest): Unit = {
    log.info("handleDeskShareRTMPBroadcastStartedRequest: isBroadcastingRTMP=" +
      DeskshareModel.isBroadcastingRTMP(liveMeeting.deskshareModel) +
      " URL:" + DeskshareModel.getRTMPBroadcastingUrl(liveMeeting.deskshareModel))

    // only valid if not broadcasting yet
    if (!DeskshareModel.isBroadcastingRTMP(liveMeeting.deskshareModel)) {
      DeskshareModel.setRTMPBroadcastingUrl(liveMeeting.deskshareModel, msg.streamname)
      DeskshareModel.broadcastingRTMPStarted(liveMeeting.deskshareModel)
      DeskshareModel.setDesktopShareVideoWidth(liveMeeting.deskshareModel, msg.videoWidth)
      DeskshareModel.setDesktopShareVideoHeight(liveMeeting.deskshareModel, msg.videoHeight)
      log.info("START broadcast ALLOWED when isBroadcastingRTMP=false")

      // Notify viewers in the meeting that there's an rtmp stream to view
      outGW.send(new DeskShareNotifyViewersRTMP(props.meetingProp.intId, msg.streamname, msg.videoWidth, msg.videoHeight, true))
    } else {
      log.info("START broadcast NOT ALLOWED when isBroadcastingRTMP=true")
    }
  }

  def handleMonitorNumberOfUsers(msg: MonitorNumberOfUsers) {
    monitorNumberOfWebUsers()
    monitorNumberOfUsers()
  }

  def monitorNumberOfWebUsers() {
    if (Users2x.numUsers(liveMeeting.users2x) == 0 &&
      MeetingStatus2x.lastWebUserLeftOn(liveMeeting.status) > 0) {
      if (liveMeeting.timeNowInMinutes - MeetingStatus2x.lastWebUserLeftOn(liveMeeting.status) > 2) {
        log.info("Empty meeting. Ejecting all users from voice. meetingId={}", props.meetingProp.intId)
        outGW.send(new EjectAllVoiceUsers(props.meetingProp.intId, props.recordProp.record, props.voiceProp.voiceConf))
      }
    }
  }

  def monitorNumberOfUsers() {
    val hasUsers = Users2x.numUsers(liveMeeting.users2x) != 0
    // TODO: We could use a better control over this message to send it just when it really matters :)
    eventBus.publish(BigBlueButtonEvent(props.meetingProp.intId, UpdateMeetingExpireMonitor(props.meetingProp.intId, hasUsers)))
  }

  def handleSendTimeRemainingUpdate(msg: SendTimeRemainingUpdate) {
    if (props.durationProps.duration > 0) {
      val endMeetingTime = MeetingStatus2x.startedOn(liveMeeting.status) + (props.durationProps.duration * 60)
      val timeRemaining = endMeetingTime - liveMeeting.timeNowInSeconds
      outGW.send(new MeetingTimeRemainingUpdate(props.meetingProp.intId, props.recordProp.record, timeRemaining.toInt))
    }
    if (!props.meetingProp.isBreakout && !BreakoutRooms.getRooms(liveMeeting.breakoutRooms).isEmpty) {
      val endMeetingTime = BreakoutRooms.breakoutRoomsStartedOn(liveMeeting.breakoutRooms) +
        (BreakoutRooms.breakoutRoomsdurationInMinutes(liveMeeting.breakoutRooms) * 60)
      val timeRemaining = endMeetingTime - liveMeeting.timeNowInSeconds
      outGW.send(new BreakoutRoomsTimeRemainingUpdateOutMessage(props.meetingProp.intId, props.recordProp.record, timeRemaining.toInt))
    } else if (BreakoutRooms.breakoutRoomsStartedOn(liveMeeting.breakoutRooms) != 0) {
      BreakoutRooms.breakoutRoomsdurationInMinutes(liveMeeting.breakoutRooms, 0)
      BreakoutRooms.breakoutRoomsStartedOn(liveMeeting.breakoutRooms, 0)
    }
  }

  def handleExtendMeetingDuration(msg: ExtendMeetingDuration) {

  }

  def handleGetRecordingStatus(msg: GetRecordingStatus) {
    outGW.send(new GetRecordingStatusReply(props.meetingProp.intId, props.recordProp.record,
      msg.userId, MeetingStatus2x.isRecording(liveMeeting.status).booleanValue()))
  }

  def startRecordingIfAutoStart() {
    if (props.recordProp.record && !MeetingStatus2x.isRecording(liveMeeting.status) &&
      props.recordProp.autoStartRecording && Users2x.numUsers(liveMeeting.users2x) == 1) {
      log.info("Auto start recording. meetingId={}", props.meetingProp.intId)
      MeetingStatus2x.recordingStarted(liveMeeting.status)
      outGW.send(new RecordingStatusChanged(props.meetingProp.intId, props.recordProp.record,
        "system", MeetingStatus2x.isRecording(liveMeeting.status)))
    }
  }

  def stopAutoStartedRecording() {
    if (props.recordProp.record && MeetingStatus2x.isRecording(liveMeeting.status) &&
      props.recordProp.autoStartRecording && Users2x.numUsers(liveMeeting.users2x) == 0) {
      log.info("Last web user left. Auto stopping recording. meetingId={}", props.meetingProp.intId)
      MeetingStatus2x.recordingStopped(liveMeeting.status)
      outGW.send(new RecordingStatusChanged(props.meetingProp.intId, props.recordProp.record,
        "system", MeetingStatus2x.isRecording(liveMeeting.status)))
    }
  }

  def sendMeetingHasEnded(userId: String) {
    outGW.send(new MeetingHasEnded(props.meetingProp.intId, userId))
    outGW.send(new DisconnectUser(props.meetingProp.intId, userId))
  }

  def record(msg: BbbCoreMsg): Unit = {
    if (liveMeeting.props.recordProp.record) {
      outGW.record(msg)
    }
  }
}
