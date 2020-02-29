package org.seekloud.theia.roomManager.models.dao

import java.util.concurrent.atomic.AtomicLong

import org.seekloud.theia.roomManager.Boot.executor
import org.seekloud.theia.roomManager.utils.DBUtil.{db, _}
import org.seekloud.theia.roomManager.models.SlickTables._
import slick.jdbc.H2Profile.api._

import scala.util.{Failure, Success}

/**
  * User: haoxue
  * Date: 2020/02/07
  * Time: 14：16
  * Description: 对attend_event的一些数据库操作
  */
object AttendDao {


  val id = new AtomicLong(3000001)

  def addAttendEvent(uid:Long,roomId:Long,inTime:Long) ={
    db.run{
      tAttendEvent += rAttendEvent(id.getAndIncrement(),uid,roomId,inTime,0)
    }.andThen{
      case Success(_) =>
        log.debug("数据表Attend_Event操作成功！！")
      case Failure(ex) =>
        ex.printStackTrace()
    }
  }

  def inviteWatchRecord(uid: Long, roomid: Long) = {

    db.run(tAttendEvent += rAttendEvent(0L, uid, roomid, 0L, 0L))

  }


}
