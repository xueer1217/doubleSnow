package org.seekloud.theia.roomManager.models.dao

import java.util.concurrent.atomic.AtomicLong

import org.seekloud.theia.roomManager.models.SlickTables._
import slick.jdbc.PostgresProfile.api._
import org.seekloud.theia.roomManager.Boot.executor
import org.seekloud.theia.roomManager.utils.DBUtil._

import scala.concurrent.Future
/**
  * created by benyafang on 2019/9/23 16:20
  * */

object RecordCommentDAO {

  val id = new AtomicLong(5000001)

  def addRecordComment(r:rRecordComment):Future[Long] = {
    db.run(tRecordComment.returning(tRecordComment.map(_.id)) += r)
  }



  def getRecordComment(roomId:Long)= {
    db.run(tRecordComment.filter(r => r.roomId === roomId).result)
  }

  def deleteRecordComment(commentid:Long) ={
    db.run(tRecordComment.filter(_.id === commentid).delete)
  }


}
