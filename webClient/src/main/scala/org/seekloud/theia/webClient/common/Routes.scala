package org.seekloud.theia.webClient.common

import org.scalajs.dom

/**
  * create by zhaoyin
  * 2019/7/18  10:20 AM
  */
object Routes {

  private val base = "/theia/roomManager"

  object UserRoutes {

    private val urlbase = base + "/user"
    private val urlRecord = base + "/record"
    private val urlComment = base + "/recordComment"

    val userRegister = urlbase + "/signUp"

    val userLogin = urlbase + "/signIn"

    val userLoginByMail = urlbase + "/signInByMail"

    val getRoomList = urlbase + "/getRoomList"

    val searchRoom = urlbase + "/searchRoom"

    val temporaryUser = urlbase + "/temporaryUser"


    /*录像相关*/

    //参会人查看自己可见的会议录像列表
    def getRecordList(sortBy: String, pageNum: Int, pageSize: Int, uid: Long) = urlRecord + s"/getRecordList?sortBy=$sortBy&pageNum=$pageNum&pageSize=$pageSize&uid=$uid"
    //参会人查看会议录像和会议信息
    val getOneRecord = urlRecord + "/searchRecord"
    //（废弃）
    val watchRecordOver = urlRecord + "/watchRecordOver"
    //会议发起者可以邀请其他未参会用户查看会议录像
    val inviteToWatchRecord = urlRecord + "/inviteWatchRecord"
    //会议发起者查看邀请列表
    val getInviteList = urlRecord + "/getInviteInfo"
    //会议发起者删除邀请信息
    val deleteInviteInfo = urlRecord + "/deleteWatchInvite"


    /*评论相关*/

    //查看评论
    val getCommentInfo = urlComment+"/getRecordCommentList"
    //添加评论
    val sendCommentInfo = urlComment+"/addRecordComment"
    //会议发起者可以删除评论
    val deleteCommentInfo = urlComment+"/deleteComment"

    def uploadImg(imgType: Int, userId: String) = base + s"/file/uploadFile?imgType=$imgType&userId=$userId"

    def nickNameChange(userId: Long, userName: String) = urlbase + s"/nickNameChange?userId=$userId&newName=$userName"

  }

  object AdminRoutes {

    private val urlAdmin = base + "/admin"
    private val urlStat = base + "/statistic"

    val adminSignIn = urlAdmin + "/adminSignIn"

    val admingetUserList = urlAdmin + "/getUserList"

    val adminsealAccount = urlAdmin + "/sealAccount"

    val admincancelSealAccount = urlAdmin + "/cancelSealAccount"

    val adminDeleteRecord = urlAdmin + "/deleteRecord"

    val adminbanOnAnchor = urlAdmin + "/banOnAnchor"

    val loginDataByHour = urlStat + "/loginDataByHour"

    val getLoginData = urlStat + "/getLoginData"

    val watchObserve = urlStat + "/watchObserve"

    val watchObserveByHour = urlStat + "/watchObserveByHour"
  }

  val getToken = base + "/rtmp/getToken"

  def getWsSocketUri(liveId: String, liveCode: String): String = {
    val wsProtocol = if (dom.document.location.protocol == "https:") "wss" else "ws"
    s"$wsProtocol://10.1.29.246:41650/webrtcServer/userJoin?liveId=$liveId&liveCode=$liveCode"
  }

  def rmWebScocketUri(userId: Long, token: String, roomId: Long) = {
    val wsProtocol = if (dom.document.location.protocol == "https:") "wss" else "ws"
    s"$wsProtocol://${dom.document.location.host}/theia/roomManager/user/setupWebSocket?userId=$userId&token=$token&roomId=$roomId"
  }
}
